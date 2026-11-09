# ADR-006: KI nur für Vorschläge, Bestätigung durch Menschen, Generatoren ohne KI

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6). Vorbehalt: Spike 0.3 (Antwortformat bei Infomaniak AI) und Golden-Lauf 0.5 (Sicherheitsschwelle für Req 7.6).

**Datum:** 08.11.2026

## Kontext
Die KI soll Art, Datum, Partner und Betrag vorschlagen, damit der Inhaber nur noch bestätigen muss
(Req 7, Konzept D4). Kräfte und Randbedingungen:
- **Fehler bleiben möglich:** Gate 0 verlangt ≥ 18 von 20 zurückgehaltenen Belegen richtig und höchstens
  1 Fehler unter Feldern mit Sicherheit ab der Schwelle (Req 0.3, 0.9, P11). Auch bestandene Gates
  lassen also Fehler zu.
- **Archiviert heisst unveränderbar:** Nach `ARCHIVIERT` sind Dateien unveränderbar, bei Arten mit
  `gesetzliche_pflicht` zusätzlich gesperrt (ADR-004), und Metadatenänderungen brauchen Version und
  Grund (Req 6.5, 10.1, ADR-007). Fehler sollen vorher auffallen.
- **Rollen:** Nur Rollen mit Recht A7 bestätigen; `mitarbeiter` erfasst nur (Req 2.6, P3).
- **Regeln:** KI rechnet nicht (tech.md Regel 5); KI nur über Infomaniak AI Services, keine anderen
  Anbieter, auch nicht als Rückfall (Regel 6); Logs nur mit IDs.
- **Generatoren** (`rechnung_gen.py`, `urinet_pdf.py`) kennen die Metadaten exakt, weil sie das PDF
  daraus erzeugen (Req 11).

## Entscheid
1. **Die KI liefert nur Vorschläge.** Genau ein Aufruf je Beleg mit festem JSON-Schema (Req 7.1); bei
   ungültiger Antwort eine Nachfrage, sonst kein Vorschlag (Req 7.3). Gespeichert werden `ki_vorschlag`
   und `ki_sicherheit`, nie bestätigte Werte.
2. **Deterministisch vor KI:** Werte des Swiss QR Codes ersetzen die KI-Werte (Req 7.11); das
   Belegnummer-Muster setzt die Art nur, wenn der Mandant Aussteller ist (Req 7.5, 7.12); Partner-Abgleich
   über E-Mail, UID, IBAN und `pg_trgm` (Req 7.4); Beispielvorauswahl ohne zweiten KI-Aufruf (Req 7.9, P10);
   `aufbewahren_bis` aus Domain-Logik (Req 9.3).
3. **Archiviert wird nur nach Bestätigung** durch einen Menschen mit Recht A7 (`EINGANGSKORB` →
   `ARCHIVIERUNG`, Req 8.2), auch bei hoher Sicherheit. Felder unter `archiv.ki.schwelle` (Startwert 0.8,
   nach Req 0.9 angehoben) zeigt der Eingangskorb mit «bitte prüfen» (Req 7.6). Bis zur Bestätigung gilt
   `PERSONAL` (Req 2.21).
4. **KI blockiert nie:** Ohne Antwort geht das Dokument ohne Vorschlag in den Eingangskorb;
   `KI_VORSCHLAG` setzt nie `FEHLER` (design.md, Verarbeitung).
5. **Lernen aus Korrekturen:** Jede abweichende Bestätigung speichert ein `ki_beispiel`; die Korrekturquote
   je Feld ist Kennzahl (Req 7.7, 16.4).
6. **Generatoren ohne KI:** `POST /dokumente/generator` prüft die Metadaten gegen die Art (Req 11.6, 11.7,
   11.10), setzt `bestaetigt` und verarbeitet mit `ohneKi = true` direkt bis `ARCHIVIERT` (Req 11.2).
7. **Drive-Import mit KI, aber ohne Abkürzung:** Importierte Dateien landen wie ein Upload im Eingangskorb,
   nie direkt im Archiv (Req 12.8).

## Betrachtete Alternativen
1. **Automatisch archivieren ab hoher Sicherheit** – abgelehnt: Das Gate lässt Fehler unter sicheren
   Feldern zu (Req 0.9); eine Fehlarchivierung bei Arten mit `gesetzliche_pflicht` ist nach der Sperre
   nur mit dem Bypass-Schlüssel des Inhabers lösbar (ADR-007; unter Option B: Inhaber-Zugang, siehe ADR-007, Nachtrag 09.11.2026), und P3 legt die Bestätigung in Menschenhand.
