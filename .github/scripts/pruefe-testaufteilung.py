#!/usr/bin/env python3
"""Backend-Tests über die CI-Jobs vollständig (ci.yml, Job «testzahl»; Entscheid Fabian 13.11.2026).

Die Backend-Tests laufen in drei Jobs: ohne eigenen Quarkus-Start, Startgruppe start-1, Startgruppe start-2
(StartGruppenTest). Aus den Surefire-Berichten (TEST-*.xml) der Jobs wird geprüft: Jede Testklasse des Moduls lief in
genau einem Job, kein Job ist leer, kein Test ist fehlgeschlagen. Testklasse heisst: Datei unter <testquellen> nach den
Mustern von Surefire (Test*, *Test, *Tests, *TestCase), nicht abstrakt, mit mindestens einem Test (JUnit, ArchUnit oder
Cucumber). Die Zahlen je Job gehen auch in die Zusammenfassung des Laufs (GITHUB_STEP_SUMMARY).

Aufruf: pruefe-testaufteilung.py <testquellen> <job>=<berichtsverzeichnis> ...
Ausgabe nur Klassennamen und Zahlen.
"""
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

MUSTER = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase)$")
TEST = re.compile(r"@(Test|ParameterizedTest|TestFactory|RepeatedTest|TestTemplate|ArchTest)\b|extends\s+CucumberQuarkusTest")


def erwartete_klassen(quellen):
    klassen = set()
    for datei in quellen.rglob("*.java"):
        name = datei.stem
        text = datei.read_text(encoding="utf-8")
        if MUSTER.match(name) and TEST.search(text) and not re.search(rf"abstract\s+class\s+{name}\b", text):
            klassen.add(".".join(datei.relative_to(quellen).with_suffix("").parts))
    return klassen


def berichte(verzeichnis):
    """{Klasse: (Tests, übersprungen, Fehler)} aus den TEST-*.xml eines Jobs."""
    erg = {}
    for datei in sorted(pathlib.Path(verzeichnis).rglob("TEST-*.xml")):
        suite = ET.parse(datei).getroot()
        zahl = lambda a: int(suite.get(a, "0"))
        erg[suite.get("name")] = (zahl("tests"), zahl("skipped"), zahl("failures") + zahl("errors"))
    return erg


def main(argv):
    quellen = pathlib.Path(argv[1])
    jobs = dict(arg.split("=", 1) for arg in argv[2:])
    erwartet = erwartete_klassen(quellen)
    gelaufen = {job: berichte(pfad) for job, pfad in jobs.items()}
    fehler = []
    zeilen = ["| Job | Klassen | Tests | übersprungen |", "| --- | ---: | ---: | ---: |"]
    for job, klassen in gelaufen.items():
        tests = sum(t for t, _, _ in klassen.values())
        zeilen.append(f"| {job} | {len(klassen)} | {tests} | {sum(s for _, s, _ in klassen.values())} |")
        if tests == 0:
            fehler.append(f"Job {job}: keine Tests")
        fehler += [f"Job {job}: {k} mit {f} Fehlern" for k, (_, _, f) in sorted(klassen.items()) if f]
    alle = [k for klassen in gelaufen.values() for k in klassen]
    zeilen.append(f"| Summe | {len(alle)} | {sum(t for kl in gelaufen.values() for t, _, _ in kl.values())} | "
                  f"{sum(s for kl in gelaufen.values() for _, s, _ in kl.values())} |")
    for k in sorted(erwartet):
        jobs_k = [job for job, klassen in gelaufen.items() if k in klassen]
        if len(jobs_k) != 1:
            fehler.append(f"{k}: lief in {len(jobs_k)} Jobs {jobs_k}, erwartet genau einer")
    for k in sorted(set(alle) - erwartet):
        if alle.count(k) > 1:
            fehler.append(f"{k}: lief in mehreren Jobs")
    print("\n".join(zeilen))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write("### Backend-Tests je Job\n\n" + "\n".join(zeilen) + f"\n\n{len(erwartet)} Testklassen im Modul\n")
    for f in fehler:
        print(f"::error::{f}")
    if fehler:
        return 1
    print(f"Alle {len(erwartet)} Testklassen liefen in genau einem Job.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
