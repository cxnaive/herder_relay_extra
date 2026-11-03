package workspace

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"os"
	"reflect"
	"strconv"
	"strings"
	"unicode/utf8"
)

// DownloadFile is read-only. download_revision is never a file-write token.
// Metadata and chunks stay bounded even when the original file is very large.
type DownloadFile struct {
	Path             string `json:"path"`
	MediaType        string `json:"media_type"`
	Kind             string `json:"kind"`
	Size             int64  `json:"size"`
	ReadOnly         bool   `json:"read_only"`
	PreviewAvailable bool   `json:"preview_available"`
	PreviewReason    string `json:"preview_reason,omitempty"`
	DownloadRevision string `json:"download_revision"`
	MetadataOnly     bool   `json:"metadata_only"`
	Data             string `json:"data"`
	Offset           int64  `json:"offset"`
	NextCursor       string `json:"next_cursor"`
	ChunkSHA256      string `json:"chunk_sha256,omitempty"`
}

func downloadRevision(info os.FileInfo) string {
	// Include inode and change time where available, not access time (reads change it).
	identity := fmt.Sprintf("%d:%d:%d", info.Size(), info.ModTime().UnixNano(), info.Mode())
	sys := reflect.ValueOf(info.Sys())
	if sys.Kind() == reflect.Pointer && !sys.IsNil() {
		sys = sys.Elem()
	}
	if sys.Kind() == reflect.Struct {
		for _, key := range []string{"Dev", "Ino", "Ctim", "Ctimespec", "Ctime", "Ctimensec"} {
			if field := sys.FieldByName(key); field.IsValid() && field.CanInterface() {
				identity += fmt.Sprintf(":%s=%v", key, field.Interface())
			}
		}
	}
	return fmt.Sprintf("%x", sha256.Sum256([]byte(identity)))
}

func ReadDownloadChunk(workspace, path, cursor, expected string, metadata bool) (DownloadFile, error) {
	root, err := openWorkspace(workspace)
	if err != nil {
		return DownloadFile{}, err
	}
	defer root.Close()
	path, err = safePath(path)
	if err != nil {
		return DownloadFile{}, err
	}
	file, info, err := root.regularFile(path)
	if err != nil {
		return DownloadFile{}, err
	}
	defer file.Close()
	revision := downloadRevision(info)
	offset := int64(0)
	if metadata {
		if cursor != "" || expected != "" {
			return DownloadFile{}, publicError("Invalid file metadata request")
		}
	} else {
		if len(expected) != 64 || expected != revision {
			return DownloadFile{}, publicError("File changed; reopen it before downloading")
		}
		if cursor != "" {
			offset, err = strconv.ParseInt(cursor, 10, 64)
			if err != nil || offset <= 0 || cursor != strconv.FormatInt(offset, 10) {
				return DownloadFile{}, publicError("Invalid download cursor")
			}
		}
		if offset > info.Size() {
			return DownloadFile{}, publicError("Download cursor exceeds file size")
		}
	}
	sample := make([]byte, min(int64(512), info.Size()))
	if _, err := file.ReadAt(sample, 0); err != nil {
		return DownloadFile{}, publicError("File metadata could not be read")
	}
	media := strings.Split(http.DetectContentType(sample), ";")[0]
	kind := "binary"
	textSample := sample
	if info.Size() > int64(len(sample)) {
		// A UTF-8 code point can straddle the bounded sniff buffer.
		for trim := 0; trim < 3 && len(textSample) > 0 && !utf8.Valid(textSample); trim++ {
			textSample = textSample[:len(textSample)-1]
		}
	}
	switch {
	case bytes.HasPrefix(sample, []byte("%PDF-")):
		kind = "pdf"
		media = "application/pdf"
	case strings.HasPrefix(media, "image/") && media != "image/svg+xml":
		kind = "image"
	case utf8.Valid(textSample) && !bytes.ContainsRune(sample, 0):
		kind = "text"
		media = "text/plain"
	}
	preview := kind != "binary" && info.Size() <= MaxDocumentBytes && (kind != "text" || info.Size() <= maxTextBytes)
	reason := ""
	if !preview {
		reason = "此文件不支持内置预览；仍可下载或分享原文件"
		if info.Size() > MaxDocumentBytes {
			reason = "文件超过 12 MiB 预览限制；仍可下载或分享原文件"
		} else if kind == "text" && info.Size() > maxTextBytes {
			reason = "文本超过 1 MiB 预览限制；仍可下载或分享原文件"
		}
	}
	result := DownloadFile{Path: path, MediaType: media, Kind: kind, Size: info.Size(), ReadOnly: true, PreviewAvailable: preview, PreviewReason: reason, DownloadRevision: revision, MetadataOnly: metadata, Offset: offset}
	if !metadata {
		data := make([]byte, min(int64(DocumentChunkBytes), info.Size()-offset))
		n, err := file.ReadAt(data, offset)
		if (err != nil && err != io.EOF) || n != len(data) {
			return DownloadFile{}, publicError("File changed or could not be read; retry download")
		}
		result.Data = base64.StdEncoding.EncodeToString(data)
		result.ChunkSHA256 = fmt.Sprintf("%x", sha256.Sum256(data))
		if end := offset + int64(len(data)); end < info.Size() {
			result.NextCursor = strconv.FormatInt(end, 10)
		}
	}
	after, err := file.Stat()
	if err != nil || downloadRevision(after) != revision {
		return DownloadFile{}, publicError("File changed during download; reopen it")
	}
	return result, nil
}
