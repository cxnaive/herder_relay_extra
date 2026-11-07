package relay

import (
	"errors"
	"fmt"
)

type RequestError struct {
	Unknown bool
	Message string
}

func (e *RequestError) Error() string { return e.Message }

// UnknownOutcome treats transport failures conservatively. Only a definite
// rejection allows a write to be submitted again without checking its result.
func UnknownOutcome(err error) bool {
	if err == nil {
		return false
	}
	var result *RequestError
	if errors.As(err, &result) {
		return result.Unknown
	}
	return true
}
func commandReply(reply Message) (Message, error) {
	phase := Text(reply, "phase")
	if phase == "dispatched_unknown" || phase == "unconfirmed" || phase == "awaiting_evidence" {
		return nil, &RequestError{true, "操作结果未确认，请先核对当前 Agent"}
	}
	if Text(reply, "type") == "error" || reply["ok"] == false || reply["error"] != nil && reply["error"] != "" {
		return nil, &RequestError{false, fmt.Sprintf("请求未完成：%v", reply["error"])}
	}
	return unwrap(reply), nil
}
