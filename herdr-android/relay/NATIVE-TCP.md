# Native IP + TCP gateway (local extension)

This extension ships in local Relay/Gateway `0.23.5+codexfix.6` and Android
Herdr Pocket `0.2.8` (versionCode 11). It is not an upstream release feature.
It uses the existing E2EE v2 protocol with a native TCP record carrier instead
of WebSocket. Existing HTTPS/WSS support and certificate validation are unchanged.

## Ports and endpoints

- **Public business traffic: one TCP port, 8377.** Both phone and computer make
  outbound connections to that port. No public UDP, 80 or 443 is required.
- SSH 22 is maintenance access, not an app connection.
- Gateway HTTP health/legacy WS routes bind **127.0.0.1:18377 only**.
- The computer's existing Relay HTTP 8375 and event UDP 8376 remain loopback-only.
- Disable gateway STUN with `HERDR_GATEWAY_STUN_ADDR=` (explicit empty value).
- On the computer set `HERDR_TRANSPORT_FORCE_RELAY=1` and
  `HERDR_REACHABILITY_PORT_MAPPING=0`. The native Android path never attempts WebRTC.

Example computer configuration, in its private `relay.env` (203.0.113.10 is a documentation-only address; replace it with your server):

```sh
HERDR_GATEWAY_URL=tcp://203.0.113.10:8377
HERDR_GATEWAY_REGISTRATION_TOKEN=<the gateway's random registration secret>
HERDR_TRANSPORT_FORCE_RELAY=1
HERDR_REACHABILITY_PORT_MAPPING=0
```

Keep the existing relay token and device-auth store; do not replace them just
because the carrier changes. The shared registration secret belongs only on the
computer and Gateway, never in the APK or phone pairing link. Use at least 32
random bytes of entropy (for example 64 random hex characters), and restrict
registration to the computer's derived relay ID.

## Pairing

Install the new APK over the same-signed existing app. On the computer:

```sh
herdr-phone
# Equivalent local helper:
herdr-phone-tcp
```

Open the app's own device scanner (or paste the link). Native pairing links use
`herdr-tcp://IP:8377/#...`, not a browser page. The upstream plugin's WSS setup
screen is not this native pairing helper. Do not put invitations in chat,
public logs, screenshots, or source control.

The CLI asks the running Relay to generate a 10-minute device invitation using
its private `0700` directory / `0600` Unix admin socket. It does not open or write
the live credential database in a second process. Authentication, invitations,
and revocation are handled by the existing device store.

Native pairing refuses bootstrap/master-key links, address overrides, missing
ports and duplicate fragment parameters. Once the app saves the issued device
credential, it immediately reconnects with that credential. This confirms receipt
and consumes the recoverable pending invitation; subsequent invitation reuse is
rejected. Network loss before confirmation can retry the same pending enrollment
until expiry, without creating another identity.

After verifying the new connection, remove the obsolete WSS computer entry from
the app if desired. Removing a local entry does not revoke other paired devices.
The computer running Herdr must remain online for the phone to access its agents.

## Security boundary

**TCP itself is not encrypted.** Application confidentiality/authentication come
from the existing device-secret-authenticated ephemeral P-256 ECDH handshake and
AES-GCM E2EE v2 frames. Sequence validation rejects replay; tampered proofs and
ciphertext fail closed. The blind gateway does not receive the device secret or
application decryption keys. Registration uses a nonce-bound HMAC, not a plaintext
registration token.

There is no TLS on this native carrier. IP addresses, routing IDs, handshake
metadata, record sizes and timing remain visible to the network/gateway. E2EE
does not prevent blocking or denial of service. This is a local extension, not an
independent cryptographic audit, and TCP is not a guarantee about provider or
regulatory requirements.

## Record format

1. Preface: ASCII `HRDRTCP1`, followed by role byte 1 (computer), 2 (phone), or
   3 (bounded unauthenticated health probe).
2. Each record: one type byte, four-byte unsigned big-endian length, payload.
   Types are 1 JSON/text, 2 binary, 3 ping, 4 pong.
3. Maximum record is 1 MiB + 6 bytes. Ping/pong carry exactly eight bytes.
4. Routing JSON/HMAC and gateway binary chunk framing are unchanged. Encrypted
   application frames use the existing 262144-byte gateway chunk budget.
5. Initial/routing handshakes, partial records, writes, queues, connections and
   per-IP attempts have bounds. Idle connections can keep receiving heartbeat
   pings without losing the stream after a fixed count of heartbeats.

## Service and rollback

The supplied systemd unit runs as a dedicated non-login `herdr-tcp` user, with
read-only system/home protection, a 256 MiB memory limit and writable quota state
only under `/var/lib/herdr-tcp-gateway`. The secret environment file is root-only
at `/etc/herdr-tcp-gateway/relay.env`. Releases live under
`/opt/herdr-tcp-gateway/releases/`; `current` selects the active binary.

```sh
systemctl status herdr-tcp-gateway.service
curl -fsS http://127.0.0.1:18377/healthz
journalctl -u herdr-tcp-gateway.service -n 30 --no-pager
```

Keep the old WSS Gateway untouched. To roll back the computer, restore the backed
up `relay.env`, service override and `herdr-phone` wrapper; daemon-reload and restart
**only `herdr-mobile-relay.service`**. Do not restore an old devices.json over newly
paired devices unless deliberately reverting enrollment, and do not restart Herdr
or agent processes. Reuse the old phone entry or create a fresh WSS invitation.

## Verification

- Whole Go test suite and vet pass. Race tests cover native records, Gateway,
  transport, private pairing, config and application packages.
- Actual Go Gateway + computer client: E2EE command/large multi-chunk reply,
  plaintext/tampered frame rejection and replay rejection.
- Android tests use real local TCP sockets and authenticated E2EE, including
  fragmented headers, ping/pong, oversize refusal, credential reconnection,
  immediate enrollment confirmation and tampered proof/ciphertext rejection.
- Public server path was tested with the isolated Relay before activation:
  history pages and 3 MiB PDF / Markdown / PNG byte-for-byte SHA-256 checks;
  invitation enrollment, credential confirmation, and spent invitation refusal.
- No physical Android device was attached. JVM/Robolectric and APK/lint checks
  do not replace on-phone network and background-lifecycle testing.
