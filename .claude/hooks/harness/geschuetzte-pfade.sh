#!/usr/bin/env bash
# PreToolUse (Bash, Read, Edit, Write …): fragt nach, bevor Geheimnisse oder die
# Leitplanken selbst berührt werden. Im Headless-Lauf gibt es niemanden, der «ja» sagt –
# dort wirkt «ask» als Sperre, genau das soll ein Night-Batch.
source "$(dirname "$0")/_gemeinsam.sh"

eingabe="$(cat)"
werkzeug="$(jq -r '.tool_name // ""' <<<"$eingabe")"

geheim() {
  case "$1" in
    *.env.example|*.env.sample) return 1 ;;
    .env|.env.*|*/.env|*/.env.*|*.enc.yaml|*.enc.yml) return 0 ;;
  esac
  return 1
}

if [[ "$werkzeug" == "Bash" ]]; then
  cmd="$(jq -r '.tool_input.command // ""' <<<"$eingabe")"
  bereinigt="$(sed -E 's/\.env\.(example|sample)//g' <<<"$cmd")"
  grep -Eq '(^|[^[:alnum:]_.-])\.env([^[:alnum:]_-]|$)|\.enc\.ya?ml|\.github/workflows|\.claude/(settings|hooks|harness)|\.githooks' <<<"$bereinigt" \
    && entscheide ask "Harness: Befehl berührt Geheimnisse oder Leitplanken."
  exit 0
fi

pfad="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // ""' <<<"$eingabe")"
[[ -n "$pfad" ]] || exit 0
rel="${pfad#"$projekt"/}"

geheim "$rel" && entscheide ask "Harness: $rel enthält Geheimnisse."
[[ "$werkzeug" == "Read" ]] && exit 0

case "$rel" in
  .github/workflows/*|.githooks/*|.gitleaks.toml|.claude/settings*.json|.claude/hooks/*|.claude/harness.yml)
    entscheide ask "Harness: $rel gehört zu den Leitplanken." ;;
esac
while IFS= read -r glob; do
  # shellcheck disable=SC2053 # Das Muster soll als Glob wirken.
  [[ -n "$glob" && "$rel" == $glob ]] && entscheide ask "Harness: $rel ist geschützt (harness.yml)."
done < <(harness_liste geschuetzte-pfade)
exit 0
