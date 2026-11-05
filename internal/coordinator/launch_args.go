package coordinator

import (
	"errors"

	"github.com/0cv/herdr-mobile-relay/internal/profiles"
)

func decodeAgentArgs(message map[string]any) ([]string, error) {
	raw, present := message["agent_args"]
	if !present {
		return nil, nil
	}
	var args []string
	switch values := raw.(type) {
	case []string:
		args = append(args, values...)
	case []any:
		if len(values) > profiles.MaxLaunchArgs {
			return nil, errors.New("agent_args must contain at most 64 arguments")
		}
		for _, value := range values {
			arg, ok := value.(string)
			if !ok {
				return nil, errors.New("agent_args must be an array of strings")
			}
			args = append(args, arg)
		}
	default:
		return nil, errors.New("agent_args must be an array of strings")
	}
	if err := profiles.ValidateLaunchArgs(args); err != nil {
		return nil, err
	}
	return args, nil
}
