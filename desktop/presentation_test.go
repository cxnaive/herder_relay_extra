package main

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/cxnaive/herdr-desktop/internal/vault"
	"github.com/egoist/mygo/ui"
)

func TestSidebarTwoLevelsPersistAndSearchTemporarilyExpands(t *testing.T) {
	a := newApplication(true)
	defer a.cancel()
	a.loadDemo()
	a.machines = a.machines[:1]
	m := a.machines[0]
	m.agents[0].TabLabel = "真正的会话名"
	m.workspaces = []relay.Workspace{{ID: "robotics", ServerSessionID: "primary", Label: "具身项目", Cwd: "/work/robotics"}}
	v, err := vault.Open(filepath.Join(t.TempDir(), "state.enc"), make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	a.store = &storage{vault: v, state: savedState{Drafts: map[string]string{}, Uncertain: map[string]bool{}}}
	tt := ui.NewTester(a.view, 1280, 840)
	click := func(label string) {
		t.Helper()
		if err := tt.Click(label); err != nil {
			t.Fatal(err)
		}
	}
	if !tt.HasText("真正的会话名") || !tt.HasText("具身项目") {
		t.Fatal("server labels missing")
	}
	click("具身项目")
	if tt.HasText("结果复核") {
		t.Fatal("workspace did not collapse")
	}
	click("研究工作站")
	if tt.HasText("具身项目") {
		t.Fatal("computer did not collapse")
	}
	click("搜索 Agent")
	tt.Type("结果复核")
	if !tt.HasText("结果复核") {
		t.Fatal("search failed to expand")
	}
	a.query = ""
	tt.Frame()
	if tt.HasText("具身项目") {
		t.Fatal("search overwrote collapse state")
	}
	var saved savedState
	if err = v.Load(&saved); err != nil || len(saved.Collapsed) != 2 {
		t.Fatal("collapse state not saved", err, saved.Collapsed)
	}
	click("研究工作站")
	if tt.HasText("结果复核") {
		t.Fatal("workspace collapse was lost")
	}
	click("具身项目")
	click("真正的会话名")
}

func TestFileCitationsAreLinksOnlyOutsideLiteralCode(t *testing.T) {
	raw := `:codex-file-citation{path="/home/hello/gt-vlm-cvpr2027/evidence/receipts/2026-11-04-neighbor-aligned-input-results.json" purpose="source"}`
	converted := citationMarkdown("结果 " + raw)
	if strings.Contains(converted, ":codex-file-citation") || !strings.Contains(converted, "herdr-file://open?path=") {
		t.Fatal(converted)
	}
	for _, src := range []string{"`" + raw + "`", "```text\n" + raw + "\n```", "    " + raw, "\\" + raw, "[" + raw + "](https://example.com)", `:codex-file-citation{path="x" path="y"}`, ":codex-file-citation{path=\"partial"} {
		if got := citationMarkdown(src); got != src {
			t.Fatalf("literal changed: %q -> %q", src, got)
		}
	}
	p, _, err := workspaceReference("/home/hello/gt-vlm-cvpr2027", "", "/home/hello/gt-vlm-cvpr2027/evidence/receipts/2026-11-04-neighbor-aligned-input-results.json")
	if err != nil || p != "evidence/receipts/2026-11-04-neighbor-aligned-input-results.json" {
		t.Fatal(p, err)
	}
	for _, src := range []string{"../../secret", "/home/hello/gt-vlm-cvpr2027-other/secret", "file://other/tmp/file", "https://example.com/image.png", "//other/share/file", "%2e%2e/%2e%2e/secret"} {
		if _, _, err := workspaceReference("/home/hello/gt-vlm-cvpr2027", "README.md", src); err == nil {
			t.Fatal("escaped workspace", src)
		}
	}
}

func TestCitationClickOpensFileAndMarkdownRelativeLinks(t *testing.T) {
	a := newApplication(true)
	defer a.cancel()
	a.loadDemo()
	_, chat := a.selected()
	chat.entries = []entry{{ID: "citation", Role: "assistant", Text: `请查看 :codex-file-citation{path="/work/robotics/eval-summary.json" purpose="source"}`}}
	tt := ui.NewTester(a.view, 1380, 840)
	if err := tt.Click("eval-summary.json"); err != nil {
		t.Fatal(err)
	}
	if !a.files || chat.files.previewPath != "eval-summary.json" || !tt.HasText("下载") {
		t.Fatal("citation did not open file")
	}
	m, _ := a.selected()
	a.openFile(m, chat, "reports/evaluation.md")
	chat.files.document.Data = []byte("# 报告\n\n[打开评测数据](../eval-summary.json)\n\n## 结论\n\n结果已经核对。")
	called := ""
	onLink := chat.files.md.OnLink
	chat.files.md.OnLink = func(dest string) { called = dest; onLink(dest) }
	tt.Frame()
	if !tt.HasText("结果已经核对。") {
		t.Fatal("markdown not rendered")
	}
	if err := tt.Click("打开评测数据"); err != nil {
		t.Fatal(err)
	}
	if chat.files.previewPath != "eval-summary.json" {
		t.Fatalf("relative document link resolved wrongly: path=%q status=%q called=%q", chat.files.previewPath, a.status, called)
	}
}

func TestToolGroupsNestedExpansionAndImagePreview(t *testing.T) {
	a := newApplication(true)
	defer a.cancel()
	a.loadDemo()
	_, chat := a.selected()
	im := image.NewRGBA(image.Rect(0, 0, 64, 32))
	for y := 0; y < 32; y++ {
		for x := 0; x < 64; x++ {
			im.Set(x, y, color.RGBA{30, 180, 120, 255})
		}
	}
	var b bytes.Buffer
	png.Encode(&b, im)
	chat.entries = []entry{{ID: "first", Role: "assistant", Tools: []map[string]any{{"id": "one", "name": "read_file", "input": "notes.md", "output": "读取完成"}}}, {ID: "second", Role: "assistant", Tools: []map[string]any{{"id": "two", "name": "exec_command", "input": "go test ./...", "output": "测试成功"}}}, {ID: "picture", Role: "assistant", Text: "评测图像", Images: []any{map[string]any{"name": "评测曲线", "data_url": "data:image/png;base64," + base64.StdEncoding.EncodeToString(b.Bytes())}}}}
	blocks := conversationBlocks(chat.entries)
	if len(blocks) != 2 || len(blocks[0].tools) != 2 {
		t.Fatal("adjacent tools not grouped", blocks)
	}
	tt := ui.NewTester(a.view, 1380, 900)
	click := func(s string) {
		t.Helper()
		if err := tt.Click(s); err != nil {
			t.Fatal(err)
		}
	}
	if tt.HasText("读取完成") {
		t.Fatal("details visible before expansion")
	}
	click("2 项工具调用")
	tt.Advance(time.Second)
	if tt.HasText("读取完成") {
		t.Fatal("second fold missing")
	}
	click("read_file")
	tt.Advance(time.Second)
	if !tt.HasText("读取完成") {
		t.Fatal("tool result missing")
	}
	click("放大查看")
	if a.imagePreview == nil || a.imagePreview.bitmap == nil {
		t.Fatal("image did not open")
	}
	click("放大")
	if a.imageZoom <= 1 {
		t.Fatal("image zoom failed")
	}
	click("关闭图片")
	if p := os.Getenv("HERDR_PRESENTATION_SCREENSHOT"); p != "" {
		f, err := os.Create(p)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		png.Encode(f, tt.Image())
	}
}

func TestRuntimeTimerStopsWhenStaleDisconnectedOrReplaced(t *testing.T) {
	now := time.Now()
	agent := relay.Agent{Target: relay.Target{PaneID: "p", Generation: 1}, Agent: "codex", BindingState: "verified"}
	r := runPresentation{}
	if !r.accept(relay.Message{"status": "working", "source": "codex_runtime", "timing": "turn_elapsed", "elapsed_ms": 5000, "ticking": true}, agent.Target, now) {
		t.Fatal("valid state rejected")
	}
	label, timer, spin := r.display(agent, true, true, now.Add(2*time.Second))
	if label != "运行中" || timer != "本轮 00:07" || !spin {
		t.Fatal(label, timer, spin)
	}
	_, timer, spin = r.display(agent, true, true, now.Add(20*time.Second))
	if timer != "" || spin {
		t.Fatal("stale timer ticked")
	}
	_, timer, spin = r.display(agent, false, true, now)
	if timer != "" || spin {
		t.Fatal("disconnected timer ticked")
	}
	agent.Generation++
	_, timer, spin = r.display(agent, true, true, now)
	if timer != "" || spin {
		t.Fatal("replaced timer ticked")
	}
	if r.accept(relay.Message{"status": "unknown", "source": "unverified_primary", "elapsed_ms": 123, "ticking": true}, agent.Target, now) {
		t.Fatal("unverified timing accepted")
	}
}
