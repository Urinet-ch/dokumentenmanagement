# ADR-019: Fachliche Dubletten mit Hauptfassung statt Löschen

**Status:** Vorgeschlagen – Freigabe per Merge des Spec-PR von Spec 09.

**Datum:** 28.11.2026

## Kontext
- Derselbe Beleg kommt beim Produktivimport aus mehreren Quellen (Generator, E-Mail, Gmail, Download, Drive, Scan) mit
  verschiedener Prüfsumme. Die Dublettenprüfung nach Spec 01, 3.4 erkennt nur gleiche Dateien.
- Archivierte Dateien sind unveränderbar (tech.md Regel 2); Kennzahlen und Lückenbericht müssen jeden Beleg einmal zählen.

## Entscheid
- Das Archiv sucht fachliche Dubletten in drei Stufen (`SICHER`, `WAHRSCHEINLICH`, `MOEGLICH`) und schlägt nach dem Rang
  der Quelle vor; es verwirft nie selbständig. Ein Mensch mit A7 verknüpft (Fassung, unterschriebene Fassung, Anhang),
  verneint oder wechselt die Hauptfassung, mit A9 verwirft er.
- Je Beleg gibt es genau eine Hauptfassung; Auswertungen, Regeln und Export zählen nur Hauptfassungen.
- Eine offene Dublette `SICHER` sperrt die Bestätigung.

## Folgen
- Neue Tabellen `dublette`, `dokument_beziehung`, Spalte `hauptfassung`; bestehende Abfragen erhalten einen Filter.
- Belegnummer-Aliasse (Altbestand) werden gepflegt.

## Verweise
Spec 09, Requirement 11; Spec 05, Requirement 5.
