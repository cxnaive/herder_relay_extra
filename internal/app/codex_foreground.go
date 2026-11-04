package app

import (
	"errors"
	"path/filepath"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

var errCodexProcessStart = errors.New("Codex process identity unavailable")

func codexForegroundProcess(info *herdr.PaneProcessInfo, pane string) (herdr.PaneProcess, error) {
	if _, err := foregroundRoot(info, pane); err == nil {
		process := info.ForegroundProcesses[0]
		if len(process.Argv) > 0 && filepath.Base(process.Argv[0]) == "codex" {
			return process, nil
		}
	}
	return codexForegroundGroup(info, pane)
}
