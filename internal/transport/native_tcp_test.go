package transport

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/config"
	"github.com/0cv/herdr-mobile-relay/internal/gateway"
	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/0cv/herdr-mobile-relay/internal/nativeconn"
	"github.com/coder/websocket"
)

func newNativeHarness(t *testing.T) (*gatewayHarness, string) {
	t.Helper()
	registration := strings.Repeat("s", 32)
	server, err := gateway.New(gateway.Options{TCPAddr: "127.0.0.1:0", RegistrationToken: registration})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	endpoint := "tcp://" + server.TCPAddress()
	hub := NewHub(&config.Config{Token: gatewayTestRelayKey}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	hub.SetE2EEAuthResolver(fixedE2EEAuthResolver{secret: []byte(gatewayTestRelayKey)})
	h := &gatewayHarness{hub: hub, connected: make(chan *ClientConn, 8), received: make(chan map[string]any, 8)}
	h.relayID, _ = gatewaywire.DeriveRelayID(gatewayTestRelayKey)
	h.rendezvousKey, _ = gatewaywire.DeriveRendezvousKey(gatewayTestRelayKey)
	hub.SetOnConnect(func(c *ClientConn) { h.connected <- c })
	hub.SetHandler(func(c *ClientConn, m map[string]any, admitted func()) { admitted(); h.received <- m })
	h.client, err = NewGatewayClient(hub, GatewayOptions{URL: endpoint, RelayKey: gatewayTestRelayKey, RegistrationToken: registration})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); h.client.Run(ctx) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(gatewayTestTimeout):
			t.Error("native relay shutdown timeout")
		}
		ctx, c := context.WithTimeout(context.Background(), gatewayTestTimeout)
		defer c()
		_ = hub.Shutdown(ctx)
	})
	h.waitRegistered(t)
	return h, endpoint
}
func nativePhone(t *testing.T, h *gatewayHarness, endpoint string) *gatewayTestPhone {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), gatewayTestTimeout)
	defer cancel()
	c, err := nativeconn.Dial(ctx, endpoint, nativeconn.RolePhone)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var hello gatewaywire.ServerHello
	if err = json.Unmarshal(b, &hello); err != nil {
		t.Fatal(err)
	}
	nonce, err := base64.RawURLEncoding.DecodeString(hello.Nonce)
	if err != nil {
		t.Fatal(err)
	}
	b, _ = json.Marshal(gatewaywire.ConnectHello{Type: gatewaywire.TypeConnect, Proto: 1, RelayID: h.relayID, Proof: base64.RawURLEncoding.EncodeToString(gatewaywire.ConnectProof(h.rendezvousKey, h.relayID, nonce))})
	if err = c.Write(ctx, websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
	_, b, err = c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var ready map[string]any
	_ = json.Unmarshal(b, &ready)
	if ready["type"] != "ready" {
		t.Fatalf("not ready: %s", b)
	}
	return &gatewayTestPhone{tcp: c}
}
func TestNativeTCPRealE2EEHistoryAndReplay(t *testing.T) {
	h, endpoint := newNativeHarness(t)
	p := nativePhone(t, h, endpoint)
	p.handshake(t, gatewayTestRelayKey)
	var connection *ClientConn
	select {
	case connection = <-h.connected:
	case <-time.After(gatewayTestTimeout):
		t.Fatal("E2EE never authenticated")
	}
	p.sendMessage(t, `{"type":"test_history","request_id":"encrypted-only"}`)
	select {
	case m := <-h.received:
		if m["request_id"] != "encrypted-only" {
			t.Fatal("command corrupted")
		}
	case <-time.After(gatewayTestTimeout):
		t.Fatal("no command")
	}
	text := strings.Repeat("history 图像 markdown ", 30000)
	h.hub.Send(connection, map[string]any{"type": "history_result", "body": text})
	var response map[string]any
	if err := json.Unmarshal(p.readMessage(t), &response); err != nil {
		t.Fatal(err)
	}
	if response["body"] != text || p.lastChunks < 2 {
		t.Fatal("multi-chunk encrypted history damaged")
	}
	frame, err := p.session.seal([]byte(`{"type":"replay_test"}`))
	if err != nil {
		t.Fatal(err)
	}
	p.send(t, frame)
	select {
	case <-h.received:
	case <-time.After(gatewayTestTimeout):
		t.Fatal("valid frame lost")
	}
	p.send(t, frame)
	ctx, cancel := context.WithTimeout(t.Context(), gatewayTestTimeout)
	defer cancel()
	if _, _, err := p.tcp.Read(ctx); err == nil {
		t.Fatal("replayed ciphertext not closed")
	}
	select {
	case <-h.received:
		t.Fatal("replay reached handler")
	default:
	}
}
func TestNativeTCPRejectsPlaintextAndTamperedCiphertext(t *testing.T) {
	for _, attack := range []string{"plaintext", "tampered"} {
		t.Run(attack, func(t *testing.T) {
			h, endpoint := newNativeHarness(t)
			p := nativePhone(t, h, endpoint)
			if attack == "plaintext" {
				p.send(t, []byte(`{"type":"run_command","text":"must-not-run"}`))
			} else {
				p.handshake(t, gatewayTestRelayKey)
				frame, err := p.session.seal([]byte(`{"type":"must-not-run"}`))
				if err != nil {
					t.Fatal(err)
				}
				frame[len(frame)-1] ^= 0x80
				p.send(t, frame)
			}
			ctx, cancel := context.WithTimeout(t.Context(), gatewayTestTimeout)
			defer cancel()
			if _, _, err := p.tcp.Read(ctx); err == nil {
				t.Fatal("unauthenticated frame not rejected")
			}
			select {
			case <-h.received:
				t.Fatal("unauthenticated command dispatched")
			default:
			}
		})
	}
}
