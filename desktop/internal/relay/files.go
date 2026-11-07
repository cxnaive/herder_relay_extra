package relay

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"path"
	"strconv"
	"strings"
	"time"
)

type Requester interface {
	Request(context.Context, Message) (Message, error)
}

type FileEntry struct {
	Path string `json:"path"`
	Name string `json:"name"`
	Kind string `json:"kind"`
	Size int64  `json:"size"`
}
type Directory struct {
	Root       string      `json:"root"`
	Path       string      `json:"path"`
	Entries    []FileEntry `json:"entries"`
	NextCursor string      `json:"next_cursor"`
	Revision   string      `json:"revision"`
	Total      int         `json:"total"`
	Skipped    int         `json:"skipped"`
}
type FileMetadata struct {
	Path             string `json:"path"`
	Kind             string `json:"kind"`
	MediaType        string `json:"media_type"`
	Size             int64  `json:"size"`
	ReadOnly         bool   `json:"read_only"`
	PreviewAvailable bool   `json:"preview_available"`
	DownloadRevision string `json:"download_revision"`
	MetadataOnly     bool   `json:"metadata_only"`
}
type Document struct {
	Path, Kind, MediaType string
	Data                  []byte
}

func validFilePath(p string) bool {
	return p != "." && fs.ValidPath(p) && !strings.ContainsAny(p, "\x00\\")
}
func ParentDirectory(p string) string {
	parent := path.Dir(p)
	if parent == "." {
		return ""
	}
	return parent
}
func decodeFileMessage(m Message, out any) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, out)
}
func fileRequest(a Agent, action, p string) Message {
	m := a.Request(action)
	m["path"], m["file_scope"] = p, "workspace"
	return m
}
func ListDirectory(ctx context.Context, s Requester, a Agent, p string, previous *Directory) (Directory, error) {
	invalid := errors.New("目录已变化，请刷新后重试")
	if p != "" && !validFilePath(p) {
		return Directory{}, invalid
	}
	m := fileRequest(a, "workspace_list", p)
	m["limit"] = 200
	if previous != nil {
		if previous.Path != p || previous.NextCursor == "" {
			return Directory{}, invalid
		}
		m["cursor"] = previous.NextCursor
	}
	reply, err := s.Request(ctx, m)
	if err != nil {
		return Directory{}, err
	}
	var page Directory
	if decodeFileMessage(reply, &page) != nil || page.Path != p || !strings.HasPrefix(page.Root, "/") || page.Revision == "" || len(page.Entries) > 200 || page.Skipped < 0 || len(page.NextCursor) > 4096 {
		return Directory{}, invalid
	}
	if previous != nil && (previous.Revision != page.Revision || previous.Root != page.Root || (page.NextCursor != "" && previous.NextCursor == page.NextCursor)) {
		return Directory{}, invalid
	}
	seen := map[string]bool{}
	all := []FileEntry{}
	if previous != nil {
		all = append(all, previous.Entries...)
		for _, e := range all {
			seen[e.Path] = true
		}
	}
	for _, e := range page.Entries {
		if !validFilePath(e.Path) || ParentDirectory(e.Path) != p || path.Base(e.Path) != e.Name || seen[e.Path] || (e.Kind != "file" && e.Kind != "directory") || e.Size < 0 {
			return Directory{}, invalid
		}
		seen[e.Path] = true
		all = append(all, e)
	}
	if page.Total < len(all) || (page.NextCursor == "" && page.Total != len(all)) || (page.NextCursor != "" && len(page.Entries) == 0) {
		return Directory{}, invalid
	}
	page.Entries = all
	return page, nil
}
func validDigest(s string) bool {
	b, err := hex.DecodeString(s)
	return err == nil && len(b) == 32 && s == strings.ToLower(s)
}
func GetFileMetadata(ctx context.Context, s Requester, a Agent, p string) (FileMetadata, error) {
	var meta FileMetadata
	if !validFilePath(p) {
		return meta, errors.New("文件路径无效")
	}
	m := fileRequest(a, "workspace_file_download", p)
	m["metadata_only"] = true
	reply, err := s.Request(ctx, m)
	if err != nil {
		return meta, err
	}
	if decodeFileMessage(reply, &meta) != nil || meta.Path != p || !meta.ReadOnly || !meta.MetadataOnly || meta.Size < 0 || !validDigest(meta.DownloadRevision) {
		return FileMetadata{}, errors.New("文件信息已变化，请重新打开")
	}
	return meta, nil
}

