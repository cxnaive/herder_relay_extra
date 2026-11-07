package relay

import (
	"bytes"
	"context"
	"crypto/cipher"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type terminalPeer struct {
	wire           *wire
	in, out        cipher.AEAD
	received, sent uint64
	sendMu         sync.Mutex
}

func (p *terminalPeer) read() (Message, error) {
	b, err := p.wire.logicalRead()
	if err != nil {
		return nil, err
	}
	if len(b) < 26 || binary.BigEndian.Uint64(b[2:10]) != p.received {
		return nil, fmt.Errorf("bad encrypted sequence")
	}
	nonce, aad := nonceAAD(p.received, "c2s")
	plain, err := p.in.Open(nil, nonce, b[10:], aad)
	p.received++
	if err != nil {
		return nil, err
	}
	return parseMessage(plain)
}
func (p *terminalPeer) send(m Message) error {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	nonce, aad := nonceAAD(p.sent, "s2c")
	frame := make([]byte, 10)
	frame[0] = 2
	binary.BigEndian.PutUint64(frame[2:], p.sent)
	p.sent++
	return p.wire.logicalWrite(p.out.Seal(frame, nonce, data, aad))
}
func (p *terminalPeer) ack(m Message, ok bool) error {
	return p.send(Message{"type": "command_result", "request_id": m["request_id"], "ok": ok, "phase": "completed", "data": Message{}})
}
func terminalPair(t *testing.T) (*Session, *terminalPeer, *TerminalConn) {
	t.Helper()
	left, right := net.Pipe()
	in, _ := newAEAD(make([]byte, 32))
	out, _ := newAEAD(bytes.Repeat([]byte{1}, 32))
	s := &Session{wire: &wire{conn: left}, cipher: &sessionCipher{in: in, out: out}, pending: map[string]chan Message{}, closed: make(chan struct{}), Events: make(chan Message)}
	p := &terminalPeer{wire: &wire{conn: right}, in: out, out: in}
	terminal := NewTerminalConn(s, Agent{Target: Target{ServerSessionID: "primary", PaneID: "pane", TerminalID: "terminal", Generation: 3, BindingEpoch: 7}, Cwd: "/work"})
	terminal.id = "stream"
	if err := s.attachTerminal(terminal); err != nil {
		t.Fatal(err)
	}
	go s.readLoop()
	t.Cleanup(func() { s.Close(); right.Close(); terminal.Close() })
	return s, p, terminal
}
func waitResult(t *testing.T, ch <-chan error) {
	t.Helper()
	select {
	case err := <-ch:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("operation waited for an acknowledgment instead of pipelining")
	}
}

func TestTerminalSendsOrderedInputBeforeAcknowledgments(t *testing.T) {
	_, peer, terminal := terminalPair(t)
	const count = 16
	received := make(chan error, 1)
	release := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		requests := []Message{}
		for i := 0; i < count; i++ {
			m, err := peer.read()
			if err != nil {
				received <- err
				return
			}
			data, err := base64.StdEncoding.DecodeString(Text(m, "data"))
			if err != nil || Number(m, "terminal_sequence") != int64(i+1) || string(data) != fmt.Sprintf("输入%d", i) || Text(m, "stream_id") != "stream" {
				received <- fmt.Errorf("input reordered: %v", m)
				return
			}
			target := Decode[Target](m["target"])
			if target != terminal.agent.Target {
				received <- fmt.Errorf("target lost")
				return
			}
			requests = append(requests, m)
		}
		received <- nil
		<-release
		for _, m := range requests {
			if err := peer.ack(m, true); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	wrote := make(chan error, 1)
	go func() {
		for i := 0; i < count; i++ {
			if _, err := terminal.Write([]byte(fmt.Sprintf("输入%d", i))); err != nil {
				wrote <- err
				return
			}
		}
		wrote <- nil
	}()
	waitResult(t, received)
	waitResult(t, wrote)
	close(release)
	waitResult(t, done)
}
func TestTerminalWindowIsBoundedAndNegativeAckStopsWithoutReplay(t *testing.T) {
	_, peer, terminal := terminalPair(t)
	first := make(chan Message, 1)
	received := make(chan error, 1)
	go func() {
		for i := 0; i < cap(terminal.inflight); i++ {
			m, err := peer.read()
			if err != nil {
				received <- err
				return
			}
			if i == 0 {
				first <- m
			}
			if Number(m, "terminal_sequence") != int64(i+1) {
				received <- fmt.Errorf("wrong sequence")
				return
			}
		}
		received <- nil
	}()
	for i := 0; i < cap(terminal.inflight); i++ {
		if _, err := terminal.Write([]byte("x")); err != nil {
			t.Fatal(err)
		}
	}
	waitResult(t, received)
	if len(terminal.inflight) != cap(terminal.inflight) {
		t.Fatal("unconfirmed input not bounded")
	}
	blocked := make(chan error, 1)
	go func() { _, err := terminal.Write([]byte("must not send")); blocked <- err }()
	select {
	case err := <-blocked:
		t.Fatal("window did not apply backpressure", err)
	case <-time.After(30 * time.Millisecond):
	}
	if err := peer.ack(<-first, false); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-blocked:
		if err == nil {
			t.Fatal("failed stream accepted input")
		}
	case <-time.After(time.Second):
		t.Fatal("failed acknowledgment did not close stream")
	}
	req, err := peer.read()
	if err != nil || Text(req, "type") != "terminal_close" {
		t.Fatal("replayed or additional input after failure", req, err)
	}
	if _, err = terminal.Write([]byte("later")); err == nil {
		t.Fatal("closed stream accepted input")
	}
}
func TestTerminalOutputBypassesUnconsumedUIEvents(t *testing.T) {
	session, peer, terminal := terminalPair(t)
	terminal.Start(nil)
	const count = 16
	want := bytes.Repeat([]byte("output\r\n"), count)
	done := make(chan error, 1)
	go func() {
		for i := 0; i < count; i++ {
			if err := peer.send(Message{"type": "terminal_output", "stream_id": "stream", "sequence": i + 1, "data": base64.StdEncoding.EncodeToString([]byte("output\r\n"))}); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	data := make([]byte, len(want))
	read := make(chan error, 1)
	go func() { _, err := io.ReadFull(terminal, data); read <- err }()
	waitResult(t, read)
	waitResult(t, done)
	if !bytes.Equal(data, want) {
		t.Fatal("ANSI bytes changed")
	}
	select {
	case message := <-session.Events:
		t.Fatal("terminal output entered UI event queue", message)
	default:
	}
}
func TestTerminalInputResizeAndScrollShareOneSequence(t *testing.T) {
	_, peer, terminal := terminalPair(t)
	got := make(chan error, 1)
	go func() {
		for i, action := range []string{"terminal_input", "terminal_resize", "terminal_scroll", "terminal_input"} {
			m, err := peer.read()
			if err != nil {
				got <- err
				return
			}
			if Text(m, "type") != action || Number(m, "terminal_sequence") != int64(i+1) {
				got <- fmt.Errorf("order: %v", m)
				return
			}
			if err = peer.ack(m, true); err != nil {
				got <- err
				return
			}
		}
		got <- nil
	}()
	if _, err := terminal.Write([]byte("first")); err != nil {
		t.Fatal(err)
	}
	if err := terminal.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	if err := terminal.Scroll(-2); err != nil {
		t.Fatal(err)
	}
	if _, err := terminal.Write([]byte("last")); err != nil {
		t.Fatal(err)
	}
	waitResult(t, got)
}
func TestCancelledRequestNeverDispatches(t *testing.T) {
	s, _, _ := terminalPair(t)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := s.beginRequest(ctx, Message{"type": "terminal_input"}); err == nil {
		t.Fatal("cancelled write accepted")
	}
}

func TestTerminalSimulatedRoundTripLatency(t *testing.T) {
	const count = 16
	const delay = 40 * time.Millisecond
	for _, pipeline := range []bool{false, true} {
		t.Run(fmt.Sprint(pipeline), func(t *testing.T) {
			s, peer, terminal := terminalPair(t)
			done := make(chan error, 1)
			go func() {
				var wg sync.WaitGroup
				failures := make(chan error, count)
				for i := 0; i < count; i++ {
					m, err := peer.read()
					if err != nil {
						done <- err
						return
					}
					wg.Add(1)
					go func() {
						defer wg.Done()
						timer := time.NewTimer(delay)
						defer timer.Stop()
						<-timer.C
						if err := peer.ack(m, true); err != nil {
							failures <- err
						}
					}()
				}
				wg.Wait()
				close(failures)
				for err := range failures {
					done <- err
					return
				}
				done <- nil
			}()
			start := time.Now()
			for i := 0; i < count; i++ {
				if pipeline {
					if _, err := terminal.Write([]byte("x")); err != nil {
						t.Fatal(err)
					}
				} else {
					m := terminal.agent.Request("terminal_input")
					m["stream_id"] = "stream"
					m["terminal_sequence"] = i + 1
					m["data"] = "eA=="
					if _, err := s.Request(t.Context(), m); err != nil {
						t.Fatal(err)
					}
				}
			}
			waitResult(t, done)
			t.Logf("16 inputs, simulated 40 ms acknowledgment: pipeline=%v elapsed=%s", pipeline, time.Since(start).Round(time.Millisecond))
		})
	}
}
