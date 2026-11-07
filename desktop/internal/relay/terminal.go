package relay

import (
	"context"
	"encoding/base64"
	"errors"
	"io"
	"sync"
	"time"
)

// TerminalConn is the native terminal's transport. It never starts a local shell.
// Event delivery is bounded; an overloaded or interrupted stream fails closed.
type TerminalConn struct {
	session   *Session
	agent     Agent
	id        string
	mu        sync.Mutex
	stateMu   sync.Mutex
	sequence  uint64
	reader    *io.PipeReader
	writer    *io.PipeWriter
	events    chan Message
	done      chan struct{}
	once      sync.Once
	closeOnce sync.Once
	geometry  func(int, int)
	consume   sync.Once
	life      context.Context
	cancel    context.CancelFunc
}

func NewTerminalConn(s *Session, a Agent) *TerminalConn {
	r, w := io.Pipe()
	life, cancel := context.WithCancel(context.Background())
	return &TerminalConn{session: s, agent: a, reader: r, writer: w, events: make(chan Message, 32), done: make(chan struct{}), life: life, cancel: cancel}
}
func (t *TerminalConn) Open(ctx context.Context) error {
	m := t.agent.Request("terminal_open")
	m["columns"] = 100
	m["rows"] = 30
	reply, err := t.session.Request(ctx, m)
	if err != nil {
		t.shutdown(err)
		return err
	}
	id := Text(reply, "stream_id")
	t.stateMu.Lock()
	t.id = id
	t.stateMu.Unlock()
	if id == "" {
		t.shutdown(errors.New("无效终端连接"))
		return errors.New("无效终端连接")
	}
	select {
	case <-t.done:
		t.closeRemote(id)
		return io.ErrClosedPipe
	default:
	}
	return nil
}
func (t *TerminalConn) Start(geometry func(int, int)) {
	t.stateMu.Lock()
	t.geometry = geometry
	t.stateMu.Unlock()
	t.consume.Do(func() { go t.readEvents() })
}
func (t *TerminalConn) Accept(m Message) {
	select {
	case <-t.done:
		return
	default:
	}
	select {
	case t.events <- m:
	default:
		t.shutdown(errors.New("终端输出超过缓冲上限，请重新连接"))
	}
}
func (t *TerminalConn) readEvents() {
	expected := int64(1)
	for {
		select {
		case <-t.done:
			return
		case <-t.session.Done():
			t.shutdown(errors.New("Relay 连接中断"))
			return
		case m := <-t.events:
			t.stateMu.Lock()
			id, geometry := t.id, t.geometry
			t.stateMu.Unlock()
			if Text(m, "stream_id") != id {
				continue
			}
			if Text(m, "type") == "terminal_closed" {
				t.shutdown(errors.New("远程终端已断开"))
				return
			}
			if Number(m, "sequence") != expected {
				t.shutdown(errors.New("终端帧不连续"))
				return
			}
			expected++
			data, err := base64.StdEncoding.DecodeString(Text(m, "data"))
			if err != nil || len(data) > 2*1024*1024 {
				t.shutdown(errors.New("无效终端数据"))
				return
			}
			cols, rows := int(Number(m, "columns")), int(Number(m, "rows"))
			if (cols != 0 || rows != 0) && (cols < 2 || cols > 512 || rows < 2 || rows > 256) {
				t.shutdown(errors.New("终端尺寸无效，请重新连接"))
				return
			}
			if cols > 0 && rows > 0 && geometry != nil {
				geometry(cols, rows)
			}
			if _, err = t.writer.Write(data); err != nil {
				t.shutdown(err)
				return
			}
		}
	}
}
func (t *TerminalConn) Read(b []byte) (int, error) { return t.reader.Read(b) }
func (t *TerminalConn) Write(b []byte) (int, error) {
	total := 0
	for len(b) > 0 {
		n := min(len(b), 32768)
		if err := t.operation("terminal_input", Message{"data": base64.StdEncoding.EncodeToString(b[:n])}); err != nil {
			return total, err
		}
		total += n
		b = b[n:]
	}
	return total, nil
}
func (t *TerminalConn) Resize(cols, rows int) error {
	return t.operation("terminal_resize", Message{"columns": cols, "rows": rows})
}
func (t *TerminalConn) Scroll(lines int) error {
	return t.operation("terminal_scroll", Message{"lines": max(-1000, min(1000, lines))})
}
func (t *TerminalConn) operation(action string, fields Message) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	select {
	case <-t.done:
		return io.ErrClosedPipe
	default:
	}
	m := t.agent.Request(action)
	t.stateMu.Lock()
	m["stream_id"] = t.id
	t.stateMu.Unlock()
	m["terminal_sequence"] = t.sequence + 1
	for k, v := range fields {
		m[k] = v
	}
	ctx, cancel := context.WithTimeout(t.life, 8*time.Second)
	defer cancel()
	if _, err := t.session.Request(ctx, m); err != nil {
		t.shutdown(err)
		return err
	}
	t.sequence++
	return nil
}
func (t *TerminalConn) shutdown(err error) {
	t.once.Do(func() {
		t.cancel()
		close(t.done)
		t.reader.CloseWithError(err)
		t.writer.CloseWithError(err)
		t.stateMu.Lock()
		id := t.id
		t.stateMu.Unlock()
		if id != "" {
			t.closeRemote(id)
		}
	})
}
func (t *TerminalConn) Close() error {
	t.shutdown(io.EOF)
	return nil
}
func (t *TerminalConn) closeRemote(id string) {
	t.closeOnce.Do(func() {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			m := t.agent.Request("terminal_close")
			m["stream_id"] = id
			_, _ = t.session.Request(ctx, m)
		}()
	})
}
