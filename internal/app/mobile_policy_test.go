package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"testing"
)

func TestMobileFileAuthorizationAndAudit(t *testing.T) {
	read, ok := protocol.ClassifyAction("workspace_file_read")
	if !ok || authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "reader"}, true, read, "") != nil {
		t.Fatal("reader cannot download")
	}
	write, ok := protocol.ClassifyAction("workspace_file_write")
	if !ok || !write.Audited || !write.RequiresProtocol {
		t.Fatal("write lacks mutation policy")
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "reader"}, true, write, "") == nil {
		t.Fatal("reader can write")
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "controller"}, true, write, "") != nil {
		t.Fatal("controller cannot write")
	}
	if protocol.Compatible(protocol.Inbound{Type: "workspace_file_write", Protocol: 2}) {
		t.Fatal("wrong protocol accepted")
	}
	details := auditWriteDetails(map[string]any{"type": "workspace_file_write", "path": "a.txt", "text": "private file content"})
	if _, ok := details["text"]; ok {
		t.Fatal("audit exposed file content")
	}
}