2. **KI auch bei Generator-Dokumenten, zur Kontrolle** – abgelehnt: Die Metadaten sind dort Quelle, keine
   Schätzung; ein Vorschlag brächte Kosten und mögliche Widersprüche ohne Nutzen.
3. **Mehrere KI-Aufrufe je Beleg** (z. B. erst Art, dann Felder, oder KI-gestützte Beispielauswahl) –
   abgelehnt: Req 7.1 und 7.9 verlangen einen Aufruf und deterministische Auswahl, damit Kosten planbar
   und Tests reproduzierbar bleiben.
4. **Ohne KI, nur Regeln** (QR-Code, Muster, Partner-Abgleich) – nicht gewählt, weil die Zeitersparnis
   kleiner wäre; bleibt Rückfall, falls Gate 0 verfehlt wird: Vorschläge nur für Art und Partner, Rest
   manuell (umsetzungsplan.md, Abschnitt 7).

## Folgen
- **Positiv:** Die Verantwortung für archivierte Metadaten liegt bei einem Menschen; Fehler fallen vor der Sperre auf.
- **Positiv:** Deterministische Teile sind mit JUnit testbar, KI-Aufrufe in Tests per WireMock (tech.md, Tests);
  Kosten pro Beleg planbar über `ki_verbrauch`.
- **Positiv:** Austausch des Modells nur über Konfiguration und den Port `KiPort` (ADR-011).
- **Negativ:** Jedes erfasste Dokument braucht eine Bestätigung; Erfassungen von `mitarbeiter` warten auf den Inhaber.
  Gemildert durch Bestätigen in höchstens zwei Tipps (Req 8.4).
- **Negativ:** Der Generator-Pfad vertraut den Skripten; Schutz nur über Validierung (422) und Belegnummer-Reservation (409).
- **Umsetzung:** Spike 0.3 (`docs/spikes/infomaniak-ai.md`): JSON-Schema-Antwort funktioniert, den JSON-Modus lehnt
  Infomaniak ab. Bilder kann Mistral-Small-4 direkt lesen (K1); design.md setzt weiterhin auf den OCR-Volltext.
- **Tests:** Der Golden-Datensatz läuft nur manuell gegen das echte Modell, nie in der CI (Req 0.2, tech.md Regel 6).

## Nachtrag 09.11.2026 (Spec 04)
Punkt 1 (genau ein Aufruf je Beleg) gilt unverändert für den Metadaten-Vorschlag; der Ingest der Wissensbasis ist ein
eigener, nachgelagerter Zweck mit mehreren Aufrufen und ändert keine Metadaten. Wissensseiten folgen demselben
Grundsatz «KI nur für Vorschläge», siehe ADR-013.

## Nachtrag 09.11.2026 (Gate 0, Modell der Erfassung)
Golden-Lauf 0.5 und Gate-Lauf liegen vor (`docs/spikes/golden-ergebnis.md`): Prüfteil 20/20 Belege mit Art, Datum,
Partner und Betrag richtig, 1 falsches Feld mit Sicherheit ab 0.8, Schwelle `archiv.ki.schwelle=0.8`. Gemessen mit
`google/gemma-4-31B-it`; Mistral-Small-4 war im Abstimmungsteil nicht gate-reif (27/30 und 24/30, keine Schwelle ≤ 1.0
mit höchstens einem Fehler). Entschieden von Fabian am 09.11.2026:
Den Metadaten-Vorschlag macht `archiv.ki.erfassung-modell=google/gemma-4-31B-it`, getrennt von `archiv.ki.chat-modell`
(Chat und Wissensbasis bleiben bei Mistral-Small-4, bis Gate 2 bzw. Gate 4 messen). Ein anderes Erfassungsmodell gilt
erst nach einem neuen Golden-Lauf (design.md «KI»). Der Satz «design.md setzt weiterhin auf den OCR-Volltext» unter
«Folgen» ist überholt: Für Bild-Originale gilt der Bildweg (Req 7.1, design.md «KI», Bildweg). Der Entscheid oben
bleibt unverändert; der Vorbehalt zur Sicherheitsschwelle ist damit erfüllt.

## Verweise
- Req 0.2, 0.3, 0.5, 0.9, 2.6 (A7), 2.21, 7.1–7.12, 8.1–8.4, 11.2, 11.6, 11.7, 11.10, 12.8, 16.4
- P3, P10, P11; tech.md Regeln 5 und 6
- Aufgaben 0.3, 0.5, 0.6, 3.2, 3.3, 3.4, 3.6, 4.7, 4.8
- ADR-001, ADR-007, ADR-009, ADR-011
