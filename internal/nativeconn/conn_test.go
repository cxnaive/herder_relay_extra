package nativeconn

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func pipePair(t *testing.T) (*Conn, *Conn) {
	t.Helper()
	a, b := net.Pipe()
	left, right := newConn(a), newConn(b)
	t.Cleanup(func() { left.CloseNow(); right.CloseNow() })
	return left, right
}
func TestNativeTCPRecordSurvivesPartialReadsAndConcurrentWrites(t *testing.T) {
	a, b := pipePair(t)
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if err := a.Write(ctx, websocket.MessageBinary, bytes.Repeat([]byte{byte(i)}, 32768)); err != nil {
				t.Error(err)
			}
		}(i)
	}
	seen := map[byte]bool{}
	for i := 0; i < 8; i++ {
		kind, data, err := b.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if kind != websocket.MessageBinary || len(data) != 32768 || !bytes.Equal(data, bytes.Repeat(data[:1], 32768)) {
			t.Fatal("interleaved/corrupt record")
		}
		seen[data[0]] = true
	}
	wg.Wait()
	if len(seen) != 8 {
		t.Fatal("lost record")
	}
}
func TestNativeTCPRejectsOversizedAndUnknownTypeBeforeAllocatingBody(t *testing.T) {
	for _, item := range []struct {
		kind byte
		size uint32
	}{{2, MaxRecord + 1}, {9, 1}, {3, 9}, {4, 0}, {1, 1 << 31}} {
		t.Run(string(rune(item.kind))+time.Duration(item.size).String(), func(t *testing.T) {
			a, b := net.Pipe()
			c := newConn(a)
			defer c.CloseNow()
			defer b.Close()
			ctx, cancel := context.WithTimeout(t.Context(), time.Second)
			defer cancel()
			go func() {
				var header [5]byte
				header[0] = item.kind
				binary.BigEndian.PutUint32(header[1:], item.size)
				_, _ = b.Write(header[:])
			}()
			if _, _, err := c.Read(ctx); !errors.Is(err, ErrProtocol) {
				t.Fatalf("expected protocol error, got %v", err)
			}
		})
	}
}
func TestNativeTCPReadCancellationClosesPartialFrame(t *testing.T) {
	a, b := pipePair(t)
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() { _, _, err := a.Read(ctx); done <- err }()
	_, _ = b.net.Write([]byte{2, 0})
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancel accepted")
		}
	case <-time.After(time.Second):
		t.Fatal("read blocked")
	}
}
func TestNativeTCPRequiresExplicitSafeEndpoint(t *testing.T) {
	for _, raw := range []string{"tcp://127.0.0.1:8377", "tcp://[::1]:8377", "tcp://relay.example:8377"} {
		if _, err := Endpoint(raw); err != nil {
			t.Fatal(err)
		}
	}
	for _, raw := range []string{"tcp://127.0.0.1", "tcp://x:0", "tcp://x:65536", "tcp://user@x:8377", "tcp://x:8377/path", "tcp://x:8377?key=secret", "ws://x:8377", "tcp://[fe80::1%25eth0]:8377"} {
		if _, err := Endpoint(raw); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}
func TestNativeTCPPrefaceAndProbeRole(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		n, err := l.Accept()
		if err != nil {
			done <- err
			return
		}
		c, role, err := Accept(ctx, n)
		if err != nil {
			done <- err
			return
		}
		defer c.CloseNow()
		if role != RoleProbe {
			done <- ErrProtocol
			return
		}
		done <- c.Write(ctx, websocket.MessageText, []byte("ok"))
	}()
	c, err := Dial(ctx, "tcp://"+l.Addr().String(), RoleProbe)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	kind, body, err := c.Read(ctx)
	if err != nil || kind != websocket.MessageText || string(body) != "ok" {
		t.Fatalf("%d %q %v", kind, body, err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
func TestNativeTCPPingUsesNoncePongWhileReaderActive(t *testing.T) {
	a, b := pipePair(t)
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	go func() { _, _, _ = a.Read(ctx) }()
	go func() { _, _, _ = b.Read(ctx) }()
	if err := a.Ping(ctx); err != nil {
		t.Fatal(err)
	}
}
