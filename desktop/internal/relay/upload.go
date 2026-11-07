package relay

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"time"
)

type UploadFile struct {
	Name, MediaType string
	Data            []byte
}
type Attachment struct {
	Ref       string    `json:"ref"`
	Name      string    `json:"name"`
	Bytes     int64     `json:"bytes"`
	MediaType string    `json:"media_type"`
	SHA256    string    `json:"sha256"`
	ExpiresAt time.Time `json:"expires_at"`
}

var attachmentRef = regexp.MustCompile(`^[a-zA-Z0-9_-]{32}$`)

func digest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func ValidateUpload(files []UploadFile) error {
	if len(files) == 0 || len(files) > 8 {
		return errors.New("每次最多上传 8 个附件")
	}
	total := 0
	for _, f := range files {
		if len(f.Data) == 0 || len(f.Data) > 20*1024*1024 {
			return errors.New("单个附件须为 1 字节至 20 MiB")
		}
		total += len(f.Data)
	}
	if total > 50*1024*1024 {
		return errors.New("附件总量最多 50 MiB")
	}
	return nil
}
func Upload(ctx context.Context, r Requester, agent Agent, files []UploadFile, progress func(string, int64, int64)) (attachments []Attachment, err error) {
	if err = ValidateUpload(files); err != nil {
		return nil, err
	}
	specs := []Message{}
	var total int64
	for _, f := range files {
		specs = append(specs, Message{"name": f.Name, "media_type": f.MediaType, "bytes": len(f.Data)})
		total += int64(len(f.Data))
	}
	req := agent.Request("upload_begin")
	req["files"] = specs
	begin, err := r.Request(ctx, req)
	if err != nil {
		return nil, err
	}
	id := Text(begin, "upload_id")
	if id == "" {
		return nil, errors.New("未收到上传凭据")
	}
	completed := false
	defer func() {
		if !completed {
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			req := agent.Request("upload_cancel")
			req["upload_id"] = id
			_, _ = r.Request(cleanup, req)
		}
	}()
	chunkSize := Number(begin, "chunk_bytes")
	if chunkSize < 1 || chunkSize > 262144 {
		return nil, errors.New("上传分块大小无效")
	}
	hashes := []Message{}
	var sent int64
	for i, f := range files {
		hashes = append(hashes, Message{"file_index": i, "sha256": digest(f.Data)})
		for offset, sequence := 0, 0; offset < len(f.Data); sequence++ {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			part := f.Data[offset:min(len(f.Data), offset+int(chunkSize))]
			req := agent.Request("upload_chunk")
			req["upload_id"] = id
			req["file_index"] = i
			req["sequence"] = sequence
			req["data"] = base64.StdEncoding.EncodeToString(part)
			req["sha256"] = digest(part)
			ack, e := r.Request(ctx, req)
			if e != nil {
				return nil, e
			}
			if Number(ack, "next_sequence") != int64(sequence+1) {
				return nil, errors.New("上传分块确认不一致")
			}
			offset += len(part)
			sent += int64(len(part))
			if progress != nil {
				progress(f.Name, sent, total)
			}
		}
	}
	req = agent.Request("upload_finish")
	req["upload_id"] = id
	req["files"] = hashes
	finish, err := r.Request(ctx, req)
	if err != nil {
		return nil, err
	}
	attachments = Decode[[]Attachment](finish["attachments"])
	if len(attachments) != len(files) {
		return nil, errors.New("附件确认数量不一致")
	}
	for i, att := range attachments {
		f := files[i]
		if !attachmentRef.MatchString(att.Ref) || att.Bytes != int64(len(f.Data)) || att.SHA256 != digest(f.Data) || att.ExpiresAt.IsZero() {
			return nil, fmt.Errorf("附件 %s 校验失败", f.Name)
		}
	}
	completed = true
	return attachments, nil
}
func PromptWithAttachments(text string, attachments []Attachment, now time.Time) (string, error) {
	for _, att := range attachments {
		if !attachmentRef.MatchString(att.Ref) || !att.ExpiresAt.After(now) {
			return "", errors.New("附件已过期，请移除后重新添加")
		}
		text += "\nAttachment: " + att.Ref + "\n"
	}
	return text, nil
}
