# ADR-020: Produktivbetrieb auf der bestehenden VPS (Variante B)

**Status:** Angenommen im Kern – entschieden von Fabian am 28.11.2026 (Variante B aus der Entscheidungshilfe
PR #731); die Schutzmassnahmen und Gates sind vorgeschlagen 28.11.2026, entschieden mit dem Merge des Spec-PR.
Löst ADR-010 teilweise ab (Punkte 5 und 6 sowie die Alternative «Dauerhaft auf der bestehenden VPS»).

**Datum:** 28.11.2026

## Kontext
- ADR-010 sah zwei Plattformen vor: Stufe A auf der bestehenden VPS nur mit Testdaten und Stufe B auf einer
  eigenen VPS, bevor echte Belege ins Archiv kommen (Req 16.15, 16.17). Begründung: Die bestehende VPS teilt das Netz
  «web» mit anderen Apps und hatte keine Reserven.
- Die VPS wurde am 23.11.2026 erweitert (tech.md); Fabian hat am 24.11.2026 entschieden, sie so zu belassen. Das
  Budget der Stufe A beträgt seit dem Entscheid vom 27.11.2026 höchstens 2048 MiB und 1.5 CPU (ADR-010, Nachtrag
  27.11.2026); für den Dienst `sftp` kommen +96 MiB und +0.1 CPU dazu (Spec 09, E11, entschieden von Fabian am 28.11.2026), zusammen
  2144 MiB und 1.6 CPU.
- Spec 09 legt den Stichtag auf den 01.01.2027 und den Import des Bestands 2026 bis 31.01.2027 (E7). Die
  Entscheidungshilfe `docs/betrieb/start-echtbelege.md` (PR #731) stellte drei Varianten zur Wahl: A (Pilot mit
  Kopien), B (bestehende VPS wird produktiv), C (Stufe B auf eigener VPS nach Spec).

## Entscheid
1. **Variante B** (Fabian, 28.11.2026): Die bestehende VPS wird produktiv. Eine zweite VPS wird nicht bestellt. Der
   Stichtag bleibt der 01.01.2027, der Bestand 2026 ist bis 31.01.2027 importiert.
2. **Stufe B bleibt als Begriff:** Stufe B heisst Produktivbetrieb (`archiv.stufe=B`, produktive Sperren, Sicherung
   mit Aufbewahrung), jetzt auf derselben VPS wie Stufe A. Stufe A endet mit dem Wechsel (Req 16.17).
3. **Gates vor dem ersten echten Beleg** (Spec 01, Welle 31): Testdaten der Stufe A verwerfen, Stufenwerte der
   Stufe B, produktiver Bucket und Zeitstempeldienst, Sicherung mit Zweitkopie und grüne Restore-Probe, Überwachung,
   Speichermessung, Schutzmassnahmen; Go/No-go nach `docs/betrieb/start-echtbelege.md`.
4. **Schutzmassnahmen gegen die geteilte VPS:** nur `api` und `kc` am Netz «web», übrige Dienste nur intern, kein
   Zugriff anderer Apps an Traefik vorbei (Req 16.28); Geheimnisse nur im Archiv (Req 16.29); Limits im Budget und
   Alarm bei Speicherabbruch oder kranker anderer App (Req 16.30); Wiederaufbau auf einer neuen VPS beschrieben
   (Req 16.31).
5. **Aufbau wie Stufe A:** API und Worker in einem Container, Deploy-Ziel und DNS unverändert. Ein getrennter Worker
   braucht einen Entscheid über das Budget.

## Betrachtete Alternativen
- **Variante A – Pilot mit Kopien echter Belege auf Stufe A:** Nicht gewählt. Kopien echter Belege sind echte Belege;
  Stufe A mit verkürzten Sperren und Testschlüsseln ist dafür nicht gebaut.
- **Variante C – Stufe B auf einer eigenen VPS nach ADR-010:** Nicht gewählt. Zusätzliche Bestellung, Einrichtung,
  DNS-Wechsel und Rückbau der Stufe A vor dem Stichtag; Fabian hat die VPS am 24.11.2026 bewusst unverändert gelassen.
- **Stichtag verschieben:** Nicht gewählt; der Stichtag 01.01.2027 bleibt (Spec 09, E7).

## Folgen
- **Positiv:** Keine zweite Plattform, kein DNS- und Deploy-Ziel-Wechsel; die Erfahrungen aus Stufe A gelten direkt
  für die Produktion.
- **Negativ, Risiken:**
  - Das Archiv mit echten Belegen teilt die VPS mit anderen Apps. Eine Schwachstelle in einer anderen App oder im
    Betriebssystem trifft auch das Archiv; Container am Netz «web» erreichen `api` und `kc` heute an Traefik vorbei
    (Req 16.28, Weg offen).
  - Eine einzige VPS ohne Ausweichmaschine: Fällt sie aus, ist das Archiv bis zum Wiederaufbau nicht erreichbar.
    Die Daten tragen Object Lock, Manifeste, Zweitkopie und Restore-Probe (ADR-007, ADR-008); der Wiederaufbau ist
    beschrieben (Req 16.31).
  - Ressourcen: Das Archiv bleibt im Budget von 2144 MiB / 1.6 CPU inklusive `sftp`; OCR teilt sich den Container mit der API, und die
    Restore-Probe braucht ein eigenes Budget oder ein kurzes Anhalten der API (Req 16.30). Ob die Leistungsziele
    (Req 16.16) damit erreicht werden, zeigt erst die Messung nach Req 16.26.
  - Der private Probe-Schlüssel liegt auf einer VPS, die auch andere Apps betreibt (nur root-lesbar, Req 16.29).
  - Der Zeitplan bis zum Stichtag ist knapp: Der Wechsel folgt auf den synthetischen Probelauf (Spec 09, 29.10).
- **Umsetzung:** Spec 01, Welle 31 (31.1–31.9); Spec 09, 30.1 setzt sie voraus.

## Verweise
- Spec 01, Req 16.1, 16.3, 16.15, 16.17, 16.18, 16.26, 16.28–16.31; Aufgaben 1.10, 5.4, 5.5, 5.8, 5.9, 5.11, 31.1–31.9
- Spec 09, Req 16.2, E7, E11; Aufgabe 30.1
- `docs/betrieb/start-echtbelege.md`; ADR-007, ADR-008, ADR-010
