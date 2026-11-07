package relay

import (
	"errors"
	"net/url"
	"strconv"
	"time"
)

func InvitationLink(c Computer, data Message, now time.Time) (string, error) {
	invite := Object(data["invitation"])
	expires, err := time.Parse(time.RFC3339, Text(invite, "expires_at"))
	if err != nil || !expires.After(now) {
		return "", errors.New("邀请已过期或格式无效")
	}
	values := url.Values{"invite": {Text(invite, "invitation_id")}, "invite_version": {strconv.FormatInt(Number(invite, "version"), 10)}, "invite_expires": {strconv.FormatInt(expires.UnixMilli(), 10)}, "setup": {Text(invite, "secret")}, "relay_id": {c.RelayID}, "rendezvous": {c.Rendezvous}, "label": {c.Name}}
	link := "herdr-tcp://" + c.Address + "/#" + values.Encode()
	if _, err := ParseInvitation(link, c.Name, now); err != nil {
		return "", errors.New("Relay 返回的邀请格式无效")
	}
	return link, nil
}
