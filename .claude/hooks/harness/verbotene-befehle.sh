#!/usr/bin/env bash
# PreToolUse (Bash): sperrt Befehle, die nie ohne Mensch laufen sollen, auch mitten in
# einer Befehlskette. Ergänzt permissions.deny, das nur Präfixe und kein «curl … | sh»
# kennt. Leitplanke gegen Versehen, keine Sandbox: eval oder base64 kommen hier durch.
source "$(dirname "$0")/_gemeinsam.sh"

cmd="$(jq -r '.tool_input.command // ""')"
# Nur der Befehl zählt, nicht ein zitierter Text etwa in einer Commit-Nachricht.
kurz="$(sed -E "s/'[^']*'//g; s/\"[^\"]*\"//g" <<<"$cmd")"
start='(^|[;&|(`[:space:]])'

if grep -Eq "${start}git[^;&|]*[[:space:]]push([[:space:]]|$)" <<<"$kurz"; then
  ohne_lease="${kurz//--force-with-lease/}"
  grep -Eq '(--force|[[:space:]]-[a-zA-Z]*f)([[:space:]]|$)' <<<"$ohne_lease" \
    && entscheide deny "Harness: git push --force ist gesperrt (--force-with-lease geht)."
fi
grep -Eq "${start}git[^;&|]*--no-verify" <<<"$kurz" \
  && entscheide deny "Harness: --no-verify umgeht Secret-Scan und main-Sperre."
grep -Eq "${start}git[^;&|]*[[:space:]]commit[^;&|]*[[:space:]]-[a-zA-Z]*n([[:space:]]|$)" <<<"$kurz" \
  && entscheide deny "Harness: git commit -n umgeht den Secret-Scan."
grep -Eq "${start}git[^;&|]*core\.hookspath" <<<"$(tr '[:upper:]' '[:lower:]' <<<"$kurz")" \
  && entscheide deny "Harness: core.hooksPath hält die Git-Hooks scharf und bleibt unverändert."
grep -Eq "${start}rm[[:space:]]+(-[a-zA-Z]*r[a-zA-Z]*[[:space:]]+-[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*[[:space:]]+-[a-zA-Z]*r|-[a-zA-Z]*(rf|fr)|--recursive[[:space:]]+--force|--force[[:space:]]+--recursive)" <<<"$kurz" \
  && entscheide deny "Harness: rm -rf ist gesperrt."
grep -Eq "${start}(curl|wget)[^;&]*\|[[:space:]]*(sudo[[:space:]]+)?(ba|z|da)?sh([[:space:]]|$)" <<<"$kurz" \
  && entscheide deny "Harness: Skripte aus dem Netz nicht direkt ausführen."
grep -Eq "${start}gh[[:space:]]+secret[[:space:]]+(set|delete)" <<<"$kurz" \
  && entscheide deny "Harness: GitHub-Secrets setzt ein Mensch."
grep -Eq "${start}sops[^;&|]*[[:space:]](-d|--decrypt)([[:space:]]|$)" <<<"$kurz" \
  && entscheide deny "Harness: Secrets nicht entschlüsseln."
exit 0
