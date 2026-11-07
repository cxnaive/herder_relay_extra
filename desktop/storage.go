package main

import (
	"crypto/rand"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/cxnaive/herdr-desktop/internal/vault"
	"github.com/egoist/mygo"
)

type savedState struct {
	Computers []relay.Computer
	Drafts    map[string]string
	Uncertain map[string]bool
	Collapsed map[string]bool
}

func (s *storage) sidebarGroups() map[string]bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	copy := map[string]bool{}
	for k, v := range s.state.Collapsed {
		copy[k] = v
	}
	return copy
}
func (s *storage) saveSidebarGroups(groups map[string]bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.state.Collapsed
	s.state.Collapsed = map[string]bool{}
	for k, v := range groups {
		if v {
			s.state.Collapsed[k] = true
		}
	}
	if err := s.vault.Save(s.state); err != nil {
		s.state.Collapsed = old
		return err
	}
	return nil
}

type storage struct {
	mu    sync.Mutex
	vault *vault.Vault
	state savedState
}

func openStorage() (*storage, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return nil, err
	}
	path := filepath.Join(dir, "Herdr Desktop", "state.enc")
	key, err := mygo.Secrets.Service("dev.herdr.desktop").Get("vault-key-v1")
	if errors.Is(err, mygo.ErrSecretNotFound) {
		if _, statErr := os.Stat(path); statErr == nil {
			return nil, errors.New("系统凭据中的密钥缺失，已保留原数据文件")
		}
		key = make([]byte, 32)
		if _, err = rand.Read(key); err != nil {
			return nil, err
		}
		err = mygo.Secrets.Service("dev.herdr.desktop").Set("vault-key-v1", key)
	}
	if err != nil {
		return nil, err
	}
	defer clear(key)
	v, err := vault.Open(path, key)
	if err != nil {
		return nil, err
	}
	s := &storage{vault: v}
	if err = v.Load(&s.state); err != nil {
		return nil, err
	}
	if s.state.Drafts == nil {
		s.state.Drafts = map[string]string{}
	}
	if s.state.Uncertain == nil {
		s.state.Uncertain = map[string]bool{}
	}
	return s, nil
}
func (s *storage) computers() []relay.Computer {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]relay.Computer{}, s.state.Computers...)
}
func (s *storage) saveComputer(c relay.Computer) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := append([]relay.Computer{}, s.state.Computers...)
	found := false
	for i := range next {
		if next[i].ID == c.ID {
			next[i] = c
			found = true
		}
	}
	if !found {
		next = append(next, c)
	}
	old := s.state.Computers
	s.state.Computers = next
	if err := s.vault.Save(s.state); err != nil {
		s.state.Computers = old
		return err
	}
	return nil
}
func (s *storage) auth(id string, a relay.Auth) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.state.Computers {
		if s.state.Computers[i].ID == id {
			old := s.state.Computers[i].Auth
			s.state.Computers[i].Auth = a
			if err := s.vault.Save(s.state); err != nil {
				s.state.Computers[i].Auth = old
				return err
			}
			return nil
		}
	}
	return errors.New("电脑不存在")
}
func (s *storage) draft(key string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state.Drafts[key], s.state.Uncertain[key]
}
func (s *storage) saveDraft(key, text string, uncertain bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	old, flag := s.state.Drafts[key], s.state.Uncertain[key]
	s.state.Drafts[key] = text
	s.state.Uncertain[key] = uncertain
	if err := s.vault.Save(s.state); err != nil {
		s.state.Drafts[key] = old
		s.state.Uncertain[key] = flag
		return err
	}
	return nil
}
