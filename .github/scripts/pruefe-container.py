#!/usr/bin/env python3
"""Container des Archivs aus «deploy-app.sh status» prüfen (Aufgabe 1.9, Req 16.10, 16.18).

  pruefe-container.py gesund <merkdatei>        < status
      api, db und kc laufen und sind healthy. Merkt den Zeitpunkt und die Laufzeit je Dienst in <merkdatei>.
  pruefe-container.py unveraendert <merkdatei>  < status
      Wieder alle drei healthy, und keiner lief kürzer, als seit «gesund» vergangen ist. Sonst wurde ein Container
      neu gestartet, z. B. nach einem cgroup-OOM mit «restart: unless-stopped».

Ausgewertet werden nur die Zeilen von «compose ps» (Containername am Zeilenanfang), nicht die Log-Zeilen
(«name | …»); ausgegeben werden nur Namen, Status und Laufzeiten, nie Log-Inhalte.
Docker rundet die Laufzeit («Up 3 minutes»), darum zählt sie als Spanne: Ein Neustart gilt, wenn die längste mögliche
Laufzeit jetzt kürzer ist als die kürzeste mögliche bei «gesund» plus die vergangene Zeit (Toleranz 15 s für die
SSH-Aufrufe). So meldet die Rundung keinen Neustart, der keiner war.
Exit 0 = in Ordnung, 1 = Befund, 2 = Aufruffehler.
"""
import json
import re
import sys
import time

DIENSTE = ("api", "db", "kc")
TOLERANZ_S = 15
ZEILE = re.compile(r"^archiv-(api|db|kc)-\d+\s+([^|\s].*)$")
LAUFT = re.compile(r"\bUp (Less than a second|About a minute|About an hour|\d+ [a-z]+)"
                   r"(?: \((healthy|unhealthy|health: starting)\))?(?:\s|$)")
EINHEIT = {"second": 1, "minute": 60, "hour": 3600, "day": 86400, "week": 7 * 86400, "month": 30 * 86400,
           "year": 365 * 86400}


def spanne(text):
    """Kleinste und grösste Laufzeit in Sekunden, die Docker als «Up <text>» anzeigt (go-units HumanDuration)."""
    fest = {"Less than a second": (0, 1), "About a minute": (60, 120), "About an hour": (3600, 5400)}
    if text in fest:
        return fest[text]
    zahl, einheit = text.split(" ")
    einheit = einheit.removesuffix("s")
    n, s = int(zahl), EINHEIT[einheit]
    if einheit in ("second", "minute"):
        return n * s, (n + 1) * s
    # Stunden rundet Docker auf die nächste ganze, Tage und mehr leitet es daraus ab
    return max(0, n * s - 1800), (n + 1) * s + 1800


def lesen(text):
    """{dienst: (gesund, (min, max) oder None, status)} aus der Ausgabe von «status»."""
    stand = {}
    for zeile in text.splitlines():
        z = ZEILE.match(zeile)
        if not z:
            continue
        lauf = LAUFT.search(z[2])
        try:
            dauer = spanne(lauf[1]) if lauf else None
        except (KeyError, ValueError):
            dauer = None
        if dauer:
            stand[z[1]] = (lauf[2] == "healthy", dauer, f"Up {lauf[1]}" + (f" ({lauf[2]})" if lauf[2] else ""))
        else:
            stand[z[1]] = (False, None, "läuft nicht")
    return stand


def main(argv):
    if len(argv) != 3 or argv[1] not in ("gesund", "unveraendert"):
        print(__doc__, file=sys.stderr)
        return 2
    modus, merkdatei = argv[1], argv[2]
    jetzt = time.time()
    stand = lesen(sys.stdin.read())
    befund = False
    if modus == "unveraendert":
        with open(merkdatei, encoding="utf-8") as f:
            merk = json.load(f)
        vergangen = jetzt - merk["zeit"]
    for d in DIENSTE:
        gesund, lauf, status = stand.get(d, (False, None, "fehlt"))
        meldung = f"archiv-{d}: {status}"
        if not gesund:
            befund = True
        elif modus == "unveraendert" and lauf[1] + TOLERANZ_S < merk["laufzeit_min"][d] + vergangen:
            meldung += f", neu gestartet seit «Container gesund» (vor {vergangen:.0f} s)"
            befund = True
        print(meldung)
    if modus == "gesund" and not befund:
        with open(merkdatei, "w", encoding="utf-8") as f:
            json.dump({"zeit": jetzt, "laufzeit_min": {d: stand[d][1][0] for d in DIENSTE}}, f)
    return 1 if befund else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
