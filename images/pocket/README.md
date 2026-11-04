# Herdr Pocket README previews

Four independent **800 × 1700 PNG** screenshots of the Android 0.2.23 native
Views, presented as a single four-column row at the top of the repository README:

- `agents.png`: computers and workspaces, with independent expanded/collapsed groups.
- `conversation.png`: native conversation, running status, and folded tool calls.
- `terminal.png`: ANSI terminal output and both extra-key rows.
- `workspace.png`: read-only project files and directories.

These are reproducible documentation scenes rendered with Robolectric at
400 × 850dp, Android API 35. Names, paths, conversation, progress, and results are
synthetic examples. No user credentials, remote connections, production logs,
or real conversation content are used. They are not physical-device captures.
PNG optimization is lossless; the UI and pixels are not redrawn or composited.

Regenerate the original images with the repository's Android toolchain:

```bash
./herdr-android/gradlew -p herdr-android --offline --no-daemon \
  testDebugUnitTest --tests 'dev.herdr.companion.ReadmePreviewTest'
```

Output: `herdr-android/app/build/reports/readme-preview/`. Review all four images
before copying them here. Keep the README links relative so they work on GitHub.
