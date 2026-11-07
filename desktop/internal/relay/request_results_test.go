package relay

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestEncryptedUploadRepliesReachWaitingRequest(t *testing.T) {
	for _, typ := range []string{"upload_begin_result", "upload_chunk_result", "upload_finish_result", "upload_cancel_result"} {
		t.Run(typ, func(t *testing.T) {
			client, server := net.Pipe()
			t.Cleanup(func() { client.Close(); server.Close() })
			in, _ := newAEAD(make([]byte, 32))
			out, _ := newAEAD(make([]byte, 32))
			session := &Session{wire: &wire{conn: client}, cipher: &sessionCipher{in: in, out: out}, pending: map[string]chan Message{}, closed: make(chan struct{}), Events: make(chan Message, 4)}
			go session.readLoop()
			defer session.Close()
			result := make(chan error, 1)
			go func() {
				w := &wire{conn: server}
				b, err := w.logicalRead()
				if err != nil {
					result <- err
					return
				}
				nonce, aad := nonceAAD(0, "c2s")
				plain, err := out.Open(nil, nonce, b[10:], aad)
				if err != nil {
					result <- err
					return
				}
				request, err := parseMessage(plain)
				if err != nil {
					result <- err
					return
				}
				response, _ := json.Marshal(Message{"type": typ, "request_id": request["request_id"], "result": Message{"fixture": "ok"}})
				nonce, aad = nonceAAD(0, "s2c")
				frame := make([]byte, 10)
				frame[0] = 2
				binary.BigEndian.PutUint64(frame[2:], 0)
				result <- w.logicalWrite(in.Seal(frame, nonce, response, aad))
			}()
			ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
			defer cancel()
			data, err := session.Request(ctx, Message{"type": "upload_begin"})
			if err != nil || Text(data, "fixture") != "ok" {
				t.Fatal("reply went to events instead of requester", data, err)
			}
			if err := <-result; err != nil {
				t.Fatal(err)
			}
		})
	}
}
