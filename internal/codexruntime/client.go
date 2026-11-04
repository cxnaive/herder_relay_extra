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
	"regexp"
	"sort"
	"strconv"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const maxThreads = 256
const threadPageSize = 64

var errUnavailable = errors.New("Codex runtime identity unavailable or ambiguous")
var threadID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type Thread struct {
	ID                string          `json:"id"`
	Path              string          `json:"path"`
	CWD               string          `json:"cwd"`
	Name              string          `json:"name"`
	Originator        string          `json:"originator"`
	SessionID         string          `json:"sessionId"`
	ParentThreadID    string          `json:"parentThreadId"`
	ForkedFromID      string          `json:"forkedFromId"`
	Source            json.RawMessage `json:"source"`
	Status            runtimeStatus   `json:"status"`
	Unavailable       string          `json:"-"`
	HistoryState      HistoryState    `json:"-"`
	HistoryCandidates []Thread        `json:"-"`
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

	ids := []string{}
	cursor := ""
	cursors := map[string]bool{}
	for {
		var list struct {
			Data       []string `json:"data"`
			NextCursor *string  `json:"nextCursor"`
		}
		params := map[string]any{"limit": threadPageSize}
		if cursor != "" {
			params["cursor"] = cursor
		}
		if err := r.call(ctx, "thread/loaded/list", params, &list); err != nil {
			return nil, err
		}
		if list.Data == nil || len(list.Data) > threadPageSize || len(ids)+len(list.Data) > maxThreads {
			return nil, errUnavailable
		}
		ids = append(ids, list.Data...)
		if list.NextCursor == nil {
			break
		}
		cursor = *list.NextCursor
		if cursor == "" || cursors[cursor] || len(ids) >= maxThreads {
			return nil, errUnavailable
		}
		cursors[cursor] = true
	}
	seen := map[string]bool{}
	origins := map[string][]association{}
	for _, id := range ids {
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
// Such an origin may expose separately checked history candidates, never a primary ID.
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

		result[origin] = r.primaryForOrigin(ctx, ids)
	}

	after, err := r.associations(ctx)
	if err != nil {
		return nil, err
	}

	for origin := range wanted {
		before, known := result[origin]
		if !known && len(after[origin]) == 0 {
			continue
		}
		current := r.primaryForOrigin(ctx, after[origin])
		switch {
		case before.ID != "" && current.ID == before.ID && current.CWD == before.CWD:
			// A proven child starting or ending does not replace its primary.
			result[origin] = current
		case before.ID == "" && current.ID == "":
			if sameHistoryCandidates(before.HistoryCandidates, current.HistoryCandidates) {
				result[origin] = current
			} else {
				result[origin] = Thread{Unavailable: "runtime_changed"}
			}
		default:
			result[origin] = Thread{Unavailable: "runtime_changed"}
		}
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	return result, nil
}

func (r *rpc) primaryForOrigin(ctx context.Context, ids []association) Thread {
	if len(ids) == 0 {
		return Thread{Unavailable: "runtime_unavailable"}
	}
	metadata := map[string]Thread{}
	for _, id := range ids {
		var response struct {
			Thread Thread `json:"thread"`
		}
		if r.call(ctx, "thread/read", map[string]any{"threadId": id.ID, "includeTurns": false}, &response) != nil || response.Thread.ID != id.ID {
			return Thread{Unavailable: "runtime_metadata_conflict"}
		}
		metadata[id.ID] = response.Thread
	}
	primary := primaryThread(ids, metadata)
	if primary.Unavailable == "runtime_ambiguous" {
		primary.HistoryCandidates = r.ambiguousHistoryCandidates(ctx, ids, metadata)
	}
	return primary
}

// Only explicit delegation relationships exclude a thread. Names, activity,
// direct-input capability and missing parent metadata never select a root.
func (t Thread) ParentID() string {
	if t.ParentThreadID != "" {
		return t.ParentThreadID
	}
	var source map[string]json.RawMessage
	if json.Unmarshal(t.Source, &source) != nil {
		return ""
	}
	for _, key := range []string{"subAgent", "subagent", "sub_agent"} {
		var child struct {
			Spawn struct {
				Parent string `json:"parent_thread_id"`
			} `json:"thread_spawn"`
		}
		if json.Unmarshal(source[key], &child) == nil && child.Spawn.Parent != "" {
			return child.Spawn.Parent
		}
	}
	return ""
}
func (t Thread) Delegated() bool {
	if t.ParentID() != "" {
		return true
	}
	var source string
	if json.Unmarshal(t.Source, &source) == nil {
		return source == "subagent" || source == "subAgent" || source == "sub_agent"
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(t.Source, &fields) == nil {
		for key := range fields {
			if key == "subagent" || key == "subAgent" || key == "sub_agent" {
				return true
			}
		}
	}
	return false
}

func primaryThread(ids []association, metadata map[string]Thread) Thread {
	unavailable := func(reason string) Thread { return Thread{Unavailable: reason} }
	var root Thread
	connected := false
	for _, id := range ids {
		t, ok := metadata[id.ID]
		if !ok {
			return unavailable("runtime_metadata_conflict")
		}
		if t.Delegated() {
			continue
		}
		if root.ID != "" {
			return unavailable("runtime_ambiguous")
		}
		root = t
		connected = id.Connected
	}
	if root.ID == "" {
		return unavailable("primary_thread_unconfirmed")
	}
	// Every excluded candidate must have a complete, acyclic ancestry to this
	// root on the same endpoint. An unrelated or unclassified thread is ambiguous.
	for _, id := range ids {
		current := id.ID
		seen := map[string]bool{}
		for current != root.ID {
			t, ok := metadata[current]
			if !ok || seen[current] || !t.Delegated() {
				return unavailable("runtime_ambiguous")
			}
			seen[current] = true
			current = t.ParentID()
		}
	}
	if !connected {
		return unavailable("runtime_disconnected")
	}
	if root.CWD == "" || (root.SessionID != "" && root.SessionID != root.ID) {
		return unavailable("runtime_metadata_conflict")
	}
	return root
}

func (t Thread) AgentStatus() string {
	switch t.Status.Type {
	case "idle":
		return "idle"
	case "active":
		for _, flag := range t.Status.Flags {
			if flag == "waitingOnApproval" || flag == "waitingOnUserInput" {
				return "blocked"
			}
		}
		return "working"
	default:
		return ""
	}
}
