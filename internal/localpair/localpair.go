// Package localpair creates one-use invitations through the running relay's
// private Unix socket. It never opens the device store in a second process.
package localpair

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/deviceauth"
	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/0cv/herdr-mobile-relay/internal/nativeconn"
)

type request struct {
	Action string `json:"action"`
	Name   string `json:"name"`
}
type Response struct {
	Link      string    `json:"link,omitempty"`
	ExpiresAt time.Time `json:"expires_at,omitempty"`
	Error     string    `json:"error,omitempty"`
}

func socketPath(runtime string) string { return filepath.Join(runtime, "tcp-pair", "admin.sock") }

func InvitationLink(endpoint, token string, invitation deviceauth.Invitation) (string, error) {
	address, err := nativeconn.Endpoint(endpoint)
	if err != nil {
		return "", err
	}
	id, err := gatewaywire.DeriveRelayID(token)
	if err != nil {
		return "", err
	}
	key, err := gatewaywire.DeriveRendezvousKey(token)
	if err != nil {
		return "", err
	}
	values := url.Values{"setup": {invitation.Secret}, "invite": {invitation.InvitationID}, "invite_version": {strconv.FormatUint(invitation.Version, 10)}, "invite_expires": {strconv.FormatInt(invitation.ExpiresAt.UnixMilli(), 10)}, "relay_id": {id}, "rendezvous": {base64.RawURLEncoding.EncodeToString(key)}, "label": {invitation.Name}}
	return "herdr-tcp://" + address + "/#" + values.Encode(), nil
}

func Listen(runtime string, create func(string) (Response, error)) (func(), error) {
	path := socketPath(runtime)
	dir := filepath.Dir(path)
	if err := os.Mkdir(dir, 0700); err != nil && !os.IsExist(err) {
		return nil, err
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("TCP pairing directory must be a private directory (0700)")
	}
	if info, err := os.Lstat(path); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return nil, errors.New("refusing to replace non-socket pairing path")
		}
		conn, dialErr := net.DialTimeout("unix", path, time.Second)
		if dialErr == nil {
			_ = conn.Close()
			return nil, errors.New("pairing socket already active")
		}
		// A private socket with no listening process can survive an unclean exit.
		if !errors.Is(dialErr, os.ErrNotExist) {
			var op *net.OpError
			if !errors.As(dialErr, &op) || !errors.Is(op.Err, syscall.ECONNREFUSED) {
				return nil, dialErr
			}
		}
		if err := os.Remove(path); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	listener, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0600); err != nil {
		_ = listener.Close()
		return nil, err
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
				var req request
				dec := json.NewDecoder(io.LimitReader(conn, 4096))
				dec.DisallowUnknownFields()
				if dec.Decode(&req) != nil || req.Action != "create" {
					return
				}
				response, err := create(req.Name)
				if err != nil {
					response = Response{Error: "Unable to create invitation"}
				}
				_ = json.NewEncoder(conn).Encode(response)
			}()
		}
	}()
	var once sync.Once
	return func() { once.Do(func() { _ = listener.Close(); <-done }) }, nil
}

func Create(ctx context.Context, runtime, name string) (Response, error) {
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", socketPath(runtime))
	if err != nil {
		return Response{}, fmt.Errorf("connect to running TCP relay: %w", err)
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if err := json.NewEncoder(conn).Encode(request{Action: "create", Name: name}); err != nil {
		return Response{}, err
	}
	var response Response
	if err := json.NewDecoder(io.LimitReader(conn, 16384)).Decode(&response); err != nil {
		return Response{}, err
	}
	if response.Error != "" {
		return Response{}, errors.New(response.Error)
	}
	if response.Link == "" {
		return Response{}, errors.New("empty invitation")
	}
	return response, nil
}
