package gateway

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/nativeconn"
)

func (s *Server) startTCP(address string) error {
	if len(s.opts.RegistrationToken) < 32 {
		return errors.New("native TCP requires a registration token of at least 32 bytes")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	s.tcpListener = listener
	s.tcpSlots = make(chan struct{}, 64)
	s.tcpWG.Add(1)
	go func() {
		defer s.tcpWG.Done()
		for {
			conn, err := listener.Accept()
			if err != nil {
				if s.stopped() {
					return
				}
				s.logger.Warn("native TCP accept failed")
				return
			}
			if s.stopped() {
				_ = conn.Close()
				return
			}
			ip, _, _ := net.SplitHostPort(conn.RemoteAddr().String())
			if !s.connectLimiter.allow("tcp:"+ip, s.now()) {
				_ = conn.Close()
				continue
			}
			select {
			case s.tcpSlots <- struct{}{}:
			default:
				_ = conn.Close()
				continue
			}
			s.tcpConnections.Store(conn, struct{}{})
			s.tcpWG.Add(1)
			go func() {
				defer s.tcpWG.Done()
				defer func() { <-s.tcpSlots; s.tcpConnections.Delete(conn); _ = conn.Close() }()
				tcp, role, err := nativeconn.Accept(s.ctx, conn)
				if err != nil {
					return
				}
				defer tcp.CloseNow()
				switch role {
				case nativeconn.RoleRelay:
					if s.connectLimiter.allow("relay:"+ip, s.now()) {
						s.serveRelay(s.ctx, tcp)
					}
				case nativeconn.RolePhone:
					s.serveConnect(s.ctx, tcp, ip)
				case nativeconn.RoleProbe:
					ctx, cancel := context.WithTimeout(s.ctx, 3*time.Second)
					defer cancel()
					_ = writeJSON(ctx, tcp, map[string]any{"type": "tcp_health", "proto": 1, "status": "ok"})
				}
			}()
		}
	}()
	return nil
}

// TCPAddress is empty when the optional native listener is disabled.
func (s *Server) TCPAddress() string {
	if s.tcpListener == nil {
		return ""
	}
	return s.tcpListener.Addr().String()
}
