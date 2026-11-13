#!/usr/bin/env python3
"""Grüne CI für den deployten Commit nachweisen (Req 16.10; design.md «Betrieb auf der VPS», Deploy).

Als Nachweis gilt:
1. ein grüner Lauf von ci.yml auf dem Commit selbst (Push auf main oder «gh workflow run ci.yml --ref main»), oder
2. vor dem Stichtag <pr-ci-bis> ein grüner PR-Lauf von ci.yml (eingeführt, als Push auf main ohne CI lief, Entscheid
   Fabian 11.11.2026, PR #50; seit PR #58 vom 11.11.2026 läuft ci.yml wieder auf Push nach main; Auftrag PR #42, S1):
   Der Commit ist der Squash-Merge genau eines PR auf main, der Kopf dieses PR enthält
   den Vorgänger des Commits auf main und hat denselben Tree. Dann hat die PR-CI (Merge-Ref) genau den Stand geprüft,
   der deployt wird; hat sich main vor dem Merge bewegt, gilt der PR-Lauf nicht.
3. bei reinen Spec-Anpassungen (entschieden von Fabian am 13.11.2026) der Nachweis des Vorgängers: Der Commit hat
   einen grünen ci.yml-Lauf (auf dem Push nach main), in dem nur die Spec-Prüfungen liefen (Jobs ART_JOB und SPEC_JOB
   erfolgreich, alle anderen erfolgreich oder übersprungen), seine Änderung zum ersten Elternteil ist nach nur-spec.py
   (dieselbe Regel wie in ci.yml) «nur Spec», und der Vorgänger hat selbst einen Nachweis nach 1, 2 oder 3; höchstens
   HOECHSTTIEFE Commits in Folge. ci.yml wendet die Regel des Vorgängers an; hier gilt die des Commits. Beide sind
   gleich, denn ein Commit, der nur-spec.py ändert, ist nach der Regel des Vorgängers nie «nur Spec» und hat damit
   keinen Lauf nur mit Spec-Prüfungen.
Grün heisst: Lauf erfolgreich und jeder seiner Jobs erfolgreich (ein Lauf als Entwurf überspringt alle Jobs). Die
Spec-Prüfungen laufen in ci.yml auch im vollen Lauf, ein voller Lauf hat also keinen übersprungenen Job.

Aufruf: pruefe-ci-nachweis.py <owner/repo> <sha> <pr-ci-bis JJJJ-MM-TT> [heute JJJJ-MM-TT]
Daten nur über «gh api»; die Ausgabe nennt nur SHAs, PR-Nummern, Lauf-IDs und Dateipfade.
"""
import datetime
import importlib.util
import json
import pathlib
import subprocess
import sys

HILFE = "CI auf main von Hand starten (gh workflow run ci.yml --ref main), nach grünem Lauf den Deploy neu auslösen."
HOECHSTTIEFE = 20
# Die Compare-API nennt höchstens 300 Dateien; bei so vielen ist die Liste womöglich gekürzt, im Zweifel kein «nur Spec»
MAX_DATEIEN = 300


def _nur_spec_regel():
    # kein __pycache__ neben den Skripten (lokale Testläufe würden das Repo verschmutzen)
    sys.dont_write_bytecode = True
    pfad = pathlib.Path(__file__).resolve().with_name("nur-spec.py")
    spec = importlib.util.spec_from_file_location("nur_spec", pfad)
    modul = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modul)
    return modul


NUR_SPEC = _nur_spec_regel()


class Fehler(Exception):
    pass


def api(pfad):
    erg = subprocess.run(["gh", "api", pfad], capture_output=True, text=True)
    if erg.returncode != 0:
        raise Fehler(f"GitHub-API nicht lesbar ({pfad.split('?')[0]})")
    return json.loads(erg.stdout)


def gruene_laeufe(repo, sha, ereignis=None):
    """(voller Lauf, Lauf nur mit Spec-Prüfungen): IDs grüner ci.yml-Läufe auf sha, je None, wenn keiner."""
    pfad = f"repos/{repo}/actions/workflows/ci.yml/runs?head_sha={sha}&status=success&per_page=100"
    if ereignis:
        pfad += f"&event={ereignis}"
    spec = None
    for lauf in api(pfad)["workflow_runs"]:
        if lauf.get("head_sha") != sha or lauf.get("conclusion") != "success":
            continue
        jobs = api(f"repos/{repo}/actions/runs/{lauf['id']}/jobs?per_page=100")["jobs"]
        if jobs and all(job.get("conclusion") == "success" for job in jobs):
            return lauf["id"], spec
        ergebnis = {job.get("name"): job.get("conclusion") for job in jobs}
        if (spec is None and all(job.get("conclusion") in ("success", "skipped") for job in jobs)
                and ergebnis.get(NUR_SPEC.ART_JOB) == "success" and ergebnis.get(NUR_SPEC.SPEC_JOB) == "success"):
            spec = lauf["id"]
    return None, spec


def gruener_lauf(repo, sha, ereignis=None):
    return gruene_laeufe(repo, sha, ereignis)[0]


