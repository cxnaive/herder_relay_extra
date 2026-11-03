#!/bin/sh
# Run from an extracted native gateway bundle, as root. Preserves an existing env.
set -eu
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo 'Run as root' >&2; exit 1; }
[ -f herdr-gateway ] && [ -f herdr-tcp-gateway.service ]
if [ ! -f /etc/herdr-tcp-gateway/relay.env ]; then
  : "${HERDR_GATEWAY_ALLOWED_RELAY_IDS:?Set your computer relay ID before first install}"
  printf '%s\n' "$HERDR_GATEWAY_ALLOWED_RELAY_IDS" | grep -Eq '^[A-Za-z0-9_-]{22}(,[A-Za-z0-9_-]{22})*$' || { echo 'Invalid relay allow-list' >&2; exit 1; }
fi
id herdr-tcp >/dev/null 2>&1 || useradd --system --home-dir /var/lib/herdr-tcp-gateway --no-create-home --shell /usr/sbin/nologin herdr-tcp
install -d -m 0755 /opt/herdr-tcp-gateway/releases/0.23.5-codexfix.6
install -d -m 0700 /etc/herdr-tcp-gateway
install -d -m 0700 -o herdr-tcp -g herdr-tcp /var/lib/herdr-tcp-gateway
if [ ! -f /etc/herdr-tcp-gateway/relay.env ]; then
  umask 077
  token=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  sed -e "s/REPLACE_WITH_RANDOM_64_HEX_CHARACTERS/$token/" \
      -e "s/REPLACE_WITH_COMPUTER_RELAY_ID/$HERDR_GATEWAY_ALLOWED_RELAY_IDS/" \
      relay.env.example > /etc/herdr-tcp-gateway/relay.env
fi
install -m 0755 herdr-gateway /opt/herdr-tcp-gateway/releases/0.23.5-codexfix.6/herdr-gateway.new
mv -f /opt/herdr-tcp-gateway/releases/0.23.5-codexfix.6/herdr-gateway.new /opt/herdr-tcp-gateway/releases/0.23.5-codexfix.6/herdr-gateway
install -m 0644 herdr-tcp-gateway.service /etc/systemd/system/herdr-tcp-gateway.service
ln -sfn releases/0.23.5-codexfix.6 /opt/herdr-tcp-gateway/current.new
mv -Tf /opt/herdr-tcp-gateway/current.new /opt/herdr-tcp-gateway/current
systemctl daemon-reload
systemctl enable herdr-tcp-gateway.service
systemctl restart herdr-tcp-gateway.service
printf '%s\n' 'Gateway installed. Allow inbound TCP 8377 in the cloud security group if necessary.' \
  'Copy the registration secret securely from /etc/herdr-tcp-gateway/relay.env to the computer relay.env; never to the phone.'
