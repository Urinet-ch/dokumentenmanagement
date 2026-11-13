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

# nur_lesend <befehl>: wahr, wenn jeder Teilbefehl git oder ein Lesewerkzeug ist und nichts in eine
# Datei schreibt. Teilt an ; & | und Zeilenumbrüchen ausserhalb von Anführungszeichen. Im Zweifel
# falsch: Befehlsersetzung, Umleitung in eine Datei, sed/perl -i, find -exec/-delete.
nur_lesend() {
  local c="$1" teile=() t="" q="" z i
  [[ "$c" == *'$('* || "$c" == *'`'* ]] && return 1
  for ((i = 0; i < ${#c}; i++)); do
    z="${c:i:1}"
    if [[ -n "$q" ]]; then [[ "$z" == "$q" ]] && q=""; t+="$z"; continue; fi
    case "$z" in
      "'"|'"') q="$z"; t+="$z" ;;
      ';'|'&'|'|'|$'\n') teile+=("$t"); t="" ;;
      '>') [[ "${c:i+1}" =~ ^'>'?[[:space:]]*(/dev/null|'&'[0-9]) ]] || return 1; t+="$z" ;;
      *) t+="$z" ;;
    esac
  done
  teile+=("$t")
  local w rest
  for t in "${teile[@]}"; do
    rest="$t"
    while :; do
      rest="${rest#"${rest%%[![:space:]({]*}"}"
      w="${rest%%[[:space:]]*}"
      case "$w" in
        do|then|else|elif|'!') rest="${rest#"$w"}" ;;
        *=*) [[ "$w" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] && rest="${rest#"$w"}" || break ;;
        *) break ;;
      esac
    done
    case "$w" in
      ''|')'|'}'|done|fi|git|gh|ls|cat|head|tail|jq|grep|rg|wc|diff|stat|sort|uniq|cut|tr|echo|printf|cd|pwd|for|if|while|test|'['|'[['|true|basename|dirname|realpath|file) ;;
      sed|perl) [[ "$rest" =~ [[:space:]](-[A-Za-z]*i|--in-place) ]] && return 1 ;;
      find) [[ "$rest" =~ -(exec|execdir|delete|ok|fprint) ]] && return 1 ;;
      *) return 1 ;;
    esac
  done
  return 0
}

if [[ "$werkzeug" == "Bash" ]]; then
  cmd="$(jq -r '.tool_input.command // ""' <<<"$eingabe")"
  bereinigt="$(sed -E 's/\.env\.(example|sample)//g' <<<"$cmd")"
  grep -Eq '(^|[^[:alnum:]_.-])\.env([^[:alnum:]_-]|$)|\.enc\.ya?ml' <<<"$bereinigt" \
    && entscheide ask "Harness: Befehl berührt Geheimnisse."
  # Leitplanken: lesen ist frei (Read-Werkzeug ebenso), schreiben fragt nach.
  grep -Eq '\.github/workflows|\.claude/(settings|hooks|harness)|\.githooks' <<<"$cmd" && ! nur_lesend "$cmd" \
    && entscheide ask "Harness: Befehl schreibt womöglich in die Leitplanken."
  # Zugangsdaten ausserhalb des Repos. permissions.deny sperrt Read und cat & Co., aber
  # nicht ein Skript, das die Datei selbst öffnet (python -c, node -e). Leitplanke gegen
  # Credential Harvesting, keine Sandbox: «cd ~ && cat .ssh/x» kommt hier durch.
  heim='(~|\$HOME|\$\{HOME\}|/Users/[^/[:space:]]+|/home/[^/[:space:]]+)'
  grep -Eq "${heim}/(\.ssh/|\.aws/|\.azure/|\.kube/|\.gnupg/|\.config/(gh|gcloud|sops)/|\.docker/config\.json|\.netrc|\.npmrc|\.pypirc|\.git-credentials|\.fly/|\.m2/settings|\.gradle/gradle\.properties|\.claude\.json|\.claude/\.credentials|Library/Keychains|Library/Application.{1,2}Support/sops)" <<<"$cmd" \
    && entscheide ask "Harness: Befehl berührt Zugangsdaten ausserhalb des Repos."
  grep -Eq '(^|[^[:alnum:]_-])(gh[[:space:]]+auth[[:space:]]+token|security[[:space:]]+(find-generic-password|find-internet-password|dump-keychain))|/proc/(self|[0-9]+)/environ' <<<"$cmd" \
    && entscheide ask "Harness: Befehl liest Zugangsdaten aus."
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
