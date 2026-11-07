package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"image"
	"image/png"
	"os"
	"testing"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo/ui"
)

func TestFileReferenceUsesGrantedRootAndDocumentBase(t *testing.T) {
	for _, row := range []struct {
		base, raw, want string
		granted         bool
	}{
		{"", "/var/reports/a.md", "var/reports/a.md", true},
		{"", "notes.md", "home/user/project/notes.md", true},
		{"var/reports/a.md", "../images/chart.png", "var/images/chart.png", true},
		{"", "file:///etc/config.json", "etc/config.json", true},
		{"", "/", "", true},
		{"reports/a.md", "../notes.md", "notes.md", false},
	} {
		got, _, err := fileReference("/home/user/project", row.base, row.raw, row.granted)
		if err != nil || got != row.want {
			t.Fatal(row, got, err)
		}
	}
	for _, raw := range []string{"/etc/config.json", "../private", "%2e%2e/private", "file:///etc/config"} {
		if _, _, err := fileReference("/home/user/project", "", raw, false); err == nil {
			t.Fatal("workspace escaped", raw)
		}
	}
	for _, raw := range []string{"file://host/etc/config", "//host/share", "bad%5cpath", "/etc/%00secret", "https://example.com/a"} {
		if _, _, err := fileReference("/home/user/project", "", raw, true); err == nil {
			t.Fatal("unsafe remote path accepted", raw)
		}
	}
}
func TestPermissionChangeClearsAllPreviewsAndCancelsTransfers(t *testing.T) {
	a, m, chat := managementFixture(t)
	m.capabilities = map[string]bool{"filesystem_read_opt_in_v1": true}
	a.applyFileAccess(m, true, false)
	reader := a.fileReader(m)
	readCtx, readCancel := context.WithCancel(reader.Context)
	downloadCtx, downloadCancel := context.WithCancel(reader.Context)
	f := filesFor(chat)
	f.fileEpoch = m.fileEpoch
	f.filesystem = true
	f.cancel = readCancel
	f.cancelDownload = downloadCancel
	f.previewPath = "etc/private.md"
	f.document = &relay.Document{Data: []byte("private")}
	chat.images = map[string]*conversationImage{"x": {data: []byte("private")}}
	a.imagePreview = chat.images["x"]
	a.applyFileAccess(m, false, false)
	if readCtx.Err() == nil || downloadCtx.Err() == nil || reader.Context.Err() == nil || chat.files != nil || chat.images != nil || chat.md != nil || a.imagePreview != nil {
		t.Fatal("revoked readers or preview survived")
	}
	if a.fileRequestCurrent(m, chat, f, m.epoch, f.operation) {
		t.Fatal("late result still current")
	}
	if a.fileReader(m).Filesystem {
		t.Fatal("new reads retained full scope")
	}
	m.config.Auth.Role = "reader"
	a.applyFileAccess(m, true, false)
	if m.filesystemRead {
		t.Fatal("reader granted full access")
	}
	m.config.Auth.Role = "controller"
	m.capabilities = nil
	a.applyFileAccess(m, true, false)
	if m.filesystemRead {
		t.Fatal("unsupported grant accepted")
	}
}

type scopedImageRequester func(context.Context, relay.Message) (relay.Message, error)

func (f scopedImageRequester) Request(c context.Context, m relay.Message) (relay.Message, error) {
	return f(c, m)
}
func TestOutsideWorkspaceMarkdownImagesUseSameReadScope(t *testing.T) {
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 4, 4))); err != nil {
		t.Fatal(err)
	}
	data := encoded.Bytes()
	called := false
	r := relay.FileReader{Context: t.Context(), Filesystem: true, Requester: scopedImageRequester(func(_ context.Context, m relay.Message) (relay.Message, error) {
		called = true
		if m["file_scope"] != "filesystem" || m["path"] != "var/images/plot.png" {
			t.Fatal(m)
		}
		return relay.Message{"path": "var/images/plot.png", "kind": "image", "media_type": "image/png", "size": len(data), "offset": 0, "read_only": true, "sha256": fmt.Sprintf("%x", sha256.Sum256(data)), "data": base64.StdEncoding.EncodeToString(data)}, nil
	})}
	_, bitmap, err := readImage(t.Context(), r, relay.Agent{Cwd: "/work"}, "var/reports/report.md", "../images/plot.png", true)
	if err != nil || bitmap == nil || !called {
		t.Fatal(err)
	}
}
func TestPermissionDialogDoesNotGrantOnOpenOrDismiss(t *testing.T) {
	a, m, _ := managementFixture(t)
	m.capabilities = map[string]bool{"filesystem_read_opt_in_v1": true}
	a.fileAccessDialog = &fileAccessDialog{machine: m, epoch: m.epoch, known: true}
	tt := ui.NewTester(a.view, 1280, 840)
	if output := os.Getenv("HERDR_ACCESS_SCREENSHOT"); output != "" {
		f, err := os.Create(output)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if err = png.Encode(f, tt.Image()); err != nil {
			t.Fatal(err)
		}
	}
	if !tt.HasText("允许只读访问") || m.filesystemRead {
		t.Fatal("grant was implicit")
	}
	if err := tt.Click("关闭权限窗口"); err != nil {
		t.Fatal(err)
	}
	if m.filesystemRead || a.fileAccessDialog != nil {
		t.Fatal("dismissal changed permission")
	}
	m.config.Auth.Role = "reader"
	d := &fileAccessDialog{machine: m, epoch: m.epoch, known: true}
	a.setFileAccess(d)
	if d.busy || m.filesystemRead {
		t.Fatal("reader dispatched permission mutation")
	}
}
