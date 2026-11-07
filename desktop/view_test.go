package main

import (
	"bytes"
	"image/png"
	"os"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

func TestSidebarNavigationKeepsDraftsAndView(t *testing.T) {
	app := newApplication(true)
	defer app.cancel()
	app.loadDemo()
	tt := ui.NewTester(app.view, 1180, 760)
	if err := tt.Click("对话输入"); err != nil {
		t.Fatal(err)
	}
	tt.Type("检查中文输入")
	_, first := app.selected()
	if first.draft != "检查中文输入" {
		t.Fatalf("draft %q", first.draft)
	}
	if err := tt.Click("结果复核"); err != nil {
		t.Fatal(err)
	}
	_, second := app.selected()
	if second == first || second.draft != "" {
		t.Fatal("draft crossed sessions")
	}
	if err := tt.Click("终端"); err != nil {
		t.Fatal(err)
	}
	if app.mode != 1 || !tt.HasText("打开本机终端") {
		t.Fatal("terminal did not replace conversation")
	}
	if err := tt.Click("评测报告"); err != nil {
		t.Fatal(err)
	}
	if err := tt.Click("对话"); err != nil {
		t.Fatal(err)
	}
	_, restored := app.selected()
	if restored.draft != "检查中文输入" {
		t.Fatal("draft was lost")
	}
	if err := tt.Click("1 项工具调用"); err != nil {
		t.Fatal(err)
	}
	if !tt.HasText("read_file") {
		t.Fatal("tool details absent")
	}
}

func TestChineseCompositionSurvivesIncomingMessages(t *testing.T) {
	app := newApplication(true)
	defer app.cancel()
	app.loadDemo()
	tt := ui.NewTester(app.view, 1180, 760)
	if err := tt.Click("对话输入"); err != nil {
		t.Fatal(err)
	}
	tt.Compose("pin", 3)
	_, chat := app.selected()
	chat.entries[1].Text += "\n\n正在继续处理。"
	tt.Frame()
	if chat.draft != "" {
		t.Fatal("composition committed during an output refresh")
	}
	tt.Compose("", 0)
	tt.Type("拼音")
	if chat.draft != "拼音" {
		t.Fatalf("input after refresh %q", chat.draft)
	}
}
func TestAppIconAndUserFacingCopy(t *testing.T) {
	im, err := png.Decode(bytes.NewReader(appIcon))
	if err != nil || im.Bounds().Dx() != 1024 {
		t.Fatal("invalid app icon", err)
	}
	app := newApplication(true)
	defer app.cancel()
	app.loadDemo()
	tt := ui.NewTester(app.view, 1180, 760)
	for _, s := range tt.Texts() {
		if strings.Contains(s, "desktop_terminal_v1") || strings.Contains(s, "libghostty") {
			t.Fatal("implementation note visible", s)
		}
	}
	if path := os.Getenv("HERDR_UI_SCREENSHOT"); path != "" {
		f, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if err = png.Encode(f, tt.Image()); err != nil {
			t.Fatal(err)
		}
	}
}
