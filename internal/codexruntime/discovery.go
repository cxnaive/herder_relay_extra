package codexruntime

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

type Thread struct {
	ID             string          `json:"id"`
	Path           string          `json:"path"`
	CWD            string          `json:"cwd"`
	Name           string          `json:"name"`
	Originator     string          `json:"originator"`
	SessionID      string          `json:"sessionId"`
	ParentThreadID string          `json:"parentThreadId"`
	ForkedFromID   string          `json:"forkedFromId"`
	Source         json.RawMessage `json:"source"`
	Status         runtimeStatus   `json:"status"`
	Unavailable    string          `json:"-"`
	Candidates     []Thread        `json:"-"`
	Connected      bool            `json:"-"`
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

// Discover reads only local runtime metadata. Threads are grouped by their
// codex_tui HTTP origin; callers must prove that origin belongs to their TUI PID.
// Neither transcript availability nor creation origin participates in selection.
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
	if err = r.call(ctx, "initialize", map[string]any{"clientInfo": map[string]any{"name": "herdr_mobile_relay_identity", "version": "2"}, "capabilities": map[string]any{"experimentalApi": true}}, &initialized); err != nil {
		return nil, err
	}
	if err = wsjson.Write(ctx, conn, map[string]any{"method": "initialized"}); err != nil {
		return nil, err
	}
	before, err := r.associations(ctx)
	if err != nil {
		return nil, err
	}
	result := map[string]Thread{}
	for origin := range wanted {
		result[origin] = r.primaryForOrigin(ctx, before[origin])
	}
	after, err := r.associations(ctx)
	if err != nil {
		return nil, err
	}
	for origin := range wanted {
		current := r.primaryForOrigin(ctx, after[origin])
		if !sameRuntimeRoots(result[origin], current) {
			result[origin] = Thread{Unavailable: "runtime_changed"}
		} else {
			result[origin] = current
		}
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return result, nil
}

func (r *rpc) primaryForOrigin(ctx context.Context, ids []association) Thread {
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
	return primaryThread(ids, metadata)
}

// Every excluded subagent must reach a root in this same terminal association.
// A fork is an independent root, not a child. Broken/cyclic ancestry is not proof.
func primaryThread(ids []association, metadata map[string]Thread) Thread {
	bad := func(reason string) Thread { return Thread{Unavailable: reason} }
	if len(ids) == 0 {
		return bad("runtime_unavailable")
	}
	known := map[string]bool{}
	for _, id := range ids {
		t, ok := metadata[id.ID]
		if !ok || known[id.ID] || !threadID.MatchString(id.ID) || t.ID != id.ID {
			return bad("runtime_metadata_conflict")
		}
		known[id.ID] = true
	}
	roots := map[string]Thread{}
	for _, id := range ids {
		current := id.ID
		seen := map[string]bool{}
		for {
			t, ok := metadata[current]
			if !ok || !known[current] || seen[current] {
				return bad("runtime_ancestry_unavailable")
			}
			seen[current] = true
			if !t.Delegated() {
				if t.CWD == "" || (t.SessionID != "" && t.SessionID != t.ID) {
					return bad("runtime_metadata_conflict")
				}
				for member := range seen {
					if session := metadata[member].SessionID; session != "" && session != t.ID {
						return bad("runtime_metadata_conflict")
					}
				}
				roots[t.ID] = t
				break
			}
			parent := t.ParentID()
			if !threadID.MatchString(parent) {
				return bad("runtime_ancestry_unavailable")
			}
			// If both formats are present they must describe the same parent.
			sourceOnly := t
			sourceOnly.ParentThreadID = ""
			if sourceParent := sourceOnly.ParentID(); sourceParent != "" && sourceParent != parent {
				return bad("runtime_metadata_conflict")
			}
			current = parent
		}
	}
	candidates := make([]Thread, 0, len(roots))
	for _, id := range ids {
		if t, ok := roots[id.ID]; ok {
			t.Connected = id.Connected
			candidates = append(candidates, t)
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].ID < candidates[j].ID })
	if len(candidates) == 0 {
		return bad("primary_thread_unconfirmed")
	}
	if len(candidates) > 1 {
		return Thread{Unavailable: "runtime_ambiguous", Candidates: candidates}
	}
	root := candidates[0]
	root.Candidates = candidates
	if !root.Connected {
		root.ID = ""
		root.Unavailable = "runtime_disconnected"
	}
	return root
}

func sameRuntimeRoots(a, b Thread) bool {
	if a.Unavailable != b.Unavailable || len(a.Candidates) != len(b.Candidates) {
		return false
	}
	for i, x := range a.Candidates {
		y := b.Candidates[i]
		if x.ID != y.ID || x.CWD != y.CWD || x.SessionID != y.SessionID || x.Connected != y.Connected {
			return false
		}
	}
	return true
}

// PrimaryForOwnedThreads reduces metadata for threads whose exclusive writers
// belong to the already-verified standalone TUI. It performs no discovery itself.
func PrimaryForOwnedThreads(threads []Thread) Thread {
	ids := make([]association, 0, len(threads))
	metadata := make(map[string]Thread, len(threads))
	for _, t := range threads {
		ids = append(ids, association{ID: t.ID, Connected: true})
		metadata[t.ID] = t
	}
	return primaryThread(ids, metadata)
}
