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

## Leitsatz
Jedes Dokument einmal erfassen, mit Metadaten versehen, unveränderbar ablegen – und es danach
in Sekunden wiederfinden.

## Benutzer und Rollen
| Rolle | Wer | Darf |
| --- | --- | --- |
| `inhaber` | Fabian Aschwanden | alles im eigenen Mandanten |
| `mitarbeiter` | Joel Aschwanden | erfassen, Projekt- und eigene Dokumente lesen; keine Dokumente mit Vertraulichkeit `personal` ausser den eigenen |
| `treuhaender` | externer Treuhänder | nur lesen und Jahresexport |
| `geschaeftsstelle` | VHF/GSK (Mandant 2) | Dokumente des eigenen Mandanten erfassen und pflegen |

## Mandanten
- Mandant 1: `urinet` (ab Phase 1).
- Mandant 2: `vhf-gsk` – Bulletin-/Merkblatt-Archiv mit Suche und Chatbot für die WordPress-Seite (Phase 3).
- Ein neuer Mandant ist Konfiguration, kein neues Projekt.

## Abgrenzung
- AbaNinja bleibt führend für Buchhaltung und Zahlstatus. Das Archiv markiert **nie** eine Rechnung als bezahlt und schreibt keine Zahlungen nach AbaNinja.
- 21.CRM bleibt führend für Kontakte und Akquise. Zum Start kommen Kontakte per CSV-Export ins Archiv.
- Das Archiv ist weder Buchhaltung noch CRM noch Redaktionssystem (WordPress bleibt für VHF/GSK).

## Phasen und Specs
| Phase | Spec | Inhalt | Gate |
| --- | --- | --- | --- |
| 0 Klären | `01-archiv-kern`, Welle 0 | Spikes, Golden-Datensatz, Test-Export 21.CRM | ≥ 45 von 50 Belegen richtig erkannt |
| 1 Archiv-Kern | `01-archiv-kern`, Wellen 1–5 | Erfassung, OCR, KI-Vorschläge, Eingangskorb, Suche, GeBüV-Ablage, CRM-CSV-Import | Belege 2026 im Archiv, Prüflauf ohne Abweichung |
| 2 Abgleich und Chat | `02-abgleich-chat` | E-Mail-Eingang, semantische Suche, Chat-Agent, Jahresexport, AbaNinja (nur mit Abo Basic) | 7 Beispielfragen richtig und mit Quelle |
| 3 Zweiter Mandant | `03-zweiter-mandant` | VHF/GSK: Mandant, öffentliche Schnittstelle, WordPress-Plugin, Chatbot | VHF/GSK-Archiv online |

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
