package app

import (
	"errors"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

func TestCodexForegroundGroupSelectsNativeChildNotNodeOrWSLHelper(t *testing.T) {
	info := &herdr.PaneProcessInfo{PaneID: "pane", ShellPID: 10, ForegroundProcessGroupID: 20,
		ForegroundProcesses: []herdr.PaneProcess{{PID: 20}, {PID: 21}, {PID: 22}}}
	base := map[int]codexOSProcess{
		20: {pid: 20, parent: 10, group: 20, executable: "/usr/bin/node", cwd: "/project", argv: []string{"node", "/npm/@openai/codex/bin/codex.js"}},
		21: {pid: 21, parent: 20, group: 20, executable: "/npm/vendor/codex", cwd: "/project", argv: []string{"/npm/vendor/codex", "resume", "unused"}},
		22: {pid: 22, parent: 21, group: 20, executable: "/init", cwd: "/project", argv: []string{"/init", "powershell.exe"}},
	}
	read := func(pid int) (codexOSProcess, error) {
		if pid == info.ShellPID {
			return codexTestProcess(info, pid, codexOSProcess{}), nil
		}
		p, ok := base[pid]
		if !ok {
			return p, errors.New("missing")
		}
		return codexTestProcess(info, pid, p), nil
	}
	got, err := codexForegroundGroupWith(info, "pane", read)
	if err != nil || got.PID != 21 || got.Cwd != "/project" {
		t.Fatalf("native child not selected: %#v %v", got, err)
	}
	launcher := base[20]
	launcher.argv = []string{"node", "/home/test/.local/bin/codex"}
	base[20] = launcher
	base[22] = codexOSProcess{pid: 22, parent: 21, group: 20}
	if got, err := codexForegroundGroupWith(info, "pane", read); err != nil || got.PID != 21 {
		t.Fatal("npm symlink launcher with unreaped helper rejected", err)
	}
	mutations := map[string]func(map[int]codexOSProcess){
		"two native agents": func(m map[int]codexOSProcess) {
			p := m[22]
			p.parent = 20
			p.executable = "/bin/codex"
			p.argv = []string{"codex"}
			p.cwd = "/project"
			m[22] = p
		},
		"unrelated process group":  func(m map[int]codexOSProcess) { p := m[21]; p.group = 99; m[21] = p },
		"detached candidate":       func(m map[int]codexOSProcess) { p := m[21]; p.parent = 999; m[21] = p },
		"reused helper PID":        func(m map[int]codexOSProcess) { p := m[22]; p.pid = 23; m[22] = p },
		"unrelated node script":    func(m map[int]codexOSProcess) { p := m[20]; p.argv = []string{"node", "/another/codex.js"}; m[20] = p },
		"launcher project changed": func(m map[int]codexOSProcess) { p := m[20]; p.cwd = "/other"; m[20] = p },
		"parent cycle":             func(m map[int]codexOSProcess) { p := m[21]; p.parent = 22; m[21] = p },
		"unrelated helper":         func(m map[int]codexOSProcess) { p := m[22]; p.parent = 10; m[22] = p },
		"missing native process":   func(m map[int]codexOSProcess) { delete(m, 21) },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			copy := map[int]codexOSProcess{}
			for id, p := range base {
				copy[id] = p
			}
			mutate(copy)
			reader := func(id int) (codexOSProcess, error) {
				if id == info.ShellPID {
					return codexTestProcess(info, id, codexOSProcess{}), nil
				}
				p, ok := copy[id]
				if !ok {
					return p, errors.New("missing")
				}
				return codexTestProcess(info, id, p), nil
			}
			if _, err := codexForegroundGroupWith(info, "pane", reader); err == nil {
				t.Fatal("ambiguous ownership accepted")
			}
		})
	}
	if _, err := codexForegroundGroupWith(info, "other", read); err == nil {
		t.Fatal("wrong pane accepted")
	}
	info.ForegroundProcesses = append(info.ForegroundProcesses, info.ForegroundProcesses[0])
	if _, err := codexForegroundGroupWith(info, "pane", read); err == nil {
		t.Fatal("duplicate PID accepted")
	}
}

