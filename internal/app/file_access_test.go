package app

import (
	"context"
	"github.com/0cv/herdr-mobile-relay/internal/deviceauth"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"testing"
)

func TestFilesystemReadAuthorizationIsDefaultOffAndRecheckedOnEveryRead(t *testing.T) {
	store, err := deviceauth.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	invite, err := store.CreateInvitation("test", deviceauth.RoleController, "en")
	if err != nil {
		t.Fatal(err)
	}
	result, err := store.CompleteE2EEAuth(context.Background(), transport.E2EEAuthSelector{Kind: transport.E2EEAuthInvitation, ID: invite.InvitationID, Version: invite.Version}, true)
	if err != nil {
		t.Fatal(err)
	}
	identity := result.Identity
	server := &Server{deviceAuth: store}
	root := func(scope string) (string, error) {
		return fileReadRootFor("/workspace", scope, server.filesystemReadAllowedFor(identity, true))
	}
	if _, err := root("filesystem"); err == nil {
		t.Fatal("default outside access")
	}
	if got, err := root(""); err != nil || got != "/workspace" {
		t.Fatal("workspace default broken")
	}
	if err := store.SetFilesystemRead(identity.CredentialID, identity.CredentialVersion, true); err != nil {
		t.Fatal(err)
	}
	if got, err := root("filesystem"); err != nil || got != "/" {
		t.Fatal("opt-in not effective", err)
	}
	if got, err := root("workspace"); err != nil || got != "/workspace" {
		t.Fatal("opt-in silently expanded workspace scope")
	}
	wrong := identity
	wrong.DeviceID = "different-device"
	if server.filesystemReadAllowedFor(wrong, true) || server.filesystemReadAllowedFor(identity, false) {
		t.Fatal("identity not fenced")
	}
	if _, err := root("anything"); err == nil {
		t.Fatal("unknown scope accepted")
	}
	store.SetFilesystemRead(identity.CredentialID, identity.CredentialVersion, false)
	if _, err := root("filesystem"); err == nil {
		t.Fatal("disabled permission still usable by an ongoing transfer")
	}
	action, _ := protocol.ClassifyAction("file_access_set")
	if action.Class != protocol.ActionMutating || !action.RequiresProtocol {
		t.Fatal("setting is not a v3 controller mutation")
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "reader"}, true, action, "") == nil || authorizeAuthenticatedIdentity(identity, false, action, "") == nil {
		t.Fatal("unauthorized setting change")
	}
}
