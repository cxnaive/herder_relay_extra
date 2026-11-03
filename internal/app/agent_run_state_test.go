package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"testing"
)

func TestRunStateIsAuthenticatedReadOnlyForBothDeviceRoles(t *testing.T) {
	action, ok := protocol.ClassifyAction("get_agent_run_state")
	if !ok || action.Class != protocol.ActionReadOnly || action.Audited || action.Coordinated {
		t.Fatal("status is not a lightweight read")
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{}, false, action, "") == nil {
		t.Fatal("anonymous status permitted")
	}
	for _, role := range []string{"reader", "controller"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") != nil {
			t.Fatal("authenticated device cannot read status")
		}
	}
}
