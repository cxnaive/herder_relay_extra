package app

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

func codexProcessWriters(pid int, root string) (map[string]string, error) {
	return codexWritersAt("/proc", pid, root)
}

// Read kernel-owned flock records, not lock-file existence or another process's
// open transcripts. Never open, take, release or remove a Codex writer lock.
func codexWritersAt(proc string, pid int, root string) (map[string]string, error) {
	if pid <= 0 || !filepath.IsAbs(root) {
		return nil, errCodexProcessStart
	}
	base := filepath.Join(proc, strconv.Itoa(pid))
	fds, err := os.ReadDir(filepath.Join(base, "fd"))
	if err != nil || len(fds) > 4096 {
		return nil, errCodexProcessStart
	}
	directory := filepath.Join(filepath.Dir(root), "thread-writer-locks")
	canonical, err := filepath.EvalSymlinks(directory)
	if err != nil || canonical != directory {
		return nil, errCodexProcessStart
	}
	result := map[string]string{}
	for _, entry := range fds {
		fd := filepath.Join(base, "fd", entry.Name())
		path, err := os.Readlink(fd)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, errCodexProcessStart
		}
		if filepath.Dir(path) == directory && strings.HasSuffix(path, ".lock (deleted)") {
			return nil, errCodexProcessStart
		}
		if filepath.Dir(path) != directory || filepath.Ext(path) != ".lock" {
			continue
		}
		id := strings.TrimSuffix(filepath.Base(path), ".lock")
		if id == ".coordination" {
			continue
		}
		if !codexBindingID.MatchString(id) {
			return nil, errCodexProcessStart
		}
		info, err := os.Stat(fd)
		if err != nil || !info.Mode().IsRegular() {
			return nil, errCodexProcessStart
		}
		st, ok := info.Sys().(*syscall.Stat_t)
		if !ok || st.Uid != uint32(os.Geteuid()) {
			return nil, errCodexProcessStart
		}
		named, err := os.Lstat(path)
		if err != nil || !named.Mode().IsRegular() || !os.SameFile(info, named) {
			return nil, errCodexProcessStart
		}
		raw, err := os.ReadFile(filepath.Join(base, "fdinfo", entry.Name()))
		if err != nil || len(raw) > 16*1024 {
			return nil, errCodexProcessStart
		}
		device := fmt.Sprintf("%x:%x:%d", unix.Major(st.Dev), unix.Minor(st.Dev), st.Ino)
		locked := false
		for _, line := range strings.Split(string(raw), "\n") {
			f := strings.Fields(line)
			if len(f) != 9 || f[0] != "lock:" || f[2] != "FLOCK" || f[3] != "ADVISORY" || f[4] != "WRITE" || f[5] != strconv.Itoa(pid) || f[7] != "0" || f[8] != "EOF" {
				continue
			}
			parts := strings.Split(f[6], ":")
			if len(parts) != 3 {
				continue
			}
			major, e1 := strconv.ParseUint(parts[0], 16, 32)
			minor, e2 := strconv.ParseUint(parts[1], 16, 32)
			inode, e3 := strconv.ParseUint(parts[2], 10, 64)
			locked = e1 == nil && e2 == nil && e3 == nil && device == fmt.Sprintf("%x:%x:%d", major, minor, inode)
			if locked {
				break
			}
		}
		if !locked {
			// Could be a writer in acquisition/teardown. Ignoring it could turn
			// an ambiguous set into a false unique primary.
			return nil, errCodexProcessStart
		}
		again, err := os.Stat(fd)
		if err != nil || !os.SameFile(info, again) {
			return nil, errCodexProcessStart
		}
		result[strings.ToLower(id)] = device
		if len(result) > 64 {
			return nil, errCodexProcessStart
		}
	}
	return result, nil
}
