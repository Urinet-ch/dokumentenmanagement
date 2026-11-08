# ADR-001: Eigenbau statt Paperless-ngx

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6). Dort wird auch Konzept-Entscheid E2 bestätigt.

**Datum:** 08.11.2026

## Kontext
Das UriNet Archiv soll jedes Dokument einmal erfassen, mit Metadaten versehen, unveränderbar ablegen
und danach in Sekunden wiederfinden (product.md). Das Konzept vergleicht dafür Varianten; die Specs
gehen von Variante B (Eigenbau) aus, die Bestätigung steht als Konzept-Entscheid E2 noch [offen]
(umsetzungsplan.md, Abschnitt 6). Kräfte und Randbedingungen aus Steering und Spec 01:
- **Mandanten:** eigenes PostgreSQL-Schema und eigener Bucket je Mandant (Req 1.1); ein neuer
  Mandant ist Konfiguration, kein neues Projekt (product.md). VHF/GSK folgt mit öffentlicher
  Schnittstelle und WordPress-Plugin (Spec 03).
- **GeBüV:** SHA-256 bei Eingang (Req 6.1), Object Lock nur bei `gesetzliche_pflicht` (Req 6.3, 6.12),
  tägliches signiertes Manifest mit Hash-Kette und RFC-3161-Zeitstempel (Req 15.1–15.3),
  INSERT-only-Protokolle (Req 10.3).
- **Rechte:** Rechtematrix A1–A14 mit Vertraulichkeit `NORMAL`/`INTERN`/`PERSONAL` und 404 vor 403
  (Req 2.3, 2.6).
- **Fachlogik:** KI-Vorschläge mit Sicherheit je Feld, Swiss QR Code, Lernen aus Korrekturen (Req 7),
  Generator-Schnittstelle mit Belegnummer-Reservation (Req 11), CSV-Import aus 21.CRM (Req 13),
  später Chat mit Werkzeugen, bei dem die KI nicht rechnet (tech.md Regel 5).
- **Daten in der Schweiz:** Speicher, Datenbank und KI nur bei Infomaniak (tech.md Regel 6).
- **Aufwand:** Nebenerwerb, rund 35 Arbeitstage mit Agenten (umsetzungsplan.md, Abschnitte 4 und 7).

## Entscheid
Das Archiv wird als eigener Dienst gebaut: Quarkus (Java 25) in den Laufarten API und Worker, Angular-PWA
über Quinoa im selben Artefakt, PostgreSQL, Keycloak, Infomaniak Object Storage und Infomaniak AI
Services (design.md, Overview; tech.md, Stack). Bewährte Open-Source-Bausteine übernehmen die
Einzelaufgaben, für die kein Eigenbau nötig ist: OCRmyPDF und Tesseract (OCR, PDF/A), Apache Tika
(Typ, Text), ZXing (Swiss QR Code), jscanify (Kanten im Browser), Keycloak (Anmeldung, TOTP),
LangChain4j (KI-Anbindung). Eigen sind Datenmodell, Mandantentrennung, Rechte, Ablage, Manifest,
Eingangskorb und Schnittstellen.

## Betrachtete Alternativen
Angaben zu Paperless-ngx beruhen auf allgemeinem Fachwissen (Stand 2.x), nicht auf einem Spike.

1. **Paperless-ngx selbst betreiben** – abgelehnt.
   - Paperless-ngx kennt Benutzer, Gruppen und Rechte pro Objekt, aber keine getrennten Mandanten;
     ein zweiter Mandant hiesse eine zweite Installation statt Konfiguration (Req 1.1, product.md).
   - Dateien liegen im lokalen Medienverzeichnis; eine Object-Lock-Sperre pro Objekt, ein signiertes
     Manifest mit Hash-Kette und RFC-3161-Zeitstempel fehlen und müssten daneben gebaut werden
     (Req 6.3, 15.1–15.4).
   - Die automatische Zuordnung lernt Korrespondent, Typ und Tags, liefert aber keine Felder wie
     Betrag, Belegnummer und Fälligkeit mit Sicherheit je Feld und keinen Eingangskorb nach
     Rechtematrix (Req 2.6, 7.2, 8.1); ebenso fehlen Belegnummer-Reservation und 21.CRM-Import
     (Req 11.3, 13).
   - Python/Django mit Celery und Redis als Broker ist ein zweiter Stack neben dem Referenz-Stack und
     belastet das Speicherbudget der Stufe A (tech.md).
2. **Paperless-ngx für Erfassung und OCR, eigener Dienst für Archiv und Fachlogik** – abgelehnt. Zwei
   Systeme hielten Dateien und Metadaten; die Prüfsumme bei erstem Empfang und das Protokoll gehören
   aber ins Archiv (tech.md Regel 3 und 4). Betrieb, Sicherung und Rechte wären doppelt.
3. **Fertiges DMS als Mietlösung** – abgelehnt, weil Speicher, Datenbank und KI nur bei Infomaniak
   liegen dürfen (tech.md Regel 6) und die öffentliche Schnittstelle für VHF/GSK (Spec 03) eigene
   Regeln braucht.

## Folgen
- **Positiv:** Die Requirements lassen sich direkt umsetzen und mit `@req`-Szenarien prüfen (tech.md, Tests).
- **Positiv:** Ein Datenmodell, ein Deploy, eine Sicherung (ADR-008); ein neuer Mandant ist Konfiguration (ADR-002).
- **Positiv:** Die OCR-Qualität entspricht der von Paperless-ngx, weil beide OCRmyPDF und Tesseract nutzen.
- **Negativ:** Eigener Aufwand für Oberfläche, Verarbeitung und Betrieb; Risiko Zeitbudget im Nebenerwerb. Die Wellen
  sind einzeln abschliessbar, Phase 2 und 3 verschiebbar (umsetzungsplan.md, Abschnitt 7).
- **Negativ:** Sicherheit und Pflege liegen bei Urinet. Gemildert durch Keycloak statt eigener Anmeldung,
  hexagonale Struktur mit Dev-Stubs (ADR-011) und ArchUnit.
- **Negativ:** Die Handy-Erfassung muss selbst gebaut werden (ADR-005).
- **Umsetzung:** Es gilt die Definition of Done aus tech.md, auch für Tests; Spike-Erkenntnisse aus
  Welle 0 fliessen per PR in design.md zurück, bevor Welle 1 startet.

## Verweise
- Konzept-Entscheid E2 (umsetzungsplan.md, Abschnitt 6); Aufgabe 0.6
- Req 1.1, 1.5, 2.6, 6.1, 6.3, 7, 10.3, 11, 13, 15.1–15.4, 16.13
- tech.md Regeln 3–6; product.md (Mandanten, Abgrenzung)
- ADR-002, ADR-003, ADR-004, ADR-005, ADR-006, ADR-007, ADR-008, ADR-011
