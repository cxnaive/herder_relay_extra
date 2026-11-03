package workspace

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestDocumentReadPDFMarkdownAndOriginalImage(t *testing.T) {
	root := t.TempDir()
	for name, content := range map[string][]byte{"paper.pdf": []byte("%PDF-1.7\nfixture"), "README.md": []byte("# 中文标题\n\n[paper](paper.pdf)"), "plot.png": append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 64)...)} {
		if err := os.WriteFile(filepath.Join(root, name), content, 0600); err != nil {
			t.Fatal(err)
		}
		d, err := ReadDocumentFile(root, name)
		if err != nil {
			t.Fatal(err)
		}
		expected := map[string]string{"paper.pdf": "pdf", "README.md": "text", "plot.png": "image"}[name]
		raw, err := base64.StdEncoding.DecodeString(d.Data)
		if err != nil || !bytes.Equal(raw, content) || d.Kind != expected || !d.ReadOnly || d.Path != name {
			t.Fatalf("incorrect payload: %+v %v", d, err)
		}
		encoded, _ := json.Marshal(d)
		if bytes.Contains(encoded, []byte(`"revision"`)) {
			t.Fatal("read-only preview must not advertise a write revision")
		}
	}
}

func TestDocumentRejectsTraversalSymlinksDirectoriesAndLargeFiles(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	secret := filepath.Join(outside, "private.pdf")
	if err := os.WriteFile(secret, []byte("%PDF-secret"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secret, filepath.Join(root, "link.pdf")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "external")); err != nil {
		t.Fatal(err)
	}
	f, err := os.Create(filepath.Join(root, "large.pdf"))
	if err != nil {
		t.Fatal(err)
	}
	if err = f.Truncate(MaxDocumentBytes + 1); err != nil {
		t.Fatal(err)
	}
	f.Close()
	for _, path := range []string{"../private.pdf", secret, "link.pdf", "external/private.pdf", "large.pdf", ".", "", "missing.pdf"} {
		if _, err := ReadDocumentFile(root, path); err == nil {
			t.Errorf("unsafe read accepted: %s", path)
		}
	}
}

func TestDocumentChunksFitTransportAndRejectMixedRevisions(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "paper.pdf")
	content := append([]byte("%PDF-1.7\n"), bytes.Repeat([]byte("a"), 3*1024*1024)...)
	if err := os.WriteFile(path, content, 0600); err != nil {
		t.Fatal(err)
	}
	first, err := ReadDocumentFile(root, "paper.pdf")
	if err != nil {
		t.Fatal(err)
	}
	if first.NextCursor == "" || first.Offset != 0 || len(first.SHA256) != 64 {
		t.Fatal("missing chunk identity")
	}
	encoded, _ := json.Marshal(first)
	if len(encoded) >= 1024*1024 {
		t.Fatal("chunk exceeds transport budget")
	}
	result, _ := base64.StdEncoding.DecodeString(first.Data)
	cursor := first.NextCursor
	for cursor != "" {
		part, err := ReadDocumentChunk(root, "paper.pdf", cursor, first.SHA256)
		if err != nil {
			t.Fatal(err)
		}
		if part.Offset != len(result) {
			t.Fatal("out of order offset")
		}
		raw, _ := base64.StdEncoding.DecodeString(part.Data)
		result = append(result, raw...)
		cursor = part.NextCursor
	}
	if !bytes.Equal(result, content) {
		t.Fatal("assembled file differs")
	}
	content[len(content)-1] = 'b'
	if err := os.WriteFile(path, content, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadDocumentChunk(root, "paper.pdf", first.NextCursor, first.SHA256); err == nil {
		t.Fatal("mixed file revisions accepted")
	}
	for _, cursor := range []string{"-1", "abc", "99999999999999999999", "01"} {
		if _, err := ReadDocumentChunk(root, "paper.pdf", cursor, first.SHA256); err == nil {
			t.Fatalf("invalid cursor accepted: %s", cursor)
		}
	}
}
