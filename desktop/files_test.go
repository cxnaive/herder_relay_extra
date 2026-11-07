package main

import (
	"context"
	"errors"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo/ui"
)

func TestWorkspaceBrowserNavigationPreviewAndIsolation(t *testing.T) {
	app := newApplication(true)
	defer app.cancel()
	app.loadDemo()
	tt := ui.NewTester(app.view, 1380, 840)
	click := func(name string) {
		t.Helper()
		if err := tt.Click(name); err != nil {
			t.Fatal(err)
		}
	}
	click("文件")
	if !tt.HasText("README.md") {
		t.Fatal("directory missing")
	}
	click("reports")
	if !tt.HasText("evaluation.md") {
		t.Fatal("nested directory missing")
	}
	click("evaluation.md")
	if !tt.HasText("下载") || !tt.HasText("评测结果与运行记录。") {
		t.Fatal("preview missing")
	}
	_, first := app.selected()
	click("结果复核")
	_, second := app.selected()
	if first.files == second.files || second.files.path != "" || second.files.previewPath != "" {
		t.Fatal("workspace state crossed Agents")
	}
	click("README.md")
	click("返回目录")
	click("src")
	click("上一级")
	if !tt.HasText("README.md") {
		t.Fatal("parent navigation failed")
	}
	if p := os.Getenv("HERDR_FILES_SCREENSHOT"); p != "" {
		f, err := os.Create(p)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if err = png.Encode(f, tt.Image()); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDownloadPublishesOnlyCompleteFile(t *testing.T) {
	dir := t.TempDir()
	destination := filepath.Join(dir, "report.txt")
	if err := os.WriteFile(destination, []byte("previous"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, mode := range []string{"failed", "cancelled", "complete"} {
		ctx, cancel := context.WithCancel(t.Context())
		err := saveDownload(ctx, destination, func(f *os.File) error {
			if _, err := f.WriteString("downloaded"); err != nil {
				return err
			}
			if mode == "failed" {
				return errors.New("connection interrupted")
			}
			if mode == "cancelled" {
				cancel()
			}
			return nil
		})
		cancel()
		b, readErr := os.ReadFile(destination)
		if readErr != nil {
			t.Fatal(readErr)
		}
		want := "previous"
		if mode == "complete" {
			want = "downloaded"
			if err != nil {
				t.Fatal(err)
			}
		} else if err == nil {
			t.Fatal("interrupted transfer succeeded")
		}
		if string(b) != want {
			t.Fatal("destination was overwritten", string(b))
		}
		entries, _ := os.ReadDir(dir)
		if len(entries) != 1 {
			t.Fatal("temporary download leaked")
		}
	}
}
