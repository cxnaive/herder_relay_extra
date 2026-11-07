package main

import (
	"image"
	"image/color"
	"image/png"
	"io"
	"math"
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo/plugins/terminal"
	"github.com/egoist/mygo/ui"
)

func TestAppearancePersistsAndSurvivesComputerRemoval(t *testing.T) {
	a, m, _ := managementFixture(t)
	p := appearanceSettings{Application: "dark", Terminal: "light", OriginalColors: true}
	a.changeAppearance(p)
	var saved savedState
	if err := a.store.vault.Load(&saved); err != nil {
		t.Fatal(err)
	}
	if saved.Appearance != p || a.appearance != p {
		t.Fatal("appearance was not persisted", saved.Appearance)
	}
	if err := a.store.removeComputer(m.config.ID); err != nil {
		t.Fatal(err)
	}
	if a.store.appearance() != p {
		t.Fatal("removing a computer changed local appearance")
	}
	if value := (appearanceSettings{}).normalized(); value.Application != "system" || value.Terminal != "dark" || value.contrast() != 4.5 {
		t.Fatal("unsafe defaults", value)
	}
}
func TestAppearanceKeepsApplicationAndTerminalIndependent(t *testing.T) {
	a := newApplication(true)
	defer a.cancel()
	for _, row := range []struct {
		app, term                 string
		system, appDark, termDark bool
	}{
		{"light", "dark", false, false, true}, {"dark", "light", true, true, false},
		{"system", "application", true, true, true}, {"system", "application", false, false, false},
		{"system", "dark", false, false, true},
	} {
		a.appearance = appearanceSettings{Application: row.app, Terminal: row.term}
		a.systemDark = row.system
		if a.interfaceTheme(ui.LightTheme()).Dark != row.appDark || a.appearance.terminalDark(a.systemDark) != row.termDark {
			t.Fatal("wrong mode", row)
		}
		opts := a.terminalAppearanceOptions()
		if opts.Theme != opts.DarkTheme || !opts.NoClipboardWrite || opts.MinimumContrast != 4.5 {
			t.Fatal("terminal follows system palette or lost protections")
		}
	}
}
func TestAppearanceEntryAndNativeControls(t *testing.T) {
	a, _, _ := managementFixture(t)
	tt := ui.NewTester(a.view, 1280, 840)
	if err := tt.Click("外观"); err != nil {
		t.Fatal(err)
	}
	for _, label := range []string{"应用主题", "终端配色", "增强终端文字对比度", "终端预览"} {
		if !tt.HasText(label) {
			t.Fatal("missing appearance control", label)
		}
	}
	if err := tt.Click("选择应用主题"); err != nil {
		t.Fatal(err)
	}
	tt.Key(0, ui.KeyDown)
	tt.Key(0, ui.KeyDown)
	tt.Key(0, ui.KeyEnter)
	if a.appearance.Application != "dark" || a.store.appearance().Application != "dark" {
		t.Fatal("app control did not persist")
	}
	if err := tt.Click("选择终端配色"); err != nil {
		t.Fatal(err)
	}
	tt.Key(0, ui.KeyDown)
	tt.Key(0, ui.KeyEnter)
	if a.appearance.Terminal != "light" {
		t.Fatal("terminal control changed wrong setting")
	}
	if err := tt.Click("增强终端文字对比度"); err != nil {
		t.Fatal(err)
	}
	if !a.appearance.OriginalColors {
		t.Fatal("contrast toggle did not apply")
	}
	if err := tt.Click("完成"); err != nil {
		t.Fatal(err)
	}
	if a.appearanceOpen {
		t.Fatal("dialog did not close")
	}
}
func appearanceTerminal(t *testing.T, a *application) *terminal.Terminal {
	t.Helper()
	prepareNativeTerminal(t)
	r, w := io.Pipe()
	conn := &terminalFixture{PipeReader: r, writer: w}
	opts := a.terminalAppearanceOptions()
	opts.Conn = conn
	term, err := terminal.New(opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { term.Close() })
	return term
}
func luminance(c color.Color) float64 {
	r, g, b, _ := c.RGBA()
	linear := func(n uint32) float64 {
		v := float64(n) / 65535
		if v <= 0.04045 {
			return v / 12.92
		}
		return math.Pow((v+0.055)/1.055, 2.4)
	}
	return .2126*linear(r) + .7152*linear(g) + .0722*linear(b)
}
func readablePixels(im image.Image, bg color.Color) int {
	l := luminance(bg)
	count := 0
	for y := 0; y < min(60, im.Bounds().Dy()); y++ {
		for x := 0; x < min(280, im.Bounds().Dx()); x++ {
			v := luminance(im.At(x, y))
			if (max(v, l)+.05)/(min(v, l)+.05) >= 4 {
				count++
			}
		}
	}
	return count
}
func TestTerminalContrastCorrectsRemoteTrueColorAndDarkStrip(t *testing.T) {
	a := newApplication(true)
	defer a.cancel()
	a.appearance.Terminal = "light"
	a.appearance.OriginalColors = true
	term := appearanceTerminal(t, a)
	tt := ui.NewTester(func(c *ui.Context) { terminal.View(c, term).Fill() }, 500, 150)
	term.Feed([]byte("\x1b[?25l\x1b[38;2;255;223;159mMMMM readable text\x1b[0m"))
	tt.Frame()
	bg := color.NRGBA{R: 238, G: 237, B: 233, A: 255}
	before := readablePixels(tt.Image(), bg)
	term.SetMinimumContrast(4.5)
	tt.Frame()
	after := readablePixels(tt.Image(), bg)
	if after <= before+30 {
		t.Fatal("explicit pale ANSI text not corrected", before, after)
	}
	term.SetMinimumContrast(1)
	term.Feed([]byte("\x1b[2J\x1b[H\x1b[48;2;38;38;38m\x1b[38;2;0;0;0mMMMM dark input strip\x1b[0m"))
	tt.Frame()
	beforeImage := tt.Image()
	term.SetMinimumContrast(4.5)
	tt.Frame()
	afterImage := tt.Image()
	// Count changed pixels rather than mistaking the surrounding light canvas
	// for text with adequate contrast inside the explicit dark input strip.
	changed := 0
	for y := 4; y < 24; y++ {
		for x := 6; x < 150; x++ {
			if luminance(afterImage.At(x, y))-luminance(beforeImage.At(x, y)) > .2 {
				changed++
			}
		}
	}
	if changed < 30 {
		t.Fatal("black text on remote dark strip remained unreadable", changed)
	}
}
func TestTerminalThemeSwitchKeepsSessionAndPreview(t *testing.T) {
	a, m, _ := managementFixture(t)
	a.mode = 1
	term := appearanceTerminal(t, a)
	m.term = term
	m.termIdentity = m.agents[0].Identity()
	m.termState = "终端已连接"
	tt := ui.NewTester(a.view, 1280, 840)
	term.Feed([]byte("\x1b[?25l 训练评估\r\n\r\n\x1b[38;2;255;223;159m### COTR：稳定推进\x1b[0m\r\n\r\n 训练进度  \x1b[38;2;255;128;170m100%\x1b[0m\r\n 文件位置  \x1b[38;2;160;220;160m/mnt/workspace/report.md\x1b[0m\r\n\r\n\x1b[48;2;38;38;38m\x1b[38;2;0;0;0m ❯ Ask Codex to do anything                         \x1b[0m\r\n"))
	tt.Frame()
	original := term.Text()
	for _, mode := range []string{"dark", "light"} {
		a.changeAppearance(appearanceSettings{Application: mode, Terminal: mode})
		tt.Frame()
		if m.term != term || term.Text() != original {
			t.Fatal("theme switch replaced terminal session or content")
		}
		if dir := os.Getenv("HERDR_APPEARANCE_SCREENSHOTS"); dir != "" {
			f, err := os.Create(filepath.Join(dir, "appearance-"+mode+".png"))
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
