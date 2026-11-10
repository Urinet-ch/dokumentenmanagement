#!/usr/bin/env bash
# PostToolUse: hält Werkzeug, gekürztes Argument und Ergebnis als JSONL fest, damit sich
# ein Lauf nachvollziehen lässt. Nicht signiert. Scheitert nie sichtbar: Das Log darf
# keine Arbeit aufhalten.
source "$(dirname "$0")/_gemeinsam.sh"

ablage="$HOME/.claude/audit/$(basename "$projekt")"
mkdir -p "$ablage" 2>/dev/null || exit 0
umask 077
jq -c '{zeit: (now | todate), session: .session_id, werkzeug: .tool_name,
    ergebnis: (if .hook_event_name == "PostToolUseFailure" then "fehler" else "ok" end),
    argument: ((.tool_input.command // .tool_input.file_path // .tool_input.notebook_path // "")
      # Tokenade packt Bash-Befehle in «tokenade wrap '…'»; geloggt wird der eigentliche Befehl.
      | sub("^TOKENADE_SESSION=[^ ]+ \u0027[^\u0027]*\u0027 wrap \u0027(?<c>.*)\u0027$"; "\(.c)")
      | gsub("(?i)(?<k>password|passwort|secret|token|api[_-]?key)(?<s>=|: *)[^ ]+"; "\(.k)\(.s)***")
      | gsub("(ghp_|gho_|github_pat_|tskey-|GOCSPX-|sk-)[A-Za-z0-9_-]+"; "***")
      | .[0:200])}' >>"$ablage/$(date +%F).jsonl" 2>/dev/null
exit 0
