// Package vault stores the desktop catalog and drafts encrypted at rest.
// The wrapping key is supplied by the operating system's credential store.
package vault

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

type Vault struct {
	mu   sync.Mutex
	path string
	aead cipher.AEAD
}

func Open(path string, key []byte) (*Vault, error) {
	if len(key) != 32 {
		return nil, errors.New("vault key must have 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Vault{path: path, aead: aead}, nil
}
func (v *Vault) Load(out any) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	b, err := os.ReadFile(v.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if len(b) < v.aead.NonceSize() {
		return errors.New("encrypted vault is truncated")
	}
	data, err := v.aead.Open(nil, b[:v.aead.NonceSize()], b[v.aead.NonceSize():], []byte("herdr-desktop-v1"))
	if err != nil {
		return errors.New("无法解密本机数据；请保留原文件并检查系统凭据")
	}
	return json.Unmarshal(data, out)
}
func (v *Vault) Save(value any) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	nonce := make([]byte, v.aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	b := v.aead.Seal(append([]byte{}, nonce...), nonce, data, []byte("herdr-desktop-v1"))
	if err = os.MkdirAll(filepath.Dir(v.path), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(v.path), ".vault-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(b)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), v.path)
}
