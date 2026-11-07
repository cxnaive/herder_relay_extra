package main

import (
	"bytes"
	"fmt"
	"image/png"
	"math"
	"os"
	"path/filepath"
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

func TestConversationReflowsWithinItsPane(t *testing.T) {
	app := newApplication(true)
	defer app.cancel()
	app.loadDemo()
	_, chat := app.selected()
	short := "看看训练情况"
	long := strings.Repeat("请检查最新训练进度、验证指标和保存的 checkpoint，并整理结果。", 5) + "\n/workspace/" + strings.Repeat("long_path_", 16) + "report.json"
	chat.entries = []entry{
		{ID: "short", Role: "user", Text: short},
		{ID: "reply", Role: "assistant", Text: "### Checkpoint 保存正常\n\n已读取最新的 checkpoint 元数据。\n\n- COTR：`recovery-last.ckpt`，保存到 step161。\n- 纯 2f：`last.ckpt`，保存到第 10 轮结束。\n\n**本次只检查，没有重启或改动训练。**"},
		{ID: "long", Role: "user", Text: long},
		{ID: "tools", Role: "assistant", Tools: []map[string]any{{"id": "read", "name": "read_file", "output": "读取完成"}}},
	}
	tt := ui.NewTester(app.view, 1600, 1200)
	find := func(label string) ui.Rect {
		t.Helper()
		r, ok := tt.Find(label)
		if !ok {
			t.Fatalf("missing %q", label)
		}
		return r
	}
	near := func(a, b float32) bool { return math.Abs(float64(a-b)) <= 1 }
	var wideHeight float32
	for _, row := range []struct {
		width int
		files bool
		scale float32
	}{{1600, false, 1}, {800, false, 1}, {1280, true, 1}, {800, false, 1.5}, {1600, false, 1}} {
		app.files = row.files
		tt.SetScale(row.scale)
		tt.SetSize(row.width, 1200)
		tt.Frame()
		input, bubble, paragraph, tools := find("对话输入"), find(short), find(long), find("1 项工具调用")
		if bubble.W >= input.W*.6 || !near(bubble.X+bubble.W, input.X+input.W) {
			t.Fatalf("short bubble stretched or misaligned at %+v: bubble=%+v input=%+v", row, bubble, input)
		}
		if paragraph.X < input.X || paragraph.W > input.W*.85+1 || !near(paragraph.X+paragraph.W, input.X+input.W) {
			t.Fatalf("long message escaped its pane at %+v: message=%+v input=%+v", row, paragraph, input)
		}
		// Find returns the accordion's inset text box, not its outside border.
		if tools.X < input.X || tools.X > input.X+24 || tools.X+tools.W > input.X+input.W || !near(find("Codex").X, input.X) {
			t.Fatalf("reply, tools and composer misaligned at %+v: tools=%+v input=%+v", row, tools, input)
		}
		if paragraph.Y+paragraph.H > tools.Y || input.X+input.W > float32(row.width) {
			t.Fatal("reflow overlapped the next message or window edge", row)
		}
		if row.width == 1600 {
			if wideHeight != 0 && !near(paragraph.H, wideHeight) {
				t.Fatal("resizing back retained stale row height")
			}
			wideHeight = paragraph.H
			if !near(input.X+input.W/2, (260+float32(row.width))/2) {
				t.Fatal("conversation not centered within available pane", input)
			}
		} else if paragraph.H <= wideHeight {
			t.Fatal("narrower pane did not reflow long text", row, paragraph.H, wideHeight)
		}
		if dir := os.Getenv("HERDR_CHAT_SCREENSHOTS"); dir != "" {
			f, err := os.Create(filepath.Join(dir, fmt.Sprintf("chat-%d-files-%t-scale-%.1f.png", row.width, row.files, row.scale)))
			if err != nil {
				t.Fatal(err)
			}
			err = png.Encode(f, tt.Image())
			f.Close()
			if err != nil {
				t.Fatal(err)
			}
		}
	}
}
