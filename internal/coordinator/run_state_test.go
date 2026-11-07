package coordinator

import (
	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"testing"
)

func TestCloneOwnsRunStateAndDuration(t *testing.T) {
	elapsed := int64(10)
	a := &AgentState{RunState: &codexruntime.RunState{TurnID: "a", ElapsedMS: &elapsed}}
	b := cloneAgentState(a)
	b.RunState.TurnID = "b"
	*b.RunState.ElapsedMS = 20
	if a.RunState.TurnID != "a" || *a.RunState.ElapsedMS != 10 {
		t.Fatal("run state pointer escaped")
	}
}
