package workspace

import (
	"container/heap"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const (
	maxDirectoryPage = 200
	maxDirectoryScan = 100000
)

type DirectoryPage struct {
	Root       string      `json:"root"`
	Path       string      `json:"path"`
	Entries    []TreeEntry `json:"entries"`
	NextCursor string      `json:"next_cursor"`
	Revision   string      `json:"revision"`
	Total      int         `json:"total"`
	Skipped    int         `json:"skipped"`
}

type directoryCursor struct {
	Revision string `json:"revision"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
}

func directoryLess(a, b TreeEntry) bool {
	if a.Kind != b.Kind {
		return a.Kind == "directory"
	}
	if left, right := strings.ToLower(a.Name), strings.ToLower(b.Name); left != right {
		return left < right
	}
	return a.Name < b.Name
}

// A max-heap keeps only the next page, not every file in a large directory.
type directoryHeap []TreeEntry

func (h directoryHeap) Len() int           { return len(h) }
func (h directoryHeap) Less(i, j int) bool { return directoryLess(h[j], h[i]) }
func (h directoryHeap) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }
func (h *directoryHeap) Push(x any)        { *h = append(*h, x.(TreeEntry)) }
func (h *directoryHeap) Pop() any {
	last := (*h)[len(*h)-1]
	*h = (*h)[:len(*h)-1]
	return last
}

// ListDirectory reads one level only; a large child cannot hide its siblings.
// Cursors are scoped to this canonical root, directory and directory revision.
func ListDirectory(ctx context.Context, workspace, path, cursor string, limit int) (DirectoryPage, error) {
	if limit == 0 {
		limit = maxDirectoryPage
	}
	if limit < 1 || limit > maxDirectoryPage || len(cursor) > 4096 || len(path) > 2048 {
		return DirectoryPage{}, publicError("Invalid directory page request")
	}
	root, err := openWorkspace(workspace)
	if err != nil {
		return DirectoryPage{}, err
	}
	defer root.Close()
	if path != "" {
		path, err = safePath(path)
		if err != nil {
			return DirectoryPage{}, err
		}
	}
	openPath := path
	if openPath == "" {
		openPath = "."
	}
	// No symlink directory navigation, including intermediate components.
	prefix := ""
	for _, part := range strings.Split(openPath, "/") {
		prefix = filepath.Join(prefix, part)
		info, err := root.root.Lstat(prefix)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return DirectoryPage{}, publicError("Workspace directory is unavailable or is a symbolic link")
		}
	}
	dir, err := root.root.Open(openPath)
	if err != nil {
		return DirectoryPage{}, publicError("Workspace directory could not be opened")
	}
	defer dir.Close()
	before, err := dir.Stat()
	if err != nil || !before.IsDir() {
		return DirectoryPage{}, publicError("Workspace directory is unavailable")
	}
	revision := fmt.Sprintf("%x", sha256.Sum256(fmt.Appendf(nil, "%s\x00%s\x00%d\x00%d", root.path, path, before.ModTime().UnixNano(), before.Size())))
	var afterEntry TreeEntry
	if cursor != "" {
		raw, err := base64.RawURLEncoding.DecodeString(cursor)
		var value directoryCursor
		if err != nil || json.Unmarshal(raw, &value) != nil || value.Revision != revision || value.Name == "" || strings.ContainsAny(value.Name, "/\\\x00") || (value.Kind != "file" && value.Kind != "directory") {
			return DirectoryPage{}, publicError("Directory changed or cursor expired; refresh this directory")
		}
		afterEntry = TreeEntry{Name: value.Name, Kind: value.Kind}
	}
	page := DirectoryPage{Root: root.path, Path: path, Entries: []TreeEntry{}, Revision: revision}
	next := &directoryHeap{}
	scanned := 0
	for {
		if err := ctx.Err(); err != nil {
			return DirectoryPage{}, publicError("Directory read cancelled; please retry")
		}
		rows, readErr := dir.ReadDir(128)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return DirectoryPage{}, publicError("Workspace directory could not be read")
		}
		for _, entry := range rows {
			scanned++
			if scanned > maxDirectoryScan {
				return DirectoryPage{}, publicError("This directory exceeds the 100,000-entry scan limit")
			}
			if entry.Type()&os.ModeSymlink != 0 {
				page.Skipped++
				continue
			}
			info, err := entry.Info()
			if err != nil || (!info.IsDir() && !info.Mode().IsRegular()) {
				page.Skipped++
				continue
			}
			kind := "file"
			if info.IsDir() {
				kind = "directory"
			}
			item := TreeEntry{Path: filepath.ToSlash(filepath.Join(path, entry.Name())), Name: entry.Name(), Kind: kind, Size: info.Size()}
			page.Total++
			if cursor != "" && !directoryLess(afterEntry, item) {
				continue
			}
			if next.Len() < limit+1 {
				heap.Push(next, item)
			} else if directoryLess(item, (*next)[0]) {
				(*next)[0] = item
				heap.Fix(next, 0)
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	after, err := root.root.Lstat(openPath)
	if ctx.Err() != nil || err != nil || !os.SameFile(before, after) || !after.ModTime().Equal(before.ModTime()) || after.Size() != before.Size() {
		return DirectoryPage{}, publicError("Directory changed while reading; refresh this directory")
	}
	sort.Slice(*next, func(i, j int) bool { return directoryLess((*next)[i], (*next)[j]) })
	bytes := 0
	for _, item := range *next {
		encoded, _ := json.Marshal(item)
		if len(page.Entries) >= limit || bytes+len(encoded) > 192*1024 {
			break
		}
		page.Entries = append(page.Entries, item)
		bytes += len(encoded)
	}
	if len(page.Entries) < len(*next) {
		last := page.Entries[len(page.Entries)-1]
		raw, _ := json.Marshal(directoryCursor{Revision: revision, Name: last.Name, Kind: last.Kind})
		page.NextCursor = base64.RawURLEncoding.EncodeToString(raw)
	}
	return page, nil
}
