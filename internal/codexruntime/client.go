// Package codexruntime reads local Codex TUI associations and manages explicit
// per-thread model settings. It never starts/resumes threads or invokes tools.
package codexruntime

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"reflect"
	"regexp"
	"sort"
	"strconv"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const maxThreads = 64

var errUnavailable = errors.New("Codex runtime identity unavailable or ambiguous")
var threadID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type Thread struct {
	ID         string `json:"id"`
	CWD        string `json:"cwd"`
	Name       string `json:"name"`
	Originator string `json:"originator"`
}

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
			return errUnavailable
		}
		if len(msg.Result) == 0 {
			return errUnavailable
		}
		return json.Unmarshal(msg.Result, result)
	}
	return errUnavailable
}

// LocalOrigin deliberately accepts only the IPv4 loopback form emitted by the
// installed TUI. No URL returned by the daemon is ever fetched by the relay.
func LocalOrigin(origin string) bool {
	u, err := url.Parse(origin)
	if err != nil || u.Scheme != "http" || u.Hostname() != "127.0.0.1" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	p, err := strconv.Atoi(u.Port())
	return err == nil && p > 0 && p <= 65535 && origin == "http://127.0.0.1:"+strconv.Itoa(p)
}

type association struct {
	ID        string
	Connected bool
}

func (r *rpc) associations(ctx context.Context) (map[string][]association, error) {
	var list struct {
		Data       []string `json:"data"`
		NextCursor *string  `json:"nextCursor"`
	}
	if err := r.call(ctx, "thread/loaded/list", map[string]any{"limit": maxThreads}, &list); err != nil {
		return nil, err
	}
	// Never choose a unique match from a partial inventory.
	if list.Data == nil || list.NextCursor != nil || len(list.Data) > maxThreads {
		return nil, errUnavailable
	}
	seen := map[string]bool{}
	origins := map[string][]association{}
	for _, id := range list.Data {
		if !threadID.MatchString(id) || seen[id] {
			return nil, errUnavailable
		}
		seen[id] = true
		var status struct {
			Data []struct {
				Name   string `json:"name"`
				Origin string `json:"httpOrigin"`
				Status string `json:"runtimeStatus"`
			} `json:"data"`
			NextCursor *string `json:"nextCursor"`
		}
		if err := r.call(ctx, "mcpServerStatus/list", map[string]any{"threadId": id, "serverName": "codex_tui", "detail": "toolsAndAuthOnly"}, &status); err != nil {
			return nil, err
		}
		if status.Data == nil || status.NextCursor != nil || len(status.Data) > 1 {
			return nil, errUnavailable
		}
		for _, s := range status.Data {
			if s.Name != "codex_tui" {
				return nil, errUnavailable
			}
			if LocalOrigin(s.Origin) {
				// Count disconnected/stale associations too. Ignoring one could turn a
				// reused TUI endpoint into a falsely unique current-thread identity.
				origins[s.Origin] = append(origins[s.Origin], association{id, s.Status == "connected"})
			}
		}
	}
	for _, rows := range origins {
		sort.Slice(rows, func(i, j int) bool { return rows[i].ID < rows[j].ID })
	}
	return origins, nil
}

// Discover resolves origins with exactly one connected loaded thread. An empty
// Thread marks an ambiguous/unusable origin, including across configured daemons.
func Discover(ctx context.Context, socket string, wanted map[string]bool) (map[string]Thread, error) {
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", socket)
	}}
	defer transport.CloseIdleConnections()
	conn, _, err := websocket.Dial(ctx, "ws://localhost/", &websocket.DialOptions{HTTPClient: &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return errUnavailable }}})
	if err != nil {
		return nil, err
	}
	defer conn.CloseNow()
	conn.SetReadLimit(512 * 1024)
	r := rpc{conn: conn}
	var initialized json.RawMessage
	if err = r.call(ctx, "initialize", map[string]any{"clientInfo": map[string]any{"name": "herdr_mobile_relay_identity", "version": "1"}, "capabilities": map[string]any{"experimentalApi": true}}, &initialized); err != nil {
		return nil, err
	}
	if err = wsjson.Write(ctx, conn, map[string]any{"method": "initialized"}); err != nil {
		return nil, err
	}
	origins, err := r.associations(ctx)
	if err != nil {
		return nil, err
	}
	result := map[string]Thread{}
	for origin, ids := range origins {
		if !wanted[origin] {
			continue
		}
		result[origin] = Thread{}
		if len(ids) != 1 || !ids[0].Connected {
			continue
		}
		var response struct {
			Thread Thread `json:"thread"`
		}
		if err = r.call(ctx, "thread/read", map[string]any{"threadId": ids[0].ID, "includeTurns": false}, &response); err != nil {
			return nil, err
		}
		t := response.Thread
		if t.ID != ids[0].ID || t.Originator != "codex-tui" || t.CWD == "" || t.Name == "" {
			continue
		}
		result[origin] = t
	}
	after, err := r.associations(ctx)
	if err != nil {
		return nil, err
	}
	if !reflect.DeepEqual(origins, after) {
		return nil, errUnavailable
	}
	return result, nil
}
