package workspace

import (
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestMobileFileRoundTripAndConflict(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "你好.md"), "# Hello\r\n世界\r\n")
	file, err := ReadMobileFile(root, "你好.md")
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := base64.StdEncoding.DecodeString(file.Data)
	if string(raw) != "# Hello\r\n世界\r\n" || file.Kind != "text" {
		t.Fatalf("incorrect content: %+v", file)
	}
	saved, err := WriteMobileFile(root, "你好.md", "# Updated\n", file.Revision, nil)
	if err != nil || saved.Revision == file.Revision {
		t.Fatalf("save: %+v, %v", saved, err)
	}
	if _, err := WriteMobileFile(root, "你好.md", "stale", file.Revision, nil); err == nil {
		t.Fatal("overwrote newer content")
	}
	raw, _ = os.ReadFile(filepath.Join(root, "你好.md"))
	if string(raw) != "# Updated\n" {
		t.Fatal("conflict changed file")
	}
}

func TestMobileFilesRejectTraversalLinksAndMetadata(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	writeTestFile(t, filepath.Join(outside, "secret.txt"), "secret")
	writeTestFile(t, filepath.Join(root, ".git", "config"), "secret")
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"../secret.txt", "/etc/passwd", "link/secret.txt"} {
		if _, err := ReadMobileFile(root, path); err == nil {
			t.Fatalf("read escaped: %s", path)
		}
		if _, err := WriteMobileFile(root, path, "overwrite", revision([]byte("secret")), nil); err == nil {
			t.Fatalf("write escaped: %s", path)
		}
	}
	if _, err := WriteMobileFile(root, ".git/config", "overwrite", revision([]byte("secret")), nil); err == nil {
		t.Fatal("edited git metadata")
	}
}

func TestMobileBinaryPDFAndLimits(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "sample.pdf"), "%PDF-1.7\n\x00payload")
	file, err := ReadMobileFile(root, "sample.pdf")
	if err != nil || file.Kind != "pdf" || file.MediaType != "application/pdf" {
		t.Fatalf("PDF: %+v %v", file, err)
	}
	if _, err := WriteMobileFile(root, "sample.pdf", "new", file.Revision, nil); err == nil {
		t.Fatal("edited binary")
	}
	writeTestFile(t, filepath.Join(root, "large.bin"), strings.Repeat("x", MaxMobileFileBytes+1))
	if _, err := ReadMobileFile(root, "large.bin"); err == nil {
		t.Fatal("accepted oversized download")
	}
	writeTestFile(t, filepath.Join(root, "small.txt"), "small")
	if _, err := WriteMobileFile(root, "small.txt", strings.Repeat("x", maxTextBytes+1), revision([]byte("small")), nil); err == nil {
		t.Fatal("accepted oversized edit")
	}
}

func TestMobileSaveRechecksAuthorizationAndCleansTemporaryFile(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "file.txt"), "old")
	_, err := WriteMobileFile(root, "file.txt", "new", revision([]byte("old")), func() error { return errors.New("revoked") })
	if err == nil {
		t.Fatal("saved after authorization revoked")
	}
	raw, _ := os.ReadFile(filepath.Join(root, "file.txt"))
	if string(raw) != "old" {
		t.Fatal("revoked save changed file")
	}
	entries, _ := os.ReadDir(root)
	if len(entries) != 1 {
		t.Fatal("temporary file leaked")
	}
}

func TestConcurrentMobileEditsOnlyOneCommits(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "file.txt"), "old")
	var group sync.WaitGroup
	errorsCh := make(chan error, 2)
	for _, text := range []string{"one", "two"} {
		group.Go(func() {
			_, err := WriteMobileFile(root, "file.txt", text, revision([]byte("old")), nil)
			errorsCh <- err
		})
	}
	group.Wait()
	close(errorsCh)
	success := 0
	for err := range errorsCh {
		if err == nil {
			success++
		}
	}
	if success != 1 {
		t.Fatalf("%d writes committed", success)
	}
}
