package app

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
)

// The only offered history must have persisted proof. Unmaterialized roots stay
// ambiguous for interaction: they may be the user's genuinely new empty thread.
func (s *Server) codexAssociatedHistoryCandidate(probe codexRuntimeProbe, origins map[string]codexruntime.Thread) (codexRuntimeThread, string, bool) {
	var selected codexRuntimeThread
	if s.conversationM == nil || len(probe.listeners) != 1 {
		return selected, "", false
	}
	origin := ""
	for address := range probe.listeners {
		origin = address
	}
	observation := origins[origin]
	if observation.ID != "" || observation.Unavailable != "runtime_ambiguous" || len(observation.HistoryCandidates) < 2 {
		return selected, "", false
	}
	seen := map[string]bool{}
	pending := false
	for _, candidate := range observation.HistoryCandidates {
		if !codexBindingID.MatchString(candidate.ID) || seen[candidate.ID] || candidate.CWD != probe.binding.CWD || candidate.Delegated() ||
			(candidate.SessionID != "" && candidate.SessionID != candidate.ID) || !filepath.IsAbs(candidate.Path) {
			return codexRuntimeThread{}, "", false
		}
		seen[candidate.ID] = true
		location := s.conversationM.Locate("codex", candidate.CWD, candidate.ID)
		switch candidate.HistoryState {
		case codexruntime.HistoryPersisted:
			if selected.thread.ID != "" || location.Path == "" || !s.conversationM.CodexSessionProjectMatches(candidate.CWD, candidate.ID) {
				return codexRuntimeThread{}, "", false
			}
			root, err := filepath.EvalSymlinks(location.Root)
			if err != nil || root != probe.root {
				return codexRuntimeThread{}, "", false
			}
			actual, err := filepath.EvalSymlinks(location.Path)
			if err != nil {
				return codexRuntimeThread{}, "", false
			}
			declared, err := filepath.EvalSymlinks(candidate.Path)
			if err != nil || actual != declared || !pathInCodexStore(probe.root, actual) {
				return codexRuntimeThread{}, "", false
			}
			selected = codexRuntimeThread{thread: candidate, root: root}
		case codexruntime.HistoryUnmaterialized:
			if location.Path != "" || !pathInCodexStore(probe.root, filepath.Clean(candidate.Path)) {
				return codexRuntimeThread{}, "", false
			}
			parent, err := filepath.EvalSymlinks(filepath.Dir(candidate.Path))
			if err != nil || (parent != probe.root && !pathInCodexStore(probe.root, parent)) {
				return codexRuntimeThread{}, "", false
			}
			if _, err := os.Lstat(candidate.Path); !os.IsNotExist(err) {
				return codexRuntimeThread{}, "", false
			}
			pending = true
		default:
			return codexRuntimeThread{}, "", false
		}
	}
	return selected, origin, selected.thread.ID != "" && pending
}

func pathInCodexStore(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != "." && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}
