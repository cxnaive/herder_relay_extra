package directterminal

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
)

func TestProtocol22GoldenClientMessages(t *testing.T) {
	if got := geometry(0, 100, 30); !bytes.Equal(got, []byte{0, 22, 100, 30, 0, 0, 0}) {
		t.Fatalf("hello: %x", got)
	}
	if got := geometry(3, 300, 40); !bytes.Equal(got, []byte{3, 251, 44, 1, 40, 0, 0, 0}) {
		t.Fatalf("resize: %x", got)
	}
	l, r := net.Pipe()
	defer l.Close()
	defer r.Close()
	s := &Stream{conn: l}
	ch := make(chan error, 1)
	go func() { ch <- s.Input([]byte("\x03")) }()
	b, err := readPacket(r)
	if err != nil || !bytes.Equal(b, []byte{1, 1, 3}) {
		t.Fatal(b, err)
	}
	if err = <-ch; err != nil {
		t.Fatal(err)
	}
}
func TestServerANSIFrameAndReplay(t *testing.T) {
	l, r := net.Pipe()
	defer l.Close()
	defer r.Close()
	// ServerMessage::Terminal, seq=1, 80x24, full=true, bytes="hello".
	payload := []byte{1, 1, 80, 24, 1, 5, 'h', 'e', 'l', 'l', 'o'}
	packet := binary.LittleEndian.AppendUint32(nil, uint32(len(payload)))
	packet = append(packet, payload...)
	go func() {
		defer r.Close()
		for i := 0; i < 2; i++ {
			for _, b := range packet {
				if _, err := r.Write([]byte{b}); err != nil {
					return
				}
			}
		}
	}()
	s := &Stream{conn: l}
	frame, err := s.Read()
	if err != nil || string(frame.Data) != "hello" || frame.Columns != 80 || frame.Rows != 24 {
		t.Fatal(frame, err)
	}
	if _, err = s.Read(); err == nil {
		t.Fatal("replay accepted")
	}
}
func TestRejectOversizedAndTruncatedPackets(t *testing.T) {
	b := binary.LittleEndian.AppendUint32(nil, maxFrame+1)
	if _, err := readPacket(bytes.NewReader(b)); err == nil {
		t.Fatal("oversize allocation allowed")
	}
	if _, err := readPacket(bytes.NewReader([]byte{4, 0, 0})); err != io.ErrUnexpectedEOF {
		t.Fatal(err)
	}
	d := decoder{b: []byte{253, 0}}
	d.number()
	if d.finish() == nil {
		t.Fatal("truncated varint accepted")
	}
	if got := SocketPath("/tmp/session/herdr.sock"); got != "/tmp/session/herdr-client.sock" && got != `\tmp\session\herdr-client.sock` {
		t.Fatal(got)
	}
}

func TestRejectIncompleteTerminalBaselineAndSequenceGaps(t *testing.T) {
	for _, payload := range [][]byte{
		{1, 1, 80, 24, 0, 1, 'x'}, // initial delta without a full baseline
		{1, 3, 80, 24, 1, 1, 'x'}, // skipped render sequence
	} {
		left, right := net.Pipe()
		go func() {
			defer right.Close()
			packet := binary.LittleEndian.AppendUint32(nil, uint32(len(payload)))
			right.Write(append(packet, payload...))
		}()
		s := &Stream{conn: left}
		if payload[1] == 3 {
			s.previous, s.seen = 1, true
		}
		_, err := s.Read()
		left.Close()
		if err == nil {
			t.Fatal("incomplete terminal render accepted")
		}
	}
}
