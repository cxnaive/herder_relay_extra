package profiles

import (
	"errors"
	"unicode"
	"unicode/utf8"
)

const (
	MaxLaunchArgs     = 64
	MaxLaunchArgBytes = 8192
)

// ValidateLaunchArgs bounds persisted argv and rejects terminal control bytes.
// Arguments are data: no shell parsing, substitution or environment expansion.
func ValidateLaunchArgs(args []string) error {
	if len(args) > MaxLaunchArgs {
		return errors.New("agent_args must contain at most 64 arguments")
	}
	total := 0
	for _, arg := range args {
		total += len(arg)
		if total > MaxLaunchArgBytes {
			return errors.New("agent_args exceeds 8192 UTF-8 bytes")
		}
		if !utf8.ValidString(arg) {
			return errors.New("agent_args must contain valid UTF-8")
		}
		for _, c := range arg {
			if unicode.IsControl(c) {
				return errors.New("agent_args must not contain control characters")
			}
		}
	}
	return nil
}
