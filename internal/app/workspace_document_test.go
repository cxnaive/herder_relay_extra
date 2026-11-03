package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"testing"
)

func TestDocumentReadIsReadOnlyAndProtocolFenced(t *testing.T) {
	action, ok := protocol.ClassifyAction("workspace_file_read")
	if !ok || action.Class != protocol.ActionReadOnly || action.RequiresProtocol || action.Audited {
		t.Fatal("document read policy is incorrect")
	}
	for _, role := range []string{"reader", "controller"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") != nil {
			t.Fatalf("%s cannot read document", role)
		}
	}
	if validDocumentEnvelope(protocol.Inbound{Type: "workspace_file_read", Protocol: 2, PaneID: "p", Target: &protocol.TargetRef{PaneID: "p"}}) {
		t.Fatal("legacy protocol accepted")
	}
	if _, ok := protocol.ClassifyAction("workspace_file_write"); ok {
		t.Fatal("preview patch unexpectedly added file writes")
	}
}

func TestDownloadReadHasSameAuthorizationAsPreview(t *testing.T) {
	action, ok := protocol.ClassifyAction("workspace_file_download")
	if !ok || action.Class != protocol.ActionReadOnly || action.Audited {
		t.Fatal("download is not a read-only action")
	}
	for _, role := range []string{"reader", "controller"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") != nil {
			t.Fatal("download denied for", role)
		}
	}
	if validDocumentEnvelope(protocol.Inbound{Type: "workspace_file_download", Protocol: 2, PaneID: "p", Target: &protocol.TargetRef{PaneID: "p"}}) {
		t.Fatal("legacy protocol accepted")
	}
}
