package app

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

type codexOSProcess struct {
	pid, parent, group, session, tty, foreground int
	executable, cwd, birth                       string
	argv                                         []string
}

func codexRuntimeForegroundProcess(info *herdr.PaneProcessInfo, pane string) (herdr.PaneProcess, error) {
	return codexForegroundGroup(info, pane)
}

func codexForegroundGroup(info *herdr.PaneProcessInfo, pane string) (herdr.PaneProcess, error) {
	return codexForegroundGroupWith(info, pane, readCodexOSProcess)
}

// Find the outer native Codex, not a helper or a delegated Codex descendant.
func codexForegroundGroupWith(info *herdr.PaneProcessInfo, pane string, read func(int) (codexOSProcess, error)) (herdr.PaneProcess, error) {
	bad := func() (herdr.PaneProcess, error) { return herdr.PaneProcess{}, errCodexProcessStart }
	if info == nil || info.PaneID != pane || pane == "" || info.ShellPID <= 0 || info.ForegroundProcessGroupID <= 0 || info.ForegroundProcessGroupID == info.ShellPID || len(info.ForegroundProcesses) == 0 || len(info.ForegroundProcesses) > 64 {
		return bad()
	}
	shell, err := read(info.ShellPID)
	if err != nil || shell.pid != info.ShellPID || shell.tty == 0 || shell.session <= 0 || shell.foreground != info.ForegroundProcessGroupID {
		return bad()
	}
	processes := map[int]codexOSProcess{}
	seen := map[int]bool{}
	for _, reported := range info.ForegroundProcesses {
		if reported.PID <= 0 || seen[reported.PID] {
			return bad()
		}
		seen[reported.PID] = true
		p, err := read(reported.PID)
		// A tool may exit between Herdr's inventory and /proc. It cannot still own a socket.
		if os.IsNotExist(err) && reported.PID != info.ForegroundProcessGroupID {
			continue
		}
		if err != nil || p.pid != reported.PID || p.group != info.ForegroundProcessGroupID || p.tty != shell.tty || p.session != shell.session || p.foreground != shell.foreground {
			return bad()
		}
		processes[p.pid] = p
	}
	leader := processes[info.ForegroundProcessGroupID]
	if leader.pid == 0 || leader.parent != shell.pid {
		return bad()
	}
	isNative := func(p codexOSProcess) bool {
		return filepath.Base(p.executable) == "codex" && len(p.argv) > 0 && filepath.Base(p.argv[0]) == "codex"
	}
	ancestor := func(child, root int) bool {
		seen := map[int]bool{}
		for child != root {
			if child == 0 || seen[child] {
				return false
			}
			seen[child] = true
			child = processes[child].parent
		}
		return true
	}
	selected := codexOSProcess{}
	for _, p := range processes {
		if !ancestor(p.pid, leader.pid) {
			return bad()
		}
		if !isNative(p) {
			continue
		}
		nested := false
		for parent := p.parent; parent != shell.pid && parent != 0; parent = processes[parent].parent {
			if isNative(processes[parent]) {
				nested = true
				break
			}
		}
		if nested {
			continue
		}
		if selected.pid != 0 || !filepath.IsAbs(p.cwd) {
			return bad()
		}
		selected = p
	}
	if selected.pid == 0 {
		return bad()
	}
	if leader.pid != selected.pid {
		if filepath.Base(leader.executable) != "node" || len(leader.argv) < 2 || leader.cwd != selected.cwd {
			return bad()
		}
		script := filepath.Base(leader.argv[1])
		if script != "codex" && (script != "codex.js" || !strings.Contains(filepath.ToSlash(leader.argv[1]), "/@openai/codex/")) {
			return bad()
		}
	}
	for _, p := range processes {
		if p.pid != leader.pid && !ancestor(selected.pid, p.pid) && !ancestor(p.pid, selected.pid) {
			return bad()
		}
	}
	after, err := read(selected.pid)
	if err != nil || after.birth != selected.birth || after.parent != selected.parent || after.group != selected.group || after.tty != selected.tty || after.executable != selected.executable {
		return bad()
	}
	return herdr.PaneProcess{PID: selected.pid, Name: "codex", Cwd: selected.cwd, Argv: selected.argv, Argv0: selected.argv[0]}, nil
}

func readCodexOSProcess(pid int) (codexOSProcess, error) {
	base := fmt.Sprintf("/proc/%d", pid)
	info, err := os.Stat(base)
	if err != nil {
		return codexOSProcess{}, err
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != uint32(os.Geteuid()) {
		return codexOSProcess{}, errCodexProcessStart
	}
	own, err := os.Readlink("/proc/self/ns/pid")
	if err != nil {
		return codexOSProcess{}, err
	}
	other, err := os.Readlink(base + "/ns/pid")
	if err != nil {
		return codexOSProcess{}, err
	}
	if other != own {
		return codexOSProcess{}, errCodexProcessStart
	}
	raw, err := os.ReadFile(base + "/stat")
	if err != nil {
		return codexOSProcess{}, err
	}
	end := strings.LastIndexByte(string(raw), ')')
	if end < 0 || len(raw) > 4096 {
		return codexOSProcess{}, errCodexProcessStart
	}
	f := strings.Fields(string(raw[end+1:]))
	if len(f) < 20 || f[0] == "X" {
		return codexOSProcess{}, errCodexProcessStart
	}
	n := make([]int, 5)
	for i := range n {
		n[i], err = strconv.Atoi(f[i+1])
		if err != nil {
			return codexOSProcess{}, errCodexProcessStart
		}
	}
	p := codexOSProcess{pid: pid, parent: n[0], group: n[1], session: n[2], tty: n[3], foreground: n[4], birth: f[19]}
	if f[0] == "Z" {
		return p, nil
	}
	p.executable, err = os.Readlink(base + "/exe")
	if err != nil {
		return p, err
	}
	p.executable = strings.TrimSuffix(p.executable, " (deleted)")
	p.cwd, err = os.Readlink(base + "/cwd")
	if err != nil {
		return p, err
	}
	argv, err := os.ReadFile(base + "/cmdline")
	if err != nil || len(argv) > 64*1024 {
		return p, errCodexProcessStart
	}
	p.argv = strings.Split(strings.TrimSuffix(string(argv), "\x00"), "\x00")
	after, err := os.ReadFile(base + "/stat")
	if err != nil {
		return p, err
	}
	end = strings.LastIndexByte(string(after), ')')
	if end < 0 {
		return p, errCodexProcessStart
	}
	af := strings.Fields(string(after[end+1:]))
	if len(af) < 20 || af[19] != f[19] || strings.Join(af[1:6], ":") != strings.Join(f[1:6], ":") {
		return p, errCodexProcessStart
	}
	return p, nil
}

// Only this allowlisted environment key is consumed; environment values are never logged.
func codexProcessHome(pid int, home string) (string, error) {
	raw, err := os.ReadFile(fmt.Sprintf("/proc/%d/environ", pid))
	if err != nil || len(raw) > 2*1024*1024 {
		return "", errCodexProcessStart
	}
	result := filepath.Join(home, ".codex")
	for _, entry := range bytes.Split(raw, []byte{0}) {
		if value, ok := strings.CutPrefix(string(entry), "CODEX_HOME="); ok && value != "" {
			result = value
		}
	}
	if !filepath.IsAbs(result) {
		return "", errCodexProcessStart
	}
	return filepath.EvalSymlinks(result)
}

func codexProcessProofSupported() bool { return true }
