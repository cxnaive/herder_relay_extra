#!/usr/bin/env python3
"""Explicitly select one named Codex thread for one currently running Herdr pane.
No terminal keystrokes, transcript writes, or service restarts are performed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile


def herdr(*args):
    return json.loads(subprocess.check_output(["herdr", *args], text=True))["result"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pane", required=True)
    parser.add_argument("--thread-name", required=True)
    parser.add_argument("--codex-home", type=Path, default=Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))))
    parser.add_argument("--config", type=Path, default=Path.home() / ".config/herdr/plugins/config/herdr-mobile-relay.events/codex-session-bindings.json")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    index_path = args.codex_home / "session_index.jsonl"
    if index_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Session index exceeds the supported bound")
    latest = {}
    for line in index_path.read_text().splitlines():
        row = json.loads(line)
        latest[row["id"]] = row.get("thread_name", "")
    ids = [key for key, name in latest.items() if name == args.thread_name]
    if len(ids) != 1:
        raise ValueError("Thread name is missing or ambiguous; refusing to guess")
    session_id = ids[0]
    if not re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", session_id):
        raise ValueError("Invalid logical thread ID")
    candidates = []
    for path in args.codex_home.glob("state_*.sqlite"):
        match = re.fullmatch(r"state_(\d+)\.sqlite", path.name)
        if match:
            candidates.append((int(match[1]), path))
    row = None
    for _, path in sorted(candidates, reverse=True)[:4]:
        try:
            connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=1)
            try:
                row = connection.execute("SELECT cwd,rollout_path FROM threads WHERE id=?", (session_id,)).fetchone()
            finally:
                connection.close()
        except sqlite3.Error:
            continue
        if row:
            break
    if not row:
        raise ValueError("Selected thread has no readable database entry")
    cwd, rollout = row
    path = Path(rollout).resolve(strict=True)
    path.relative_to((args.codex_home / "sessions").resolve(strict=True))
    if path.suffix != ".jsonl":
        raise ValueError("Only a readable active JSONL rollout can be pinned")
    with path.open() as stream:
        metadata = json.loads(stream.readline(1024 * 1024))
    if metadata.get("type") != "session_meta" or metadata["payload"].get("id") != session_id or metadata["payload"].get("cwd") != cwd:
        raise ValueError("Rollout metadata does not match the selected thread/project")
    pane = herdr("agent", "get", args.pane)["agent"]
    title = args.thread_name + " | " + Path(cwd).name
    if pane.get("agent") != "codex" or pane.get("cwd") != cwd or pane.get("terminal_title_stripped") != title:
        raise ValueError("Selected pane does not display the explicitly selected thread")
    reported = pane.get("agent_session", {}).get("value", "")
    if reported and reported != session_id:
        raise ValueError("Herdr reports another session; do not override it")
    info = herdr("pane", "process-info", "--pane", args.pane)["process_info"]
    processes = info.get("foreground_processes", [])
    if len(processes) != 1:
        raise ValueError("Ambiguous foreground process")
    process = processes[0]
    pid = info["foreground_process_group_id"]
    if pid != process["pid"] or process["cwd"] != cwd or Path(process["argv"][0]).name != "codex":
        raise ValueError("Foreground process is not the selected Codex client")
    stat = Path(f"/proc/{pid}/stat").read_text()
    ticks = stat[stat.rindex(")") + 2:].split()[19]
    birth = f"{Path('/proc/sys/kernel/random/boot_id').read_text().strip()}:{pid}:{ticks}"
    binding = dict(pane_id=args.pane, terminal_id=pane["terminal_id"], cwd=cwd, terminal_title=title,
                   session_id=session_id, pid=pid, process_start=birth)
    if args.dry_run:
        print(json.dumps(binding, ensure_ascii=False, indent=2))
        return
    records = {"schema": 1, "bindings": []}
    if args.config.exists():
        records = json.loads(args.config.read_text())
        if records.get("schema") != 1:
            raise ValueError("Unknown binding file schema")
    records["bindings"] = [entry for entry in records["bindings"] if entry["pane_id"] != args.pane] + [binding]
    if len(records["bindings"]) > 32:
        raise ValueError("Too many explicit bindings")
    args.config.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".codex-binding-", dir=args.config.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(records, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, args.config)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print("Saved explicit terminal/process binding. Relay will pick it up on its next inventory refresh.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
