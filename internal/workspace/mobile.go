package workspace

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unicode/utf8"
)

const MaxMobileFileBytes = 12 * 1024 * 1024

var mobileWriteMu sync.Mutex

type MobileFile struct {
	Path      string `json:"path"`
	MediaType string `json:"media_type"`
	Kind      string `json:"kind"`
	Data      string `json:"data"`
	Revision  string `json:"revision"`
	Size      int    `json:"size"`
}

func revision(data []byte) string { return fmt.Sprintf("%x", sha256.Sum256(data)) }

func mobilePayload(path string, data []byte) MobileFile {
	media := strings.Split(mime.TypeByExtension(strings.ToLower(filepath.Ext(path))), ";")[0]
	if media == "" {
		media = strings.Split(http.DetectContentType(data), ";")[0]
	}
	kind := "binary"
	if bytes.HasPrefix(data, []byte("%PDF-")) {
		kind = "pdf"
		media = "application/pdf"
	} else if strings.HasPrefix(media, "image/") && media != "image/svg+xml" {
		kind = "image"
	} else if utf8.Valid(data) && !bytes.ContainsRune(data, 0) && len(data) <= maxTextBytes {
		kind = "text"
	}
	return MobileFile{Path: path, MediaType: media, Kind: kind, Data: base64.StdEncoding.EncodeToString(data), Revision: revision(data), Size: len(data)}
}

func ReadMobileFile(workspace, path string) (MobileFile, error) {
	root, err := openWorkspace(workspace)
	if err != nil {
		return MobileFile{}, err
	}
	defer root.Close()
	path, err = safePath(path)
	if err != nil {
		return MobileFile{}, err
	}
	file, info, err := root.regularFile(path)
	if err != nil {
		return MobileFile{}, err
	}
	defer file.Close()
	if info.Size() > MaxMobileFileBytes {
		return MobileFile{}, publicError("File exceeds the 12 MiB mobile download limit")
	}
	data, err := io.ReadAll(io.LimitReader(file, MaxMobileFileBytes+1))
	if err != nil || len(data) > MaxMobileFileBytes {
		return MobileFile{}, publicError("File could not be read within the download limit")
	}
	return mobilePayload(path, data), nil
}

// Writes replace an existing UTF-8 file; callers supply the hash they read.
// The parent descriptor keeps a directory rename from redirecting the commit.
func WriteMobileFile(workspace, path, text, expected string, beforeCommit func() error) (MobileFile, error) {
	if len(text) > maxTextBytes || !utf8.ValidString(text) || strings.ContainsRune(text, 0) {
		return MobileFile{}, publicError("Only UTF-8 text up to 1 MiB can be edited")
	}
	if len(expected) != 64 {
		return MobileFile{}, publicError("A file revision is required")
	}
	mobileWriteMu.Lock()
	defer mobileWriteMu.Unlock()
	root, err := openWorkspace(workspace)
	if err != nil {
		return MobileFile{}, err
	}
	defer root.Close()
	path, err = safePath(path)
	if err != nil {
		return MobileFile{}, err
	}
	parts := strings.Split(path, "/")
	for i, part := range parts {
		if strings.EqualFold(part, ".git") {
			return MobileFile{}, publicError("Git metadata cannot be edited")
		}
		info, statErr := root.root.Lstat(strings.Join(parts[:i+1], "/"))
		if statErr != nil || info.Mode()&os.ModeSymlink != 0 {
			return MobileFile{}, publicError("File is missing or contains a symbolic link")
		}
	}
	parent, err := root.root.OpenRoot(filepath.Dir(path))
	if err != nil {
		return MobileFile{}, publicError("File directory could not be opened")
	}
	defer parent.Close()
	name := filepath.Base(path)
	readCurrent := func() ([]byte, os.FileInfo, error) {
		ctx := &rootContext{root: parent}
		file, info, readErr := ctx.regularFile(name)
		if readErr != nil {
			return nil, nil, readErr
		}
		defer file.Close()
		data, readErr := io.ReadAll(io.LimitReader(file, maxTextBytes+1))
		if readErr != nil || len(data) > maxTextBytes || !utf8.Valid(data) || bytes.ContainsRune(data, 0) {
			return nil, nil, publicError("Only UTF-8 text up to 1 MiB can be edited")
		}
		return data, info, nil
	}
	old, info, err := readCurrent()
	if err != nil {
		return MobileFile{}, err
	}
	if revision(old) != expected {
		return MobileFile{}, publicError("File changed on the computer. Reload before saving; your draft has not been written.")
	}
	tmpName := ".herdr-edit-" + rand.Text()
	tmp, err := parent.OpenFile(tmpName, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return MobileFile{}, publicError("Temporary file could not be created")
	}
	defer parent.Remove(tmpName)
	_, writeErr := tmp.WriteString(text)
	if writeErr == nil {
		writeErr = tmp.Chmod(info.Mode().Perm())
	}
	if writeErr == nil {
		writeErr = tmp.Sync()
	}
	closeErr := tmp.Close()
	if writeErr != nil || closeErr != nil {
		return MobileFile{}, publicError("File could not be saved")
	}
	current, currentInfo, err := readCurrent()
	if err != nil || !os.SameFile(info, currentInfo) || revision(current) != expected {
		return MobileFile{}, publicError("File changed on the computer. Reload before saving; your draft has not been written.")
	}
	if beforeCommit != nil {
		if err := beforeCommit(); err != nil {
			return MobileFile{}, err
		}
	}
	if err := parent.Rename(tmpName, name); err != nil {
		return MobileFile{}, publicError("File could not be replaced")
	}
	return mobilePayload(path, []byte(text)), nil
}
