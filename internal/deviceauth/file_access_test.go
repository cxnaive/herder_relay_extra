package deviceauth

import "testing"

func TestFilesystemReadOptInIsPerControllerCredentialAndPersists(t *testing.T) {
	dir := t.TempDir()
	store, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	store.state.Credentials = []credentialRecord{testCredential("a", "ca", RoleController), testCredential("b", "cb", RoleController), testCredential("r", "cr", RoleReader)}
	for _, c := range store.ListCredentials("") {
		if c.FilesystemRead {
			t.Fatal("not default off")
		}
	}
	if err := store.SetFilesystemRead("ca", 1, true); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range reopened.ListCredentials("") {
		if c.FilesystemRead != (c.CredentialID == "ca") {
			t.Fatal("permission leaked between devices")
		}
	}
	if err := store.SetFilesystemRead("cr", 1, true); err == nil {
		t.Fatal("reader enabled outside access")
	}
	if err := store.SetFilesystemRead("ca", 2, true); err == nil {
		t.Fatal("stale credential changed access")
	}
	if err := store.SetFilesystemRead("ca", 1, false); err != nil {
		t.Fatal(err)
	}
	if c, _ := store.AuthorizeCredential("ca", 1); c.FilesystemRead {
		t.Fatal("disable did not take effect")
	}
	if _, err := store.RevokeCredential("ca"); err != nil {
		t.Fatal(err)
	}
	if err := store.SetFilesystemRead("ca", 2, true); err == nil {
		t.Fatal("revoked credential enabled access")
	}
}
