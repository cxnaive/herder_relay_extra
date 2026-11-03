//go:build !linux

package app

func codexProcessStart(int) (string, error) { return "", errCodexProcessStart }
