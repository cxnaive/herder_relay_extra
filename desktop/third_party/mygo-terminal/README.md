# Local MyGo terminal rendering extension

This directory contains the runtime terminal plugin sources from **MyGo v0.8.1**, under the original MIT license. `UPSTREAM.json` records the SHA-256 of each unmodified upstream source. The optional upstream native-library generator and upstream tests are not included; `mygo-plugin.json` still pins the original Ghostty binaries and their hashes.

The module uses a distinct path under `github.com/egoist/mygo` so it can use the pinned parent module's internal localization and verified-download helpers. Internal terminal imports are renamed to this local module; the main MyGo module is unchanged.

The only behavioral extension is `Options.SoftenDarkBackgrounds` and `Terminal.SetSoftenDarkBackgrounds`. When enabled with a light theme, the painter maps explicit neutral dark cell backgrounds to subtle light gray **after inverse-color handling and before minimum-contrast adjustment**. Inverse selections and colored backgrounds retain their original rendering. Changing the option invalidates the row paint cache. Terminal data, snapshots, input, history, protocol and remote configuration are unchanged.

This small renderer extension needs local plugin sources because v0.8.1 exposes no cell-color transformation hook. An ANSI stream rewrite would alter stored terminal state and could not correctly recolor already-rendered history when switching themes. Remove the local module once an upstream public rendering API covers this behavior.

Rendering and live-toggle regression tests live in `desktop/appearance_test.go`. The normal `go test ./...` and MyGo build in `desktop/` use this module through the explicit `replace` directive.

The desktop enables this option only for remote terminals whose attached Agent is explicitly identified as `codex` by Relay. Other/unknown Agent types and the local demo shell retain their existing backgrounds. The appearance control names this scope, and inventory updates re-evaluate it even when the terminal identity stays the same.
