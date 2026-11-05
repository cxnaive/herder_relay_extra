package profiles

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"strings"
	"testing"
)

func TestValidateLaunchArgs(t *testing.T) {
	for name, args := range map[string][]string{
		"absent":      nil,
		"empty":       {},
		"codex":       {"--no-daemon", "-c", `model="custom"`},
		"literal":     {"", "two words", "项目", "$(id)", ";", "'", `a\b`},
		"byte limit":  {strings.Repeat("x", MaxLaunchArgBytes)},
		"count limit": make([]string, MaxLaunchArgs),
	} {
		t.Run(name, func(t *testing.T) {
			if err := ValidateLaunchArgs(args); err != nil {
				t.Fatal(err)
			}
		})
	}
	for name, args := range map[string][]string{
		"too many":   make([]string, MaxLaunchArgs+1),
		"too large":  {strings.Repeat("x", MaxLaunchArgBytes), "x"},
		"utf8 bytes": {strings.Repeat("中", 2731)},
		"nul":        {"a\x00b"}, "newline": {"a\nb"}, "tab": {"a\tb"},
		"escape": {"a\x1bb"}, "unicode control": {"a\u0085b"}, "invalid utf8": {"\xff"},
	} {
		t.Run(name, func(t *testing.T) {
			if err := ValidateLaunchArgs(args); err == nil {
				t.Fatal("accepted invalid argv")
			}
		})
	}
}

func TestOwnedLaunchPersistsDefensiveCopyAndRejectsDifferentArguments(t *testing.T) {
	dir, path := ownershipFixture(t)
	r := NewResolver(dir, nil)
	if err := r.SetOwnershipPath(path); err != nil {
		t.Fatal(err)
	}
	target := PaneIdentity{PaneID: "pane", TerminalID: "terminal", TabID: "tab", WorkspaceID: "workspace"}
	want := []string{"--no-daemon", "two words", "", "项目", `model="custom"`}
	args := slices.Clone(want)
	if err := r.BeginLaunchOwnership(target, "wrapper", args...); err != nil {
		t.Fatal(err)
	}
	if err := r.ReconcileOwnership([]PaneIdentity{target}); err != nil {
		t.Fatal(err)
	}
	if profile, saved := r.ResolveOwnedLaunch("pane"); profile != "" || saved != nil {
		t.Fatal("pending launch became restartable")
	}
	if err := r.RememberVerified(target, "wrapper", args...); err != nil {
		t.Fatal(err)
	}
	args[0] = "changed"
	if !r.OwnsTarget(target, "wrapper", want...) || r.OwnsTarget(target, "wrapper") || r.OwnsTarget(target, "wrapper", args...) {
		t.Fatal("launch reconciliation ignored arguments or shared the caller slice")
	}
	for range 2 {
		profile, saved := r.ResolveOwnedLaunch("pane")
		if profile != "wrapper" || !slices.Equal(saved, want) {
			t.Fatalf("launch = %q, %q", profile, saved)
		}
		saved[0] = "changed"
	}
	r = NewResolver(dir, nil)
	if err := r.SetOwnershipPath(path); err != nil {
		t.Fatal(err)
	}
	if profile, _ := r.ResolveOwnedLaunch("pane"); profile != "" {
		t.Fatal("relaunch allowed before observing terminal")
	}
	if err := r.ReconcileOwnership([]PaneIdentity{target}); err != nil {
		t.Fatal(err)
	}
	if profile, saved := r.ResolveOwnedLaunch("pane"); profile != "wrapper" || !slices.Equal(saved, want) {
		t.Fatal("relay restart lost arguments")
	}
	target.TerminalID = "replaced"
	if err := r.ReconcileOwnership([]PaneIdentity{target}); err != nil {
		t.Fatal(err)
	}
	if profile, saved := r.ResolveOwnedLaunch("pane"); profile != "" || saved != nil {
		t.Fatal("arguments transferred to a replacement terminal")
	}
}

func TestOwnershipRejectsInvalidSavedArguments(t *testing.T) {
	dir, path := ownershipFixture(t)
	r := NewResolver(dir, nil)
	if err := r.SetOwnershipPath(path); err != nil {
		t.Fatal(err)
	}
	target := PaneIdentity{PaneID: "pane", TerminalID: "terminal"}
	if err := r.RememberVerified(target, "wrapper", "--no-daemon"); err != nil {
		t.Fatal(err)
	}
	if err := r.RememberVerified(target, "wrapper", "bad\narg"); err == nil {
		t.Fatal("persisted control bytes")
	}
	data, err := json.Marshal([]profileOwnership{{Target: target, ProfileID: "wrapper", AgentArgs: []string{"bad\narg"}}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err := r.SetOwnershipPath(path); err == nil {
		t.Fatal("loaded invalid saved arguments")
	}
	if profile, args := r.ResolveOwnedLaunch("pane"); profile != "" || args != nil {
		t.Fatal("corrupt store fell back to defaults")
	}
}

// JSON escaping can make the private store much larger than the argv budget.
// A failed save must leave a bounded, reloadable previous record set.
func TestLaunchArgumentsCannotMakeOwnershipStoreUnreadable(t *testing.T) {
	dir, path := ownershipFixture(t)
	r := NewResolver(dir, nil)
	if err := r.SetOwnershipPath(path); err != nil {
		t.Fatal(err)
	}
	arg := strings.Repeat("<", MaxLaunchArgBytes)
	var previous []byte
	for i := range 32 {
		target := PaneIdentity{PaneID: fmt.Sprintf("pane-%d", i), TerminalID: fmt.Sprintf("terminal-%d", i)}
		err := r.RememberVerified(target, "wrapper", arg)
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			t.Fatal(readErr)
		}
		if len(data) > 1024*1024 {
			t.Fatal("saved a store that cannot be loaded")
		}
		if err != nil {
			if string(data) != string(previous) || r.OwnsTarget(target, "wrapper", arg) {
				t.Fatal("failed save changed ownership")
			}
			reloaded := NewResolver(dir, nil)
			if err := reloaded.SetOwnershipPath(path); err != nil {
				t.Fatal(err)
			}
			if len(reloaded.ownership) != i {
				t.Fatal("lost previous launches")
			}
			return
		}
		previous = data
	}
	t.Fatal("test did not reach the ownership store size limit")
}
