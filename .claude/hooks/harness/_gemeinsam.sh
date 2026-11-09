# Gemeinsame Helfer der Harness-Hooks. Nur per «source» laden.
command -v jq >/dev/null || { echo "Harness: jq fehlt, Aktion gesperrt." >&2; exit 2; }

projekt="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

# entscheide ask|deny <grund>: Antwort an Claude Code im PreToolUse-Format.
entscheide() {
  jq -n --arg d "$1" --arg g "$2" '{hookSpecificOutput: {hookEventName: "PreToolUse",
    permissionDecision: $d, permissionDecisionReason: $g}}'
  exit 0
}

# harness_liste <schlüssel>: Einträge einer Liste aus .claude/harness.yml, ein Glob pro Zeile.
harness_liste() {
  local datei="$projekt/.claude/harness.yml"
  [[ -f "$datei" ]] || return 0
  awk -v k="$1:" '$0 ~ /^[^ #]/ { an = ($1 == k); next }
    an && /^ *- / { sub(/^ *- */, ""); gsub(/"/, ""); print }' "$datei"
}
