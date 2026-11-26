#!/usr/bin/env python3
"""Quote roter Browser-Jobs in den letzten CI-Läufen (docs/ci-browser-stabilitaet.md).

Aufruf: python3 tools/ci_browser_quote.py [Anzahl Läufe, Standard 80] [--seit ISO-Zeit]
Braucht die GitHub-CLI (`gh`). Zählt Läufe des Workflows «CI», in denen der Browser-Job ausgeführt wurde.
"""
import json
import subprocess
import sys
from datetime import datetime


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=False).stdout


def lauf_jobs(lauf_id):
    try:
        return json.loads(gh("run", "view", str(lauf_id), "--json", "jobs"))["jobs"]
    except (json.JSONDecodeError, KeyError):
        return []


def auswerten(laeufe, seit=None):
    mit_browser = browser_rot = nur_browser_rot = 0
    for lauf in laeufe:
        if lauf.get("conclusion") not in ("success", "failure"):
            continue
        if seit and lauf["createdAt"] < seit:
            continue
        jobs = lauf_jobs(lauf["databaseId"])
        browser = [j for j in jobs if j["name"].startswith("Browser")]
        if not browser or browser[0].get("conclusion") in (None, "", "skipped"):
            continue
        mit_browser += 1
        rote = [j["name"] for j in jobs if j.get("conclusion") == "failure"]
        if browser[0]["conclusion"] == "failure":
            browser_rot += 1
        if rote and rote == [browser[0]["name"]]:
            nur_browser_rot += 1
    return mit_browser, browser_rot, nur_browser_rot


def main():
    argumente = sys.argv[1:]
    seit = None
    if "--seit" in argumente:
        i = argumente.index("--seit")
        seit = argumente[i + 1]
        del argumente[i:i + 2]
        datetime.fromisoformat(seit.replace("Z", "+00:00"))
    anzahl = argumente[0] if argumente else "80"
    laeufe = json.loads(gh("run", "list", "--workflow", "CI", "--limit", anzahl, "--json", "databaseId,conclusion,createdAt"))
    mit, rot, nur = auswerten(laeufe, seit)
    quote = f"{100 * rot / mit:.0f} %" if mit else "-"
    print(f"Läufe mit Browser-Job: {mit}; Browser rot: {rot} ({quote}); nur Browser rot: {nur}")


if __name__ == "__main__":
    main()
