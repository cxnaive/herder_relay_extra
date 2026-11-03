package app

import (
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

func TestDirectoryListRequiresExactIdentityAndReadOnlyAuthorization(t *testing.T) {
	action, ok := protocol.ClassifyAction("workspace_list")
	if !ok || action.Class != protocol.ActionReadOnly || action.Audited {
		t.Fatal("directory list must be read-only")
	}
	for _, role := range []string{"reader", "controller"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") != nil {
			t.Fatalf("%s cannot list directories", role)
		}
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{}, false, action, "") == nil {
		t.Fatal("anonymous directory access")
	}
	for _, inbound := range []protocol.Inbound{
		{Protocol: 2, PaneID: "p", Target: &protocol.TargetRef{PaneID: "p"}},
		{Protocol: protocol.Version, PaneID: "p"},
		{Protocol: protocol.Version, Target: &protocol.TargetRef{PaneID: "p"}},
	} {
		if validDirectoryEnvelope(inbound) {
			t.Fatal("missing identity or legacy protocol accepted")
		}
	}
}
