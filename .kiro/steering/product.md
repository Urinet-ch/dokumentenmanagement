---
inclusion: always
---

# Produkt: UriNet Archiv

## Zweck
Das UriNet Archiv digitalisiert alle Dokumente der Firma Urinet Aschwanden (Seedorf UR) –
Papier und digital – und legt sie revisionssicher mit Metadaten ab. Jedes Dokument wird
einmal erfasst und ist danach per Suche oder per Frage im Chat auffindbar, immer mit Quelle.

Grundlage: Konzept «UriNet Archiv — Konzept und Anforderungen» vom 08.11.2026
(https://claude.ai/artifact/6mjnH1zM56T1AphxAQq88h). Die Anforderungs-IDs des Konzepts
(D1–D8, I1–I6, S1–S2, C1–C3, M1–M4, B1–B4) stehen in jeder Spec in Klammern hinter der Anforderung.
Spec 04 trägt keine Konzept-ID; ihre Grundlage ist der Auftrag von Fabian vom 09.11.2026.
Das Konzept ist für Claude-Code-Sitzungen nicht erreichbar: Massgeblich sind die Steering-Dateien
und die freigegebenen Specs unter `.kiro/specs/` (Kiro-Format, umgesetzt mit Claude Code, siehe `CLAUDE.md`).

## Leitsatz
Jedes Dokument einmal erfassen, mit Metadaten versehen, unveränderbar ablegen – und es danach
in Sekunden wiederfinden.

## Benutzer und Rollen
| Rolle | Wer | Darf |
| --- | --- | --- |
| `inhaber` | Fabian Aschwanden | alles im eigenen Mandanten; im Mandanten `urinet` als Einziger bestätigen, Vertraulichkeit ändern und verwerfen |
| `mitarbeiter` | Joel Aschwanden | erfassen; lesen nur `NORMAL` und die eigenen `PERSONAL`-Dokumente; nicht bestätigen, ändern oder verwerfen |
| `treuhaender` | externer Treuhänder | nur lesen (`NORMAL`, `INTERN`, `PERSONAL` – Lohnabrechnungen für den Abschluss) und Jahresexport |
| `geschaeftsstelle` | VHF/GSK (Mandant 2) | Dokumente des eigenen Mandanten erfassen und pflegen |

Vertraulichkeit (`NORMAL` | `INTERN` | `PERSONAL`):
- `NORMAL` – alle Rollen des Mandanten.
- `INTERN` – `inhaber` und `treuhaender` sowie `geschaeftsstelle` (Mandant 2).
- `PERSONAL` – `inhaber`, `treuhaender` (nur lesend), `geschaeftsstelle` (Mandant 2) und der Mitarbeiter, den das Dokument betrifft (`person_benutzer`). Ein Dokument ohne bestätigte Art gilt als `PERSONAL` und ist nur für Rollen mit Recht A7 und den Erfasser sichtbar.

Verbindlich ist die Matrix Rolle × Aktion in Requirement 2 von Spec 01; jede Zeile ist ein testbares Kriterium.
Die Wissensbasis (Spec 04) gibt der `inhaber` frei, im Mandanten `vhf-gsk` die `geschaeftsstelle` (Recht A17;
Zeilen A15–A19 in Spec 04, Requirement 12.1).

## Mandanten
- Mandant 1: `urinet` (ab Phase 1).
- Mandant 2: `vhf-gsk` – Bulletin-/Merkblatt-Archiv mit Suche und Chatbot für die WordPress-Seite (Phase 3); die Webseite bindet das Archiv über die Webseiten-Schnittstelle ein und kann auch die Erfassung der Geschäftsstelle übernehmen (Spec 03, ergänzt 13.11.2026).
- Ein neuer Mandant ist Konfiguration, kein neues Projekt.

## Abgrenzung
- AbaNinja bleibt führend für Buchhaltung und Zahlstatus. Das Archiv markiert **nie** eine Rechnung als bezahlt und schreibt keine Zahlungen nach AbaNinja.
- 21.CRM bleibt führend für Kontakte und Akquise. Zum Start kommen Kontakte per CSV-Export ins Archiv.
- Das Archiv ist weder Buchhaltung noch CRM noch Redaktionssystem (WordPress bleibt für VHF/GSK).

## Phasen und Specs
| Phase | Spec | Inhalt | Gate |
| --- | --- | --- | --- |
| 0 Klären | `01-archiv-kern`, Welle 0 | Spikes, Golden-Datensatz, Test-Export 21.CRM | ≥ 18 von 20 zurückgehaltenen Belegen richtig erkannt (30 zum Abstimmen), höchstens 1 Fehler unter den sicheren Feldern |
| 1 Archiv-Kern | `01-archiv-kern`, Wellen 1–5 | Erfassung, OCR, KI-Vorschläge, Eingangskorb, Suche, GeBüV-Ablage, CRM-CSV-Import | Belege 2026 im Archiv, Prüflauf ohne Abweichung |
| 2 Abgleich und Chat | `02-abgleich-chat` | E-Mail-Eingang, semantische Suche, Chat-Agent, Jahresexport, AbaNinja (nur mit Abo Basic) | 7 Beispielfragen richtig und mit Quelle |
| 3 Zweiter Mandant | `03-zweiter-mandant` | VHF/GSK: Mandant, Webseiten-Schnittstelle (Lesen und Erfassung, versionierter Vertrag), WordPress-Plugin, Chatbot | VHF/GSK-Archiv online |
| 4 Wissensbasis | `04-wissensbasis` | Wissensbasis nach LLM-Wiki-Muster | Gate Phase 4: 5 Wissensquellen, ≥ 47 von 50 Fussnoten belegt, ≥ 14 von 15 Fragen mit Seite und Quelldokument richtig |

## Beispielfragen (Abnahme Chat, Phase 2)
1. «Zeig mir die letzte Rechnung an den FC Schattdorf.»
2. «Wie viel habe ich in den letzten drei Jahren eingenommen – pro Jahr und pro Kunde?»
3. «Welche Rechnungen sind offen oder überfällig?»
4. «Wie hoch ist die UVG-Prämie und wann ist die Lohndeklaration fällig?»
5. «Wo ist der Arbeitsvertrag von Joel?»
6. «Was haben wir dem SC Buochs offeriert, und gilt die Offerte noch?»
7. «Welche Belege fehlen noch für den Abschluss 2026?»

## Sprache und Ton
- Oberfläche, Fehlermeldungen, Dokumentation: Deutsch (Schweiz), **ss statt ß**, Guillemets «…».
- Beträge `CHF 1'234.50` (Apostroph als Tausendertrennzeichen), Datum `08.11.2026`.
- Fehlermeldungen sagen, was passiert ist und was der Benutzer tun kann – ohne Stacktrace.
