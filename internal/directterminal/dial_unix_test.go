//go:build linux || darwin

package directterminal

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"path/filepath"
	"testing"
	"time"
)

func TestDirectAttachRealSocket(t *testing.T) {
	path := filepath.Join(t.TempDir(), "herdr-client.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	result := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			result <- err
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		write := func(b []byte) error {
			h := binary.LittleEndian.AppendUint32(nil, uint32(len(b)))
			_, err := conn.Write(append(h, b...))
			return err
		}
		b, err := readPacket(conn)
		if err != nil {
			result <- err
			return
		}
		if !bytes.Equal(b, []byte{0, 22, 100, 30, 0, 0, 0}) {
			result <- io.ErrUnexpectedEOF
			return
		}
		if err = write([]byte{0, 22, 1, 0}); err != nil {
			result <- err
			return
		}
		b, err = readPacket(conn)
		if err != nil {
			result <- err
			return
		}
		if !bytes.Equal(b, []byte{5, 6, 't', 'e', 'r', 'm', '-', '1', 0}) {
			result <- io.ErrUnexpectedEOF
			return
		}
		if err = write(appendBytes([]byte{1, 1, 100, 30, 1}, []byte("\x1b[32mhello 世界"))); err != nil {
			result <- err
			return
		}
		b, err = readPacket(conn)
		if err != nil {
			result <- err
			return
		}
		if !bytes.Equal(b, appendBytes([]byte{1}, []byte("测试\r"))) {
			result <- io.ErrUnexpectedEOF
			return
		}
		b, err = readPacket(conn)
		if err != nil {
			result <- err
			return
		}
		if !bytes.Equal(b, geometry(3, 120, 40)) {
			result <- io.ErrUnexpectedEOF
			return
		}
		result <- nil
	}()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	s, err := Dial(ctx, path, "term-1", 100, 30)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	f, err := s.Read()
	if err != nil || !bytes.Contains(f.Data, []byte("世界")) {
		t.Fatal(f, err)
	}
	if err = s.Input([]byte("测试\r")); err != nil {
		t.Fatal(err)
	}
	if err = s.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	if err = <-result; err != nil {
		t.Fatal(err)
	}
}
