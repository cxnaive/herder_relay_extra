package app

import (
	"errors"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

func (s *Server) filesystemReadAllowed(client *transport.ClientConn) bool {
	identity, ok := client.Identity()
	return s.filesystemReadAllowedFor(identity, ok)
}

func (s *Server) filesystemReadAllowedFor(identity transport.AuthenticatedIdentity, authenticated bool) bool {
	if !authenticated || s.deviceAuth == nil {
		return false
	}
	credential, ok := s.deviceAuth.AuthorizeCredential(identity.CredentialID, identity.CredentialVersion)
	return ok && credential.DeviceID == identity.DeviceID && credential.Role == "controller" && credential.FilesystemRead
}

func (s *Server) fileReadRoot(client *transport.ClientConn, inbound protocol.Inbound, cwd string) (string, error) {
	return fileReadRootFor(cwd, inbound.FileScope, s.filesystemReadAllowed(client))
}

func fileReadRootFor(cwd, scope string, allowed bool) (string, error) {
	switch scope {
	case "", "workspace":
		return cwd, nil
	case "filesystem":
		if allowed {
			return "/", nil
		}
		return "", errors.New("Outside-workspace file access is disabled for this device")
	default:
		return "", errors.New("Invalid file access scope")
	}
}

func (s *Server) handleFileAccess(client *transport.ClientConn, inbound protocol.Inbound, message map[string]any) {
	respond := func(ok bool, phase, problem string, data any) {
		if inbound.Type == "file_access_set" {
			s.sendAuditedCommandResult(client, message, inbound.RequestID, inbound.Type, ok, phase, problem, "", data)
		} else {
			s.sendCommandResult(client, inbound.RequestID, inbound.Type, ok, phase, problem, "", data)
		}
	}
	fail := func(problem string) { respond(false, "failed", problem, nil) }
	identity, ok := client.Identity()
	if !ok || s.deviceAuth == nil || inbound.Protocol != protocol.Version {
		fail("Authenticated protocol v3 device required")
		return
	}
	if inbound.Type == "file_access_set" {
		if inbound.FilesystemRead == nil {
			fail("Explicit file access setting required")
			return
		}
		if err := s.deviceAuth.SetFilesystemRead(identity.CredentialID, identity.CredentialVersion, *inbound.FilesystemRead); err != nil {
			fail("Only the current controller device may change its own file access")
			return
		}
	}
	respond(true, "completed", "", map[string]any{"filesystem_read": s.filesystemReadAllowed(client)})
}
