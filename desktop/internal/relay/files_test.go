package relay

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"testing"
)

type fileRequester func(context.Context, Message) (Message, error)

func (f fileRequester) Request(ctx context.Context, m Message) (Message, error) { return f(ctx, m) }

func TestDirectoryPagesRejectTraversalAndChangedRevision(t *testing.T) {
	agent := Agent{Target: Target{PaneID: "pane", TerminalID: "terminal", Generation: 8}}
	base := Message{"root": "/work", "path": "reports", "revision": "first", "total": 2, "next_cursor": "next", "entries": []FileEntry{{Path: "reports/a.md", Name: "a.md", Kind: "file"}}}
	s := fileRequester(func(_ context.Context, m Message) (Message, error) {
		if m["target"] != agent.Target || Text(m, "file_scope") != "workspace" {
			t.Fatal("lost exact workspace scope")
		}
		return base, nil
	})
	first, err := ListDirectory(t.Context(), s, agent, "reports", nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, bad := range []FileEntry{{Path: "../secret", Name: "secret", Kind: "file"}, {Path: "other/a.md", Name: "a.md", Kind: "file"}, {Path: "reports/a.md", Name: "a.md", Kind: "file"}} {
		base["entries"], base["next_cursor"] = []FileEntry{bad}, ""
		if _, err = ListDirectory(t.Context(), s, agent, "reports", &first); err == nil {
			t.Fatal("accepted wrong/duplicate entry", bad)
		}
	}
	base["entries"] = []FileEntry{{Path: "reports/b.md", Name: "b.md", Kind: "file"}}
	base["revision"] = "replacement"
	if _, err = ListDirectory(t.Context(), s, agent, "reports", &first); err == nil {
		t.Fatal("accepted replacement directory")
	}
	base["revision"] = "first"
	page, err := ListDirectory(t.Context(), s, agent, "reports", &first)
	if err != nil || len(page.Entries) != 2 {
		t.Fatal(page, err)
	}
}

func TestOriginalDownloadBeyondPreviewLimitAndCorruption(t *testing.T) {
	data := bytes.Repeat([]byte("世界\n"), 2*1024*1024)
	meta := FileMetadata{Path: "large.txt", Kind: "text", Size: int64(len(data)), ReadOnly: true, DownloadRevision: strings.Repeat("a", 64)}
	for _, variant := range []string{"valid", "hash", "revision", "offset", "cursor", "path", "cancelled"} {
		t.Run(variant, func(t *testing.T) {
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			calls := 0
			s := fileRequester(func(_ context.Context, m Message) (Message, error) {
				calls++
				if Text(m, "type") != "workspace_file_download" || Text(m, "expected_revision") != meta.DownloadRevision || Text(m, "file_scope") != "workspace" {
					t.Fatal("invalid download request", m)
				}
				offset, _ := strconv.Atoi(Text(m, "cursor"))
				end := min(offset+512*1024, len(data))
				b := data[offset:end]
				cursor := ""
				if end < len(data) {
					cursor = strconv.Itoa(end)
				}
				part := Message{"path": meta.Path, "kind": meta.Kind, "size": meta.Size, "read_only": true, "download_revision": meta.DownloadRevision, "offset": offset, "data": base64.StdEncoding.EncodeToString(b), "chunk_sha256": fmt.Sprintf("%x", sha256.Sum256(b)), "next_cursor": cursor}
				if calls == 2 {
					switch variant {
					case "hash":
						part["chunk_sha256"] = strings.Repeat("0", 64)
					case "revision":
						part["download_revision"] = strings.Repeat("b", 64)
					case "offset":
						part["offset"] = 0
					case "cursor":
						part["next_cursor"] = "1"
					case "path":
						part["path"] = "other.txt"
					case "cancelled":
						cancel()
					}
				}
				return part, nil
			})
			var out bytes.Buffer
			err := Download(ctx, s, Agent{}, meta, &out, nil)
			if variant == "valid" {
				if err != nil || !bytes.Equal(out.Bytes(), data) {
					t.Fatal("download mismatch", err)
				}
			} else {
				if err == nil || out.Len() > 512*1024 {
					t.Fatal("invalid chunk was published", err, out.Len())
				}
			}
		})
	}
}

func TestDocumentPreviewVerifiesCompleteDigest(t *testing.T) {
	data := bytes.Repeat([]byte("中文预览\n"), 70000)
	for _, corrupt := range []bool{false, true} {
		s := fileRequester(func(_ context.Context, m Message) (Message, error) {
			offset, _ := strconv.Atoi(Text(m, "cursor"))
			end := min(offset+512*1024, len(data))
			b := append([]byte{}, data[offset:end]...)
			if corrupt && offset > 0 {
				b[0] ^= 1
			}
			cursor := ""
			if end < len(data) {
				cursor = strconv.Itoa(end)
			}
			return Message{"path": "notes.md", "kind": "text", "media_type": "text/plain", "read_only": true, "size": len(data), "offset": offset, "data": base64.StdEncoding.EncodeToString(b), "sha256": fmt.Sprintf("%x", sha256.Sum256(data)), "next_cursor": cursor}, nil
		})
		doc, err := ReadDocument(t.Context(), s, Agent{}, "notes.md")
		if corrupt {
			if err == nil {
				t.Fatal("corrupt preview accepted")
			}
		} else if err != nil || !bytes.Equal(doc.Data, data) {
			t.Fatal(err)
		}
	}
}
