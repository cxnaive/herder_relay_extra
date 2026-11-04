package app

import (
	"path/filepath"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

func codexForegroundProcess(info *herdr.PaneProcessInfo, pane string) (herdr.PaneProcess, error) {
	if _, err := foregroundRoot(info, pane); err == nil {
		process := info.ForegroundProcesses[0]
		if len(process.Argv) > 0 && filepath.Base(process.Argv[0]) == "codex" {
			return process, nil
		}
	}
	return codexForegroundGroup(info, pane)
}
