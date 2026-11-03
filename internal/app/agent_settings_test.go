package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"testing"
)

func TestAgentSettingsRequiresAuthenticatedControllerForWrites(t *testing.T) {
	read, ok := protocol.ClassifyAction("get_agent_settings")
	if !ok || read.Class != protocol.ActionReadOnly {
		t.Fatal("read classification")
	}
	write, ok := protocol.ClassifyAction("set_agent_settings")
	if !ok || write.Class != protocol.ActionMutating || !write.Audited || !write.RequiresProtocol {
		t.Fatal("write classification")
	}
	for _, action := range []protocol.ActionMetadata{read, write} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{}, false, action, "") == nil {
			t.Fatal("anonymous settings access")
		}
	}
	reader := transport.AuthenticatedIdentity{Role: "reader"}
	controller := transport.AuthenticatedIdentity{Role: "controller"}
	if authorizeAuthenticatedIdentity(reader, true, read, "") != nil || authorizeAuthenticatedIdentity(reader, true, write, "") == nil || authorizeAuthenticatedIdentity(controller, true, write, "") != nil {
		t.Fatal("role boundary")
	}
}
