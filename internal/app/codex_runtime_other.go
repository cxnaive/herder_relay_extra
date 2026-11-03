//go:build !linux

package app

func codexProcessListeners(int) (map[string]string, error) { return nil, errCodexProcessStart }
