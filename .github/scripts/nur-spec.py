#!/usr/bin/env python3
"""Ist eine Änderung «nur Spec»? Regel für ci.yml (Job «Änderungsart») und den Deploy-Nachweis (pruefe-ci-nachweis.py).

Bei reinen Spec-Anpassungen läuft kein vollständiger CI-Lauf (entschieden von Fabian am 13.11.2026): «Nur Spec» heisst,
die Änderung enthält mindestens eine Datei, und jede geänderte Datei (bei Umbenennungen beide Pfade, gelöschte mit)
ist eine Markdown-Datei «*.md» unter «.kiro/» oder «docs/». Strenge Positivliste, kein Ausschlussmuster: Was nicht
passt (Root-Dateien wie README.md, .github/**, Skripte, Code, Tests, Ressourcen, docs/manifest-schluessel.pub.pem),
verlangt den vollen Lauf. Kein Build-Schritt übernimmt eine .md-Datei aus .kiro/ oder docs/ in ein Artefakt
(backend/pom.xml holt aus docs/ nur den öffentlichen Manifest-Schlüssel; NurSpecTest hält das fest). Kommt eine
solche Übernahme dazu, gehört die Datei hier aus der Positivliste.

Aufruf (Ausgabe «true» oder «false» auf stdout, der Grund auf stderr):
  nur-spec.py pr <basis-sha> <kopf-sha>       Dateien des PR (git diff basis...kopf)
  nur-spec.py push <vorher-sha> <nachher-sha> Dateien des Pushs; neuer Branch, unbekannter oder überschriebener
                                              Vorgänger (kein Vorfahr): nie «nur Spec»
  nur-spec.py pfade                           Pfade NUL-getrennt von stdin (Tests)
Alles andere (workflow_dispatch, Fehler von git) ergibt «false».
"""
import re
import subprocess
import sys

# Name des Jobs der Spec-Prüfungen in ci.yml; der Deploy-Nachweis erkennt den Lauf daran
SPEC_JOB = "Spec-Prüfungen (nur Spec)"
# Name des Klassifizier-Jobs in ci.yml
ART_JOB = "Änderungsart (nur Spec?)"

_SPEC = re.compile(r"(\.kiro|docs)/(?:[^/]+/)*[^/]+\.md")


def ist_spec(pfad):
    if not _SPEC.fullmatch(pfad):
        return False
    # keine Pfadtricks und keine Steuerzeichen: git liefert so etwas nie, im Zweifel voller Lauf
    return all(teil not in ("", ".", "..") for teil in pfad.split("/")) and not any(ord(z) < 32 for z in pfad)


def nur_spec(pfade):
    """(True, Grund) oder (False, Grund)."""
    pfade = list(pfade)
    if not pfade:
        return False, "keine geänderte Datei"
    for pfad in pfade:
        if not ist_spec(pfad):
            return False, f"nicht nur Spec: {pfad}"
    return True, f"nur Spec ({len(pfade)} Datei(en) unter .kiro/ oder docs/)"


def _git(*args):
    erg = subprocess.run(["git", *args], capture_output=True)
    return erg.returncode, erg.stdout


def _commit(sha):
    return bool(re.fullmatch(r"[0-9a-f]{40}", sha or "")) and set(sha) != {"0"} \
        and _git("cat-file", "-e", f"{sha}^{{commit}}")[0] == 0


def _diff(bereich):
    # --no-renames: eine Umbenennung erscheint als gelöschter und neuer Pfad, beide zählen
    code, aus = _git("-c", "core.quotepath=false", "diff", "--no-renames", "--name-only", "-z", bereich)
    if code != 0:
        return None
    return [p.decode("utf-8", "surrogateescape") for p in aus.split(b"\0") if p]


def bestimmen(argv, stdin=None):
    art = argv[1] if len(argv) > 1 else ""
    if art == "pfade" and len(argv) == 2:
        daten = (stdin or sys.stdin.buffer).read()
        return nur_spec(p.decode("utf-8", "surrogateescape") for p in daten.split(b"\0") if p)
    if art not in ("pr", "push") or len(argv) != 4:
        return False, f"kein PR und kein Push auf main ({art or 'ohne Art'}): voller Lauf"
    basis, kopf = argv[2], argv[3]
    if not _commit(kopf):
        return False, "Kopf-Commit unbekannt"
    if not _commit(basis):
        return False, "Vorgänger unbekannt (neuer Branch oder erster Push)"
    if art == "push":
        if _git("merge-base", "--is-ancestor", basis, kopf)[0] != 0:
            return False, "Vorgänger ist kein Vorfahr (überschriebener Push)"
        pfade = _diff(f"{basis}..{kopf}")
    else:
        pfade = _diff(f"{basis}...{kopf}")
    if pfade is None:
        return False, "git diff fehlgeschlagen"
    return nur_spec(pfade)


def main(argv):
    ja, grund = bestimmen(argv)
    print("true" if ja else "false")
    print(grund, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
