package workspace

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestLargeFileMetadataAndBoundedDownload(t *testing.T) {
	root := t.TempDir()
	content := append([]byte("%PDF-1.7\n"), bytes.Repeat([]byte("abc"), MaxDocumentBytes/3+7)...)
	if err := os.WriteFile(filepath.Join(root, "large.pdf"), content, 0600); err != nil {
		t.Fatal(err)
	}
	meta, err := ReadDownloadChunk(root, "large.pdf", "", "", true)
	if err != nil || meta.PreviewAvailable || meta.Size != int64(len(content)) || meta.Kind != "pdf" || meta.Data != "" || meta.PreviewReason == "" {
		t.Fatalf("metadata: %+v %v", meta, err)
	}
	var result bytes.Buffer
	cursor := ""
	for {
		part, err := ReadDownloadChunk(root, "large.pdf", cursor, meta.DownloadRevision, false)
		if err != nil {
			t.Fatal(err)
		}
		raw, err := base64.StdEncoding.DecodeString(part.Data)
		if err != nil || len(raw) > DocumentChunkBytes || part.Offset != int64(result.Len()) || part.ChunkSHA256 != fmt.Sprintf("%x", sha256.Sum256(raw)) {
			t.Fatal("invalid chunk")
		}
		encoded, _ := json.Marshal(part)
		if len(encoded) > 720*1024 {
			t.Fatal("frame too large")
		}
		result.Write(raw)
		cursor = part.NextCursor
		if cursor == "" {
			break
		}
	}
	if !bytes.Equal(result.Bytes(), content) {
		t.Fatal("download differs")
	}
}
func TestDownloadRejectsChangedFileUnsafePathsAndCursors(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "file.txt")
	if err := os.WriteFile(path, []byte("original"), 0600); err != nil {
		t.Fatal(err)
	}
	meta, err := ReadDownloadChunk(root, "file.txt", "", "", true)
	if err != nil {
		t.Fatal(err)
	}
	for _, cursor := range []string{"01", "-1", "99999999999999999999", "9"} {
		if _, err := ReadDownloadChunk(root, "file.txt", cursor, meta.DownloadRevision, false); err == nil {
			t.Fatal("bad cursor accepted", cursor)
		}
	}
	for _, path := range []string{"../file.txt", path, ".", "missing"} {
		if _, err := ReadDownloadChunk(root, path, "", "", true); err == nil {
			t.Fatal("unsafe path accepted", path)
		}
	}
	outside := filepath.Join(t.TempDir(), "secret")
	os.WriteFile(outside, []byte("secret"), 0600)
	os.Symlink(outside, filepath.Join(root, "link"))
	if _, err := ReadDownloadChunk(root, "link", "", "", true); err == nil {
		t.Fatal("outside symlink accepted")
	}
	old, _ := os.Stat(path)
	os.WriteFile(path, []byte("modified"), 0600)
	os.Chtimes(path, old.ModTime(), old.ModTime())
	if _, err := ReadDownloadChunk(root, "file.txt", "", meta.DownloadRevision, false); err == nil {
		t.Fatal("same-size modification accepted")
	}
	if _, err := ReadDownloadChunk(root, "file.txt", "", "", false); err == nil {
		t.Fatal("missing revision accepted")
	}
}
func TestDownloadMetadataPreviewPolicyAndEmptyFile(t *testing.T) {
	root := t.TempDir()
	for _, tc := range []struct {
		name    string
		data    []byte
		preview bool
	}{
		{"small.md", []byte("# hi"), true}, {"unicode.md", bytes.Repeat([]byte("中文"), 200), true}, {"empty", nil, true}, {"large.md", bytes.Repeat([]byte("a"), maxTextBytes+1), false}, {"unknown.bin", []byte{0, 1, 2}, false},
	} {
		os.WriteFile(filepath.Join(root, tc.name), tc.data, 0600)
		meta, err := ReadDownloadChunk(root, tc.name, "", "", true)
		if err != nil || meta.PreviewAvailable != tc.preview {
			t.Fatalf("%s: %+v %v", tc.name, meta, err)
		}
		if len(tc.data) == 0 {
			chunk, err := ReadDownloadChunk(root, tc.name, "", meta.DownloadRevision, false)
			if err != nil || chunk.NextCursor != "" || chunk.Size != 0 {
				t.Fatal("empty download failed", err)
			}
		}
	}
}
