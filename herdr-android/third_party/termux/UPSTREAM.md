# Termux terminal renderer and emulator

Source: https://github.com/termux/termux-app

Pinned revision: `8629e632fcb95da272221be327db653fb24befe9`.

The Java files from `terminal-emulator/src/main/java/com/termux/terminal/`
and `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` are
included unchanged in `app/src/main/java/com/termux/`. The full source of these
two modules at the pinned revision is included in `termux-terminal-source.zip`.

The repository is GPLv3-only, with Apache-2.0 exceptions documented in its
original `LICENSE.md`. That notice is retained here. Herdr Pocket's
`NativeTerminalView.kt` adapts the emulator/renderer to relay screen snapshots;
it does not instantiate a local shell session or load Termux's PTY JNI library.

Terminal-generated input replies and clipboard OSC callbacks are deliberately
ignored; user input is sent through the relay's typed commands.

## Pocket 0.2.2 renderer changes

`TerminalRenderer.java` keeps upstream ANSI/wcwidth behavior, adds subpixel text
rendering and a minimum 1.35em line height, and clips iteration to visible rows
in the enclosing Android ScrollView. Font metrics are still taken directly from
Paint. No whole-terminal horizontal scaling is introduced.
