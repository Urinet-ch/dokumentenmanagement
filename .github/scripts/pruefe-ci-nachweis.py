#!/usr/bin/env python3
"""Grüne CI für den deployten Commit nachweisen (Req 16.10; design.md «Betrieb auf der VPS», Deploy).

Als Nachweis gilt:
1. ein grüner Lauf von ci.yml auf dem Commit selbst (Push auf main oder «gh workflow run ci.yml --ref main»), oder
2. vor dem Stichtag <pr-ci-bis> (Push auf main ohne CI, Entscheid Fabian 11.11.2026, PR #50; Auftrag PR #42, S1)
   ein grüner PR-Lauf von ci.yml: Der Commit ist der Squash-Merge genau eines PR auf main, der Kopf dieses PR enthält
   den Vorgänger des Commits auf main und hat denselben Tree. Dann hat die PR-CI (Merge-Ref) genau den Stand geprüft,
   der deployt wird; hat sich main vor dem Merge bewegt, gilt der PR-Lauf nicht.
Grün heisst: Lauf erfolgreich und jeder seiner Jobs erfolgreich (ein Lauf als Entwurf überspringt alle Jobs).

Aufruf: pruefe-ci-nachweis.py <owner/repo> <sha> <pr-ci-bis JJJJ-MM-TT> [heute JJJJ-MM-TT]
Daten nur über «gh api»; die Ausgabe nennt nur SHAs, PR-Nummern und Lauf-IDs.
"""
import datetime
import json
import subprocess
import sys

HILFE = "CI auf main von Hand starten (gh workflow run ci.yml --ref main), nach grünem Lauf den Deploy neu auslösen."


class Fehler(Exception):
    pass


def api(pfad):
    erg = subprocess.run(["gh", "api", pfad], capture_output=True, text=True)
    if erg.returncode != 0:
        raise Fehler(f"GitHub-API nicht lesbar ({pfad.split('?')[0]})")
    return json.loads(erg.stdout)


def gruener_lauf(repo, sha, ereignis=None):
    pfad = f"repos/{repo}/actions/workflows/ci.yml/runs?head_sha={sha}&status=success&per_page=100"
    if ereignis:
        pfad += f"&event={ereignis}"
    for lauf in api(pfad)["workflow_runs"]:
        if lauf.get("head_sha") != sha or lauf.get("conclusion") != "success":
            continue
        jobs = api(f"repos/{repo}/actions/runs/{lauf['id']}/jobs?per_page=100")["jobs"]
        if jobs and all(job.get("conclusion") == "success" for job in jobs):
            return lauf["id"]
    return None


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


def main(argv):
    repo, sha, bis = argv[1], argv[2], datetime.date.fromisoformat(argv[3])
    if len(argv) > 4:
        heute = datetime.date.fromisoformat(argv[4])
    else:
        from zoneinfo import ZoneInfo
        heute = datetime.datetime.now(ZoneInfo("Europe/Zurich")).date()
    try:
        lauf = gruener_lauf(repo, sha)
        if lauf is not None:
            print(f"CI grün auf {sha[:7]}: Lauf {lauf}")
            return 0
        if heute >= bis:
            print(f"::error::Kein grüner CI-Lauf für {sha[:7]} (PR-CI gilt nur bis {bis - datetime.timedelta(days=1)}). "
                  + HILFE)
            return 1
        nachweis, grund = pr_nachweis(repo, sha)
    except Fehler as e:
        print(f"::error::CI-Nachweis für {sha[:7]} nicht prüfbar: {e}. Deploy später neu auslösen.")
        return 1
    if nachweis is None:
        print(f"::error::Kein grüner CI-Lauf für {sha[:7]}; PR-CI gilt nicht: {grund}. " + HILFE)
        return 1
    print(f"CI grün für den Stand von {sha[:7]} (bis {bis - datetime.timedelta(days=1)}): {nachweis}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