func TestCodexForegroundGroupAllowsNativeLeaderWithOwnedHelper(t *testing.T) {
	info := &herdr.PaneProcessInfo{PaneID: "pane", ShellPID: 10, ForegroundProcessGroupID: 21, ForegroundProcesses: []herdr.PaneProcess{{PID: 21}, {PID: 22}}}
	read := func(pid int) (codexOSProcess, error) {
		if pid == 10 {
			return codexTestProcess(info, pid, codexOSProcess{}), nil
		}
		if pid == 21 {
			return codexTestProcess(info, pid, codexOSProcess{pid: 21, parent: 10, group: 21, executable: "/bin/codex", cwd: "/project", argv: []string{"codex"}}), nil
		}
		return codexTestProcess(info, pid, codexOSProcess{pid: 22, parent: 21, group: 21, executable: "/init", cwd: "/project", argv: []string{"/init"}}), nil
	}
	if process, err := codexForegroundGroupWith(info, "pane", read); err != nil || process.PID != 21 {
		t.Fatal("native foreground leader rejected", err)
	}
}

func TestCodexNativeWriterRetainsIdentityAfterChromeChildBecomesZombie(t *testing.T) {
	info := &herdr.PaneProcessInfo{PaneID: "writer", ShellPID: 10, ForegroundProcessGroupID: 21, ForegroundProcesses: []herdr.PaneProcess{{PID: 21, Name: "codex"}, {PID: 22, Name: "chrome"}}}
	read := func(pid int) (codexOSProcess, error) {
		if pid == 10 {
			return codexTestProcess(info, pid, codexOSProcess{}), nil
		}
		if pid == 21 {
			return codexTestProcess(info, pid, codexOSProcess{pid: 21, parent: 10, group: 21, executable: "/bin/codex", cwd: "/project", argv: []string{"codex"}}), nil
		}
		return codexTestProcess(info, pid, codexOSProcess{pid: 22, parent: 21, group: 21}), nil
	}
	process, err := codexForegroundGroupWith(info, "writer", read)
	if err != nil || process.PID != 21 {
		t.Fatal("unreaped browser helper hid the live writer", err)
	}
}

func codexTestProcess(info *herdr.PaneProcessInfo, pid int, p codexOSProcess) codexOSProcess {
	if pid == info.ShellPID {
		p.pid = pid
		p.group = pid
	}
	p.session = info.ShellPID
	p.tty = 1
	p.foreground = info.ForegroundProcessGroupID
	p.birth = "100"
	return p
}

func TestCodexForegroundIgnoresNestedCodexButRejectsOtherTerminal(t *testing.T) {
	info := &herdr.PaneProcessInfo{PaneID: "p", ShellPID: 10, ForegroundProcessGroupID: 20, ForegroundProcesses: []herdr.PaneProcess{{PID: 20}, {PID: 21}, {PID: 22}}}
	read := func(pid int) (codexOSProcess, error) {
		p := codexOSProcess{pid: pid, parent: 10, group: 20, executable: "/bin/codex", cwd: "/project", argv: []string{"codex"}}
		if pid == 21 {
			p.parent = 20
		}
		if pid == 22 {
			p.parent = 21
		}
		return codexTestProcess(info, pid, p), nil
	}
	root, err := codexForegroundGroupWith(info, "p", read)
	if err != nil || root.PID != 20 {
		t.Fatal("delegated Codex displaced the root", err)
	}
	for _, field := range []string{"tty", "session", "birth", "parent"} {
		t.Run(field, func(t *testing.T) {
			calls := 0
			bad := func(pid int) (codexOSProcess, error) {
				p, e := read(pid)
				if pid == 20 {
					calls++
					switch field {
					case "tty":
						p.tty = 2
					case "session":
						p.session = 99
					case "parent":
						p.parent = 99
					case "birth":
						if calls > 1 {
							p.birth = "200"
						}
					}
				}
				return p, e
			}
			if _, err := codexForegroundGroupWith(info, "p", bad); err == nil {
				t.Fatal("changed kernel identity accepted")
			}
		})
	}
}
