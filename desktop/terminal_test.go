package main

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/egoist/mygo/plugins/terminal"
	"github.com/egoist/mygo/ui"
)

type terminalFixture struct {
	*io.PipeReader
	writer     *io.PipeWriter
	mu         sync.Mutex
	input      bytes.Buffer
	cols, rows int
}

func (f *terminalFixture) Write(b []byte) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.input.Write(b)
}
func (f *terminalFixture) Resize(cols, rows int) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cols, f.rows = cols, rows
	return nil
}
func (f *terminalFixture) Close() error { f.writer.Close(); return f.PipeReader.Close() }
func prepareNativeTerminal(t *testing.T) {
	t.Helper()
	if runtime.GOOS == "windows" {
		p, err := filepath.Abs("build/ghostty-vt.dll")
		if err != nil {
			t.Fatal(err)
		}
		if _, err = os.Stat(p); err != nil {
			p, err = filepath.Abs("build/windows-amd64/ghostty-vt.dll")
		}
		if _, err = os.Stat(p); err != nil {
			t.Fatal("build the app first to obtain the pinned terminal library:", err)
		}
		t.Setenv("MYGO_GHOSTTY_VT", p)
	}
}
func TestNativeTerminalUnicodeInputResizeAndControls(t *testing.T) {
	prepareNativeTerminal(t)
	r, w := io.Pipe()
	conn := &terminalFixture{PipeReader: r, writer: w}
	term, err := terminal.New(terminal.Options{Conn: conn, NoClipboardWrite: true})
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()
	tt := ui.NewTester(func(c *ui.Context) { terminal.View(c, term).Fill().AutoFocus() }, 800, 400)
	go w.Write([]byte("\x1b[32m原生终端 READY\x1b[0m\r\n"))
	deadline := time.Now().Add(3 * time.Second)
	for !strings.Contains(term.Text(), "READY") {
		if time.Now().After(deadline) {
			t.Fatal("terminal did not render the stream")
		}
		time.Sleep(10 * time.Millisecond)
		tt.Frame()
	}
	tt.Type("中文")
	tt.Key(ui.Ctrl, ui.KeyC)
	deadline = time.Now().Add(3 * time.Second)
	for {
		conn.mu.Lock()
		input := conn.input.String()
		cols, rows := conn.cols, conn.rows
		conn.mu.Unlock()
		if strings.Contains(input, "中文") && strings.Contains(input, "\x03") && cols > 0 && rows > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("input=%q size=%dx%d", input, cols, rows)
		}
		time.Sleep(10 * time.Millisecond)
		tt.Frame()
	}
	conn.mu.Lock()
	cols := conn.cols
	conn.mu.Unlock()
	tt.SetSize(1024, 600)
	deadline = time.Now().Add(3 * time.Second)
	for {
		conn.mu.Lock()
		now := conn.cols
		conn.mu.Unlock()
		if now > cols {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("resize was not passed to the connection")
		}
		time.Sleep(10 * time.Millisecond)
		tt.Frame()
	}
}