def pr_nachweis(repo, sha):
    """(Nachweis, None) oder (None, Grund)."""
    commit = api(f"repos/{repo}/commits/{sha}")
    eltern = commit["parents"]
    if len(eltern) != 1:
        return None, "Commit ist kein Squash-Merge (nicht genau ein Vorgänger)"
    prs = [pr for pr in api(f"repos/{repo}/commits/{sha}/pulls")
           if pr.get("merged_at") and pr.get("merge_commit_sha") == sha and pr["base"]["ref"] == "main"]
    if len(prs) != 1:
        return None, "kein PR auf main, dessen Squash-Merge dieser Commit ist"
    nummer, kopf = prs[0]["number"], prs[0]["head"]["sha"]
    vergleich = api(f"repos/{repo}/compare/{eltern[0]['sha']}...{kopf}")
    if vergleich["status"] not in ("ahead", "identical"):
        return None, f"Kopf {kopf[:7]} von PR #{nummer} enthält den Vorgänger auf main nicht (main hat sich bewegt)"
    if api(f"repos/{repo}/commits/{kopf}")["commit"]["tree"]["sha"] != commit["commit"]["tree"]["sha"]:
        return None, f"Kopf {kopf[:7]} von PR #{nummer} hat einen anderen Stand als der Commit"
    lauf = gruener_lauf(repo, kopf, "pull_request")
    if lauf is None:
        return None, f"kein grüner CI-Lauf für Kopf {kopf[:7]} von PR #{nummer}"
    return f"PR #{nummer}, Kopf {kopf[:7]} mit gleichem Stand, Lauf {lauf}", None


def geaenderte_pfade(repo, basis, sha):
    """Pfade der Änderung basis → sha (Umbenennungen mit beiden Pfaden); None, wenn nicht sicher bestimmbar."""
    vergleich = api(f"repos/{repo}/compare/{basis}...{sha}")
    dateien = vergleich.get("files") or []
    if vergleich.get("status") != "ahead" or len(dateien) >= MAX_DATEIEN:
        return None
    pfade = []
    for datei in dateien:
        pfade.append(datei["filename"])
        if datei.get("previous_filename"):
            pfade.append(datei["previous_filename"])
    return pfade


def nachweis(repo, sha, bis, heute, tiefe=0):
    """(Nachweis, None) oder (None, Grund); Regeln 1 bis 3 der Beschreibung oben."""
    voll, spec = gruene_laeufe(repo, sha)
    if voll is not None:
        return f"Lauf {voll} auf {sha[:7]}", None
    if heute < bis:
        pr, grund_pr = pr_nachweis(repo, sha)
        if pr is not None:
            return pr, None
        grund_pr = f"PR-CI gilt nicht: {grund_pr}"
    else:
        grund_pr = f"PR-CI gilt nur bis {bis - datetime.timedelta(days=1)}"
    if spec is None:
        return None, f"kein grüner Lauf auf {sha[:7]}; {grund_pr}"
    eltern = api(f"repos/{repo}/commits/{sha}")["parents"]
    if not eltern:
        return None, f"Lauf {spec} auf {sha[:7]} nur mit Spec-Prüfungen, aber kein Vorgänger"
    vorgaenger = eltern[0]["sha"]
    pfade = geaenderte_pfade(repo, vorgaenger, sha)
    if pfade is None:
        return None, f"Lauf {spec} auf {sha[:7]} nur mit Spec-Prüfungen, Änderung zu {vorgaenger[:7]} nicht bestimmbar"
    ja, grund = NUR_SPEC.nur_spec(pfade)
    if not ja:
        return None, f"Lauf {spec} auf {sha[:7]} nur mit Spec-Prüfungen, Änderung zu {vorgaenger[:7]}: {grund}"
    if tiefe + 1 > HOECHSTTIEFE:
        return None, (f"mehr als {HOECHSTTIEFE} Commits in Folge nur mit Spec-Prüfungen (Höchsttiefe erreicht bei "
                      f"{sha[:7]}), kein voller Lauf in Reichweite")
    geerbt, grund = nachweis(repo, vorgaenger, bis, heute, tiefe + 1)
    if tiefe > 0:
        # Kette: Grund oder Nachweis des ersten Commits mit vollem Lauf (bzw. des Abbruchs) unverändert nach oben
        return geerbt, grund
    if geerbt is None:
        return None, f"{sha[:7]} ist nur Spec, aber ohne Nachweis in der Kette der Vorgänger: {grund}"
    return f"Lauf {spec} auf {sha[:7]} (nur Spec), erbt über {vorgaenger[:7]}: {geerbt}", None


def main(argv):
    repo, sha, bis = argv[1], argv[2], datetime.date.fromisoformat(argv[3])
    if len(argv) > 4:
        heute = datetime.date.fromisoformat(argv[4])
    else:
        from zoneinfo import ZoneInfo
        heute = datetime.datetime.now(ZoneInfo("Europe/Zurich")).date()
    try:
        ergebnis, grund = nachweis(repo, sha, bis, heute)
    except Fehler as e:
        print(f"::error::CI-Nachweis für {sha[:7]} nicht prüfbar: {e}. Deploy später neu auslösen.")
        return 1
    if ergebnis is None:
        print(f"::error::Kein grüner CI-Lauf für {sha[:7]}: {grund}. " + HILFE)
        return 1
    print(f"CI grün für den Stand von {sha[:7]}: {ergebnis}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
