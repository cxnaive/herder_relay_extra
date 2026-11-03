package workspace

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"
)

const MaxDocumentBytes = 12 * 1024 * 1024
const DocumentChunkBytes = 512 * 1024

type DocumentFile struct {
	Path       string `json:"path"`
	MediaType  string `json:"media_type"`
	Kind       string `json:"kind"`
	Data       string `json:"data"`
	Size       int    `json:"size"`
	ReadOnly   bool   `json:"read_only"`
	Offset     int    `json:"offset"`
	NextCursor string `json:"next_cursor"`
	SHA256     string `json:"sha256"`
}

// ReadDocumentFile shares the inspector's descriptor-rooted containment and
// regular-file checks. It exposes no public URL and no write revision/token.
func ReadDocumentFile(workspace, path string) (DocumentFile, error) {
	return ReadDocumentChunk(workspace, path, "", "")
}

func ReadDocumentChunk(workspace, path, cursor, expected string) (DocumentFile, error) {
	offset := 0
	if cursor != "" {
		var err error
		offset, err = strconv.Atoi(cursor)
		if err != nil || offset <= 0 || offset > MaxDocumentBytes || cursor != strconv.Itoa(offset) || len(expected) != 64 {
			return DocumentFile{}, publicError("Invalid document cursor or revision")
		}
	}
	root, err := openWorkspace(workspace)
	if err != nil {
		return DocumentFile{}, err
	}
	defer root.Close()
	path, err = safePath(path)
	if err != nil {
		return DocumentFile{}, err
	}
	file, info, err := root.regularFile(path)
	if err != nil {
		return DocumentFile{}, err
	}
	defer file.Close()
	if info.Size() > MaxDocumentBytes {
		return DocumentFile{}, publicError("File exceeds the 12 MiB document preview limit")
	}
	data, err := io.ReadAll(io.LimitReader(file, MaxDocumentBytes+1))
	if err != nil || len(data) > MaxDocumentBytes {
		return DocumentFile{}, publicError("File could not be read within the preview limit")
	}
	after, err := file.Stat()
	if err != nil || after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return DocumentFile{}, publicError("File changed while being read; please retry")
	}
	media := strings.Split(http.DetectContentType(data), ";")[0]
	kind := "binary"
	switch {
	case bytes.HasPrefix(data, []byte("%PDF-")):
		media = "application/pdf"
		kind = "pdf"
	case strings.HasPrefix(media, "image/") && media != "image/svg+xml":
		kind = "image"
	case utf8.Valid(data) && !bytes.ContainsRune(data, 0) && len(data) <= maxTextBytes:
		kind = "text"
		media = "text/plain"
	}
	digest := fmt.Sprintf("%x", sha256.Sum256(data))
	if (expected != "" && expected != digest) || offset > len(data) {
		return DocumentFile{}, publicError("Document changed during download; reopen it")
	}
	end := min(offset+DocumentChunkBytes, len(data))
	next := ""
	if end < len(data) {
		next = strconv.Itoa(end)
	}
	return DocumentFile{Path: path, MediaType: media, Kind: kind, Data: base64.StdEncoding.EncodeToString(data[offset:end]), Size: len(data), ReadOnly: true, Offset: offset, NextCursor: next, SHA256: digest}, nil
}
