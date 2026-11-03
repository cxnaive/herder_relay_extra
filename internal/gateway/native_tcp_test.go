package gateway

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/0cv/herdr-mobile-relay/internal/nativeconn"
	"github.com/coder/websocket"
)

func tcpControl(t *testing.T, ctx context.Context, c *nativeconn.Conn) controlMessage {
	t.Helper()
	kind, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var m controlMessage
	if kind != websocket.MessageText || json.Unmarshal(b, &m) != nil {
		t.Fatal("bad JSON control")
	}
	return m
}
func TestNativeTCPGatewayRequiresRegistrationSecret(t *testing.T) {
	if s, err := New(Options{TCPAddr: "127.0.0.1:0"}); err == nil {
		s.Close()
		t.Fatal("unauthenticated TCP registration enabled")
	}
}
func TestNativeTCPGatewayRegistersRoutesOpaqueFramesAndRefusesWrongProof(t *testing.T) {
	token := strings.Repeat("t", 32)
	s, err := New(Options{TCPAddr: "127.0.0.1:0", RegistrationToken: token, MaxRelays: 2})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	endpoint := "tcp://" + s.TCPAddress()
	id, key := testIdentity(t)
	bad, err := nativeconn.Dial(ctx, endpoint, nativeconn.RoleRelay)
	if err != nil {
		t.Fatal(err)
	}
	_ = tcpControl(t, ctx, bad)
	if err = writeJSON(ctx, bad, gatewaywire.RegisterHello{Type: gatewaywire.TypeRegister, Proto: gatewaywire.Proto, RelayID: id, RegistrationProof: base64.RawURLEncoding.EncodeToString(make([]byte, 32))}); err != nil {
		t.Fatal(err)
	}
	if got := tcpControl(t, ctx, bad); got.Code != gatewaywire.CodeUnauthorized {
		t.Fatalf("bad registration admitted: %+v", got)
	}
	bad.CloseNow()
	relay, err := nativeconn.Dial(ctx, endpoint, nativeconn.RoleRelay)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.CloseNow()
	hello := tcpControl(t, ctx, relay)
	nonce, _ := base64.RawURLEncoding.DecodeString(hello.Nonce)
	if err = writeJSON(ctx, relay, gatewaywire.RegisterHello{Type: gatewaywire.TypeRegister, Proto: 1, RelayID: id, RegistrationProof: base64.RawURLEncoding.EncodeToString(gatewaywire.RegistrationProof(token, id, nonce))}); err != nil {
		t.Fatal(err)
	}
	if tcpControl(t, ctx, relay).Type != gatewaywire.TypeReady {
		t.Fatal("registration not ready")
	}
	phone, err := nativeconn.Dial(ctx, endpoint, nativeconn.RolePhone)
	if err != nil {
		t.Fatal(err)
	}
	defer phone.CloseNow()
	hello = tcpControl(t, ctx, phone)
	nonce, _ = base64.RawURLEncoding.DecodeString(hello.Nonce)
	if err = writeJSON(ctx, phone, gatewaywire.ConnectHello{Type: gatewaywire.TypeConnect, Proto: 1, RelayID: id, Proof: base64.RawURLEncoding.EncodeToString(gatewaywire.ConnectProof(key, id, nonce))}); err != nil {
		t.Fatal(err)
	}
	if tcpControl(t, ctx, phone).Type != gatewaywire.TypeReady {
		t.Fatal("phone not ready")
	}
	kind, open, err := relay.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	op, connID, _, err := gatewaywire.DecodeFrame(open)
	if err != nil || kind != websocket.MessageBinary || op != gatewaywire.OpOpen {
		t.Fatal("no open frame")
	}
	opaque := bytes.Repeat([]byte{1, 2, 3, 4}, 64000)
	if err = phone.Write(ctx, websocket.MessageBinary, opaque); err != nil {
		t.Fatal(err)
	}
	_, frame, err := relay.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	op, gotID, body, err := gatewaywire.DecodeFrame(frame)
	if err != nil || op != gatewaywire.OpData || gotID != connID || !bytes.Equal(body, opaque) {
		t.Fatal("phone payload altered")
	}
	if err = relay.Write(ctx, websocket.MessageBinary, gatewaywire.EncodeFrame(gatewaywire.OpData, connID, opaque)); err != nil {
		t.Fatal(err)
	}
	_, frame, err = phone.Read(ctx)
	if err != nil || !bytes.Equal(frame, opaque) {
		t.Fatal("relay payload altered")
	}
}
