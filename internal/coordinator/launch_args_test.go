package coordinator

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/profiles"
)

func TestDecodeAgentArgsRejectsMalformedRequests(t *testing.T) {
	for _, raw := range []any{nil, "--no-daemon", 1, map[string]any{}, []any{nil}, []any{true}, []any{1}, []any{"bad\narg"}, make([]string, 65)} {
		if _, err := decodeAgentArgs(map[string]any{"agent_args": raw}); err == nil {
			t.Fatalf("accepted %#v", raw)
		}
		// Refusal happens before any scheduling or Herdr access.
		d := &Dispatcher{logger: testLogger()}
		result := d.handleAgentStart(context.Background(), time.Now(), "start", map[string]any{"profile_id": "codex", "name": "writer", "cwd": "/work/project", "agent_args": raw})
		if result.OK {
			t.Fatal("invalid launch was accepted")
		}
	}
	for _, raw := range []any{[]string{"--no-daemon", ""}, []any{"--no-daemon", ""}} {
		args, err := decodeAgentArgs(map[string]any{"agent_args": raw})
		if err != nil || !slices.Equal(args, []string{"--no-daemon", ""}) {
			t.Fatalf("args = %q, %v", args, err)
		}
	}
	for _, message := range []map[string]any{{}, {"agent_args": []any{}}, {"agent_args": []string{}}} {
		if args, err := decodeAgentArgs(message); err != nil || args != nil {
			t.Fatalf("empty argv = %q, %v", args, err)
		}
	}
}

func TestValidateStartBoundsCombinedHermesAndExplicitArguments(t *testing.T) {
	l := hermesLifecycle(t, "apollo")
	for _, profile := range []string{"codex", "hermes"} {
		request := StartRequest{ProfileID: profile, Name: "writer", Cwd: filepath.Join(l.home, "project"), AgentArgs: []string{"--custom", "two words"}}
		if profile == "hermes" {
			request.HermesProfile = "apollo"
		}
		_, validated, err := l.ValidateStart(request)
		if err != nil {
			t.Fatal(err)
		}
		args, err := validated.launchArgs(profile)
		want := []string{"--custom", "two words"}
		if profile == "hermes" {
			want = append([]string{"-p", "apollo"}, want...)
		} else {
			want = append([]string{"-c", codexTerminalTitleDefault}, want...)
		}
		if err != nil || !slices.Equal(args, want) {
			t.Fatalf("args = %q, %v", args, err)
		}
		request.AgentArgs[0] = "changed"
		if validated.AgentArgs[0] != "--custom" {
			t.Fatal("validation retained mutable argv")
		}
		request.AgentArgs = make([]string, profiles.MaxLaunchArgs)
		_, _, err = l.ValidateStart(request)
		if err == nil {
			t.Fatalf("combined argv bound for %s: %v", profile, err)
		}
		request.AgentArgs = []string{"bad\narg"}
		if _, _, err := l.ValidateStart(request); err == nil {
			t.Fatal("control bytes accepted")
		}
	}
}

func TestStartInTargetPassesLiteralArgvAndDoesNotMutateProfile(t *testing.T) {
	for _, kind := range []string{"codex", "claude", "hermes", ""} {
		t.Run("kind="+kind, func(t *testing.T) {
			dir := t.TempDir()
			record := filepath.Join(dir, "argv")
			bin := writeScript(t, dir, "herdr", `#!/bin/sh
case "$1 $2" in
  'agent start'|'pane run') printf '%s\0' "$@" > '`+record+`'; printf '{"result":{"pane_id":"pane-new"}}\n' ;;
  'agent get') printf '{"result":{"running":true}}\n' ;;
  'agent rename') printf '{"result":{}}\n' ;;
  *) exit 2 ;;
esac
`)
			client := herdr.NewClient(bin, filepath.Join(dir, "sock"))
			t.Cleanup(func() { _ = client.Close() })
			l := &Lifecycle{herdr: client}
			profile := profiles.Profile{ID: "test", Kind: kind, Argv: make([]string, 2, 20)}
			copy(profile.Argv, []string{"custom-agent", "--existing"})
			request := StartRequest{Name: "writer", AgentArgs: []string{"--no-daemon", "two words", "", `$(id); echo unexpected`, `a'b`, "项目"}}
			if kind == "hermes" {
				request.HermesProfile = "apollo"
			}
			want, err := request.launchArgs(kind)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := l.startInTarget(ctx, profile, request, "pane-new"); err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(record)
			if err != nil {
				t.Fatal(err)
			}
			got := strings.Split(strings.TrimSuffix(string(data), "\x00"), "\x00")
			if kind != "" {
				i := slices.Index(got, "--")
				if i != 9 || !slices.Equal(got[i+1:], want) {
					t.Fatalf("agent-side argv was changed or crossed Herdr boundary: %q", got)
				}
			} else {
				command := herdr.ShellJoin(append([]string{"custom-agent", "--existing"}, want...))
				if !slices.Equal(got, []string{"pane", "run", "pane-new", command}) {
					t.Fatalf("custom command = %q", got)
				}
				if profile.Argv[:cap(profile.Argv)][2] != "" {
					t.Fatal("mutated profile backing array")
				}
			}
		})
	}
}

func TestAgentStartIdempotencyIncludesArguments(t *testing.T) {
	dir := t.TempDir()
	record := filepath.Join(dir, "calls")
	bin := recordingHerdr(t, dir, record, `{"result":{"pane_id":"pane-new"}}`)
	client := herdr.NewClient(bin, filepath.Join(dir, "sock"))
	d := NewDispatcher(client, NewState(testLogger()), nil, testLogger())
	t.Cleanup(func() { _ = d.Close(context.Background()); _ = client.Close() })
	message := map[string]any{"action": "agent_start", "request_id": "same-request", "profile_id": "codex", "name": "writer", "cwd": "/work/project", "agent_args": []any{"--no-daemon"}}
	for range 2 {
		if result := d.Handle(context.Background(), message); !result.OK {
			t.Fatalf("start: %+v", result)
		}
	}
	message["agent_args"] = []any{"--model", "other"}
	if result := d.Handle(context.Background(), message); result.OK {
		t.Fatal("reused request ID accepted different argv")
	}
	data, err := os.ReadFile(record)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(string(data), "agent start") != 1 || !strings.Contains(string(data), " -- -c "+codexTerminalTitleDefault+" --no-daemon") || strings.Contains(string(data), "agent prompt") {
		t.Fatalf("arguments were dropped, sent as prompt, or launch duplicated: %s", data)
	}
}
