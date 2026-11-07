package vault

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestVaultConfidentialityAndTamper(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.enc")
	v, err := Open(path, bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	input := map[string]string{"secret": "private-pairing-key", "draft": "中文草稿"}
	if err = v.Save(input); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(path)
	if bytes.Contains(b, []byte("private-pairing-key")) {
		t.Fatal("plaintext credential")
	}
	var got map[string]string
	if err = v.Load(&got); err != nil || got["draft"] != input["draft"] {
		t.Fatal(got, err)
	}
	input["draft"] = "新草稿"
	if err = v.Save(input); err != nil {
		t.Fatal("atomic replacement", err)
	}
	b, _ = os.ReadFile(path)
	b[len(b)-1] ^= 1
	os.WriteFile(path, b, 0600)
	if err = v.Load(&got); err == nil {
		t.Fatal("tampered vault accepted")
	}
}
