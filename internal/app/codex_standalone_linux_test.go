package app

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"

	"golang.org/x/sys/unix"
)

func TestCodexStandaloneKernelWriterOwnership(t *testing.T) {
	home := t.TempDir()
	root := filepath.Join(home, "sessions")
	dir := filepath.Join(home, "thread-writer-locks")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, reportID+".lock")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { file.Close() })
	pid := os.Getpid()
	if got, err := codexProcessWriters(pid, root); err == nil {
		t.Fatalf("open but unlocked file treated as writer: %v", got)
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		t.Fatal(err)
	}
	got, err := codexProcessWriters(pid, root)
	if err != nil || len(got) != 1 || got[reportID] == "" {
		t.Fatalf("own exclusive writer not found: %v %v", got, err)
	}
	// Mere stale files and the coordinator's open descriptor are not sessions.
	if err := os.WriteFile(filepath.Join(dir, otherReportID+".lock"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	coord, err := os.OpenFile(filepath.Join(dir, ".coordination.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer coord.Close()
	if got, err := codexProcessWriters(pid, root); err != nil || len(got) != 1 {
		t.Fatalf("stale file entered candidate set: %v %v", got, err)
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_UN); err != nil {
		t.Fatal(err)
	}
	if _, err := codexProcessWriters(pid, root); err == nil {
		t.Fatal("released ownership remained verified")
	}
}

func TestCodexStandaloneWriterSnapshotRejectsForeignAndRacingEvidence(t *testing.T) {
	for _, tc := range []string{"valid", "other_pid", "wrong_inode", "shared_lock", "posix_lock", "waiting_lock", "partial_range", "duplicate", "path_replaced", "deleted", "symlink", "foreign_store", "oversized"} {
		t.Run(tc, func(t *testing.T) {
			proc := t.TempDir()
			home := t.TempDir()
			dir := filepath.Join(home, "thread-writer-locks")
			os.MkdirAll(dir, 0700)
			path := filepath.Join(dir, reportID+".lock")
			os.WriteFile(path, nil, 0600)
			info, err := os.Stat(path)
			if err != nil {
				t.Fatal(err)
			}
			st := info.Sys().(*syscall.Stat_t)
			base := filepath.Join(proc, "42")
			os.MkdirAll(filepath.Join(base, "fd"), 0700)
			os.MkdirAll(filepath.Join(base, "fdinfo"), 0700)
			fdpath := filepath.Join(base, "fd", "8")
			raw := fmt.Sprintf("pos:\t0\nflags:\t02100002\nlock:\t1: FLOCK ADVISORY WRITE 42 %02x:%02x:%d 0 EOF\n", unix.Major(st.Dev), unix.Minor(st.Dev), st.Ino)
			switch tc {
			case "other_pid":
				raw = strings.ReplaceAll(raw, "WRITE 42", "WRITE 43")
			case "wrong_inode":
				raw = strings.ReplaceAll(raw, strconv.FormatUint(st.Ino, 10), "1")
			case "shared_lock":
				raw = strings.ReplaceAll(raw, "WRITE", "READ")
			case "posix_lock":
				raw = strings.ReplaceAll(raw, "FLOCK", "POSIX")
			case "waiting_lock":
				raw = strings.ReplaceAll(raw, "1: FLOCK", "1: -> FLOCK")
			case "partial_range":
				raw = strings.ReplaceAll(raw, "0 EOF", "0 1024")
			case "path_replaced":
				os.Rename(path, path+".old")
				os.WriteFile(path, nil, 0600)
			case "deleted":
				path += " (deleted)"
			case "symlink":
				os.Rename(path, path+".real")
				os.Symlink(path+".real", path)
			case "oversized":
				raw += strings.Repeat("x", 16385)
			}
			if err := os.Symlink(path, fdpath); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(base, "fdinfo", "8"), []byte(raw), 0600); err != nil {
				t.Fatal(err)
			}
			if tc == "duplicate" {
				os.Symlink(path, filepath.Join(base, "fd", "9"))
				os.WriteFile(filepath.Join(base, "fdinfo", "9"), []byte(raw), 0600)
			}
			root := filepath.Join(home, "sessions")
			if tc == "foreign_store" {
				home = t.TempDir()
				os.MkdirAll(filepath.Join(home, "thread-writer-locks"), 0700)
				root = filepath.Join(home, "sessions")
			}
			got, err := codexWritersAt(proc, 42, root)
			if tc == "valid" || tc == "duplicate" {
				if err != nil || len(got) != 1 || got[reportID] == "" {
					t.Fatalf("valid writer unavailable: %v %v", got, err)
				}
			} else if tc == "foreign_store" {
				if err != nil || len(got) != 0 {
					t.Fatalf("other store adopted: %v %v", got, err)
				}
			} else if err == nil {
				t.Fatalf("invalid evidence accepted: %v", got)
			}
		})
	}
}
