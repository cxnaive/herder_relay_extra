// Package codexruntime queries only explicitly supplied Codex session IDs.
package codexruntime

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"regexp"
)

const maxThreads = 256
const threadPageSize = 64

var errUnavailable = errors.New("Codex runtime unavailable")
var threadID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *rpcError) Error() string { return "Codex runtime request unavailable" }
func (e *rpcError) Unwrap() error { return errUnavailable }

type rpc struct {
	conn *websocket.Conn
	seq  int
}

func (r *rpc) call(ctx context.Context, method string, params, result any) error {
	r.seq++
	if err := wsjson.Write(ctx, r.conn, map[string]any{"id": r.seq, "method": method, "params": params}); err != nil {
		return err
	}
	for i := 0; i < 64; i++ {
		var msg struct {
			ID     int             `json:"id"`
			Result json.RawMessage `json:"result"`
			Error  json.RawMessage `json:"error"`
		}
		if err := wsjson.Read(ctx, r.conn, &msg); err != nil {
			return err
		}
		if msg.ID != r.seq {
			continue
		}
		if len(msg.Error) > 0 && string(msg.Error) != "null" {
			var failure rpcError
			if json.Unmarshal(msg.Error, &failure) != nil {
				return errUnavailable
			}
			return &failure
		}
		if len(msg.Result) == 0 {
			return errUnavailable
		}
		return json.Unmarshal(msg.Result, result)
	}
	return errUnavailable
}
