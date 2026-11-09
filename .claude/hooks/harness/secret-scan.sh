#!/usr/bin/env bash
# PostToolUse (Edit, Write …): prüft die Datei auf der Platte, nicht die Ausgabe – die
# kürzt Tokenade, bevor das Modell sie liest. Fehlt gitleaks, greift der Pre-Commit-Hook.
source "$(dirname "$0")/_gemeinsam.sh"

command -v gitleaks >/dev/null || exit 0
pfad="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // ""')"
[[ -f "$pfad" ]] || exit 0
config=(); [[ -f "$projekt/.gitleaks.toml" ]] && config=(--config "$projekt/.gitleaks.toml")

gitleaks dir "$pfad" "${config[@]}" --no-banner --redact --log-level error >/dev/null 2>&1 && exit 0
jq -n --arg p "$pfad" '{decision: "block", reason: ("Harness: gitleaks meldet ein mögliches Geheimnis in " + $p + ". Entfernen, bevor weitergearbeitet wird; als Geheimnis behandeln, also nicht ausgeben.")}'
exit 0
