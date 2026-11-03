package app

import (
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestCodexRuntimeListenerRequiresOwnedLoopbackListeningSocket(t *testing.T) {
	root := t.TempDir()
	base := filepath.Join(root, "20")
	if err := os.MkdirAll(filepath.Join(base, "fd"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(base, "net"), 0700); err != nil {
		t.Fatal(err)
	}
	for fd, link := range map[string]string{"1": "/dev/pts/10", "39": "socket:[111]", "40": "socket:[222]", "41": "socket:[333]"} {
		if err := os.Symlink(link, filepath.Join(base, "fd", fd)); err != nil {
			t.Fatal(err)
		}
	}
	// Own 111 is listening on loopback; 222 is connected, 333 binds all interfaces,
	// and 444 belongs to another process in the same network namespace.
	data := "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt inode\n" +
		"0: 0100007F:B48F 00000000:0000 0A 0 0 0 0 0 111\n" +
		"1: 0100007F:B490 00000000:0000 01 0 0 0 0 0 222\n" +
		"2: 00000000:B491 00000000:0000 0A 0 0 0 0 0 333\n" +
		"3: 0100007F:B492 00000000:0000 0A 0 0 0 0 0 444\n"
	if err := os.WriteFile(filepath.Join(base, "net", "tcp"), []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	got, err := codexListenersAt(root, 20)
	if err != nil || len(got) != 1 || got["http://127.0.0.1:46223"] != "111" {
		t.Fatalf("unsafe ownership: %v %v", got, err)
	}
	if err := os.Remove(filepath.Join(base, "fd", "39")); err != nil {
		t.Fatal(err)
	}
	got, err = codexListenersAt(root, 20)
	if err != nil || len(got) != 0 {
		t.Fatalf("released socket accepted: %v %v", got, err)
	}
}

func TestCodexRuntimeListenerFindsActualProcessSocket(t *testing.T) {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	got, err := codexProcessListeners(os.Getpid())
	if err != nil || got["http://"+l.Addr().String()] == "" {
		t.Fatalf("missing actual listener: %v %v", got, err)
	}
}
