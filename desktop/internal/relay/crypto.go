package relay

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"unicode"
)

func mac(key []byte, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, key)
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}
func encode(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
func hkdf(secret, salt []byte, info string) []byte {
	return mac(mac(salt, secret), []byte(info), []byte{1})
}

type handshake struct {
	auth                   Auth
	key                    *ecdh.PrivateKey
	nonce, secret, binding []byte
}

func newHandshake(auth Auth) (*handshake, error) {
	if auth.Kind != "invitation" && auth.Kind != "credential" {
		return nil, errors.New("invalid authentication kind")
	}
	if auth.Version < 1 || len(auth.ID) < 1 || len(auth.ID) > 256 || strings.IndexFunc(auth.ID, unicode.IsControl) >= 0 {
		return nil, errors.New("invalid authentication identity")
	}
	secret, err := decode(auth.Secret, 32)
	if err != nil {
		return nil, err
	}
	key, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, 32)
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	return &handshake{auth: auth, key: key, nonce: nonce, secret: secret, binding: fmt.Appendf(nil, "herdr-e2ee-v2 auth\x00%s\x00%s\x00%d\x00", auth.Kind, auth.ID, auth.Version)}, nil
}
func (h *handshake) hello() Message {
	pub := h.key.PublicKey().Bytes()
	return Message{"type": "e2ee_client_hello", "version": 2, "auth_kind": h.auth.Kind, "auth_id": h.auth.ID, "auth_version": h.auth.Version, "locale": "zh-CN", "nonce": encode(h.nonce), "public_key": encode(pub), "proof": encode(mac(h.secret, []byte("herdr-e2ee-v2 client\x00"), h.binding, h.nonce, pub))}
}
func (h *handshake) finish(m Message) (*sessionCipher, error) {
	if Text(m, "type") != "e2ee_server_hello" || Number(m, "version") != 2 {
		return nil, errors.New("unexpected encrypted handshake")
	}
	nonce, err := decode(Text(m, "nonce"), 32)
	if err != nil {
		return nil, err
	}
	pub, err := decode(Text(m, "public_key"), 65)
	if err != nil {
		return nil, err
	}
	proof, err := decode(Text(m, "proof"), 32)
	if err != nil {
		return nil, err
	}
	transcript := append(append(append(append([]byte{}, h.binding...), h.nonce...), h.key.PublicKey().Bytes()...), nonce...)
	transcript = append(transcript, pub...)
	if !hmac.Equal(proof, mac(h.secret, []byte("herdr-e2ee-v2 server\x00"), transcript)) {
		return nil, errors.New("Relay 设备身份校验失败")
	}
	pk, err := ecdh.P256().NewPublicKey(pub)
	if err != nil {
		return nil, err
	}
	shared, err := h.key.ECDH(pk)
	if err != nil {
		return nil, err
	}
	defer clear(shared)
	salt := mac(h.secret, []byte("herdr-e2ee-v2 key\x00"), transcript)
	out, err := newAEAD(hkdf(shared, salt, "herdr-e2ee-v2 c2s"))
	if err != nil {
		return nil, err
	}
	in, err := newAEAD(hkdf(shared, salt, "herdr-e2ee-v2 s2c"))
	if err != nil {
		return nil, err
	}
	clear(h.secret)
	return &sessionCipher{out: out, in: in}, nil
}
func newAEAD(key []byte) (cipher.AEAD, error) {
	b, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(b)
}

type sessionCipher struct {
	out, in        cipher.AEAD
	sent, received uint64
}

func nonceAAD(n uint64, direction string) ([]byte, []byte) {
	nonce := make([]byte, 12)
	binary.BigEndian.PutUint64(nonce[4:], n)
	aad := append([]byte("herdr-e2ee-v2 "+direction+"\x00"), nonce[4:]...)
	return nonce, aad
}
func (s *sessionCipher) seal(data []byte) ([]byte, error) {
	if s.sent >= 1<<53-1 {
		return nil, errors.New("encrypted sequence exhausted")
	}
	nonce, aad := nonceAAD(s.sent, "c2s")
	result := make([]byte, 10)
	result[0] = 2
	binary.BigEndian.PutUint64(result[2:], s.sent)
	result = s.out.Seal(result, nonce, data, aad)
	s.sent++
	return result, nil
}
func (s *sessionCipher) open(data []byte) ([]byte, error) {
	if len(data) < 26 || data[0] != 2 || data[1] != 0 || binary.BigEndian.Uint64(data[2:10]) != s.received || s.received >= 1<<53-1 {
		return nil, errors.New("invalid encrypted frame sequence")
	}
	nonce, aad := nonceAAD(s.received, "s2c")
	result, err := s.in.Open(nil, nonce, data[10:], aad)
	if err != nil {
		return nil, err
	}
	s.received++
	return result, nil
}