// Download streams authenticated chunks to the caller's temporary file. Every
// chunk must belong to the same revision, path and offset; writes never retry.
func Download(ctx context.Context, s Requester, a Agent, meta FileMetadata, out io.Writer, progress func(int64)) error {
	if !validFilePath(meta.Path) || !validDigest(meta.DownloadRevision) || meta.Size < 0 || !meta.ReadOnly {
		return errors.New("文件信息无效")
	}
	offset, cursor := int64(0), ""
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		m := fileRequest(a, "workspace_file_download", meta.Path)
		m["expected_revision"], m["cursor"] = meta.DownloadRevision, cursor
		chunkCtx, cancel := context.WithTimeout(ctx, time.Minute)
		reply, err := s.Request(chunkCtx, m)
		cancel()
		if err != nil {
			return err
		}
		var part struct {
			FileMetadata
			Data        string `json:"data"`
			Offset      int64  `json:"offset"`
			NextCursor  string `json:"next_cursor"`
			ChunkSHA256 string `json:"chunk_sha256"`
		}
		bad := errors.New("文件在下载时发生变化，请重新下载")
		if decodeFileMessage(reply, &part) != nil || part.Path != meta.Path || part.Size != meta.Size || part.Offset != offset || part.DownloadRevision != meta.DownloadRevision || part.Kind != meta.Kind || !part.ReadOnly || part.MetadataOnly || len(part.Data) > 720*1024 {
			return bad
		}
		b, err := base64.StdEncoding.DecodeString(part.Data)
		if err != nil || len(b) > 512*1024 || int64(len(b)) > meta.Size-offset {
			return bad
		}
		digest := sha256.Sum256(b)
		if hex.EncodeToString(digest[:]) != part.ChunkSHA256 {
			return bad
		}
		next := offset + int64(len(b))
		if (part.NextCursor == "" && next != meta.Size) || (part.NextCursor != "" && (len(b) == 0 || next >= meta.Size || part.NextCursor != strconv.FormatInt(next, 10))) {
			return bad
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if err = writeAll(out, b); err != nil {
			return err
		}
		offset, cursor = next, part.NextCursor
		if progress != nil {
			progress(offset)
		}
		if cursor == "" {
			return nil
		}
	}
}
func ReadDocument(ctx context.Context, s Requester, a Agent, p string) (Document, error) {
	doc := Document{Path: p}
	if !validFilePath(p) {
		return doc, errors.New("文件路径无效")
	}
	cursor, revision := "", ""
	total := int64(-1)
	for {
		if err := ctx.Err(); err != nil {
			return doc, err
		}
		m := fileRequest(a, "workspace_file_read", p)
		m["cursor"], m["expected_revision"] = cursor, revision
		reply, err := s.Request(ctx, m)
		if err != nil {
			return doc, err
		}
		var part struct {
			Path       string `json:"path"`
			Kind       string `json:"kind"`
			MediaType  string `json:"media_type"`
			Data       string `json:"data"`
			Size       int64  `json:"size"`
			Offset     int64  `json:"offset"`
			ReadOnly   bool   `json:"read_only"`
			SHA256     string `json:"sha256"`
			NextCursor string `json:"next_cursor"`
		}
		bad := errors.New("文件在读取时发生变化，请重新打开")
		if decodeFileMessage(reply, &part) != nil || part.Path != p || part.Size < 0 || part.Size > 12*1024*1024 || part.Offset != int64(len(doc.Data)) || !part.ReadOnly || !validDigest(part.SHA256) || len(part.Data) > 720*1024 {
			return doc, bad
		}
		if total == -1 {
			total, revision, doc.Kind, doc.MediaType = part.Size, part.SHA256, part.Kind, part.MediaType
		}
		if total != part.Size || revision != part.SHA256 || doc.Kind != part.Kind || doc.MediaType != part.MediaType {
			return doc, bad
		}
		b, err := base64.StdEncoding.DecodeString(part.Data)
		if err != nil || len(b) > 512*1024 || int64(len(doc.Data)+len(b)) > total {
			return doc, bad
		}
		doc.Data = append(doc.Data, b...)
		cursor = part.NextCursor
		if cursor == "" {
			digest := sha256.Sum256(doc.Data)
			if int64(len(doc.Data)) != total || hex.EncodeToString(digest[:]) != revision {
				return doc, bad
			}
			return doc, nil
		}
		if len(b) == 0 || cursor != strconv.Itoa(len(doc.Data)) || int64(len(doc.Data)) >= total {
			return doc, bad
		}
	}
}
