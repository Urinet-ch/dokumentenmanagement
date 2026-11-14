# Abnahme Phase 2: Chat-Prüfliste (Gate Phase 2)

Vorlage für das Ergebnis des manuellen Laufs von `ChatPrueflisteIT` gegen den Produktivbestand (Spec 02, Aufgabe 9.1,
Req 13.1 und 13.2). **Nur Fragen-Nr. und Ergebnis, keine Inhalte:** keine Fragen, Antworten, Beträge, Namen oder IDs
(AGENTS/CLAUDE.md, Regel 9). Die Prüfliste und der vollständige Bericht liegen unter `ARCHIV_GOLDEN_DIR`, nicht im
Repository. Anleitung: [backend/README.md](../../backend/README.md), «Chat-Prüfliste gegen den Produktivbestand».

**Gate Phase 2:** alle 7 Beispielfragen (Nr. 1 bis 7) und mindestens 18 von 20 Fragen insgesamt richtig und mit Quelle.
Nachweis: manuell [Fabian]; der Lauf liefert nur den Vorschlag, Fabian bestätigt jede Antwort.

**Aufbewahrung:** Die Testgespräche bleiben wie alle Gespräche regulär 12 Monate in `chat_gespraech` (Spec 02, Req 5.4)
und werden vom Chat-Aufräumer gelöscht; sie werden nicht vorzeitig entfernt.

## Lauf
| Eintrag | Wert |
| --- | --- |
| Datum | [offen: Datum des Laufs] |
| Stand der Anwendung (Commit/Image) | [offen: Commit des Deploys] |
| Zeit bis zum ersten Text, p95 (Req 3.8, Ziel höchstens 10'000 ms) | [offen: Messwert in ms] |

## Ergebnis je Frage
«Vorschlag» ist die automatische Bewertung des Laufs, «Bestätigt» das Urteil von Fabian (richtig/falsch; Quelle ja/nein).

| Nr. | Beispielfrage | Vorschlag richtig | Vorschlag Quelle | Bestätigt (Fabian) |
| ---: | --- | --- | --- | --- |
| 1 | ja | | | |
| 2 | ja | | | |
| 3 | ja | | | |
| 4 | ja | | | |
| 5 | ja | | | |
| 6 | ja | | | |
| 7 | ja | | | |
| 8 | nein | | | |
| 9 | nein | | | |
| 10 | nein | | | |
| 11 | nein | | | |
| 12 | nein | | | |
| 13 | nein | | | |
| 14 | nein | | | |
| 15 | nein | | | |
| 16 | nein | | | |
| 17 | nein | | | |
| 18 | nein | | | |
| 19 | nein | | | |
| 20 | nein | | | |

## Gesamt
| Kennzahl | Soll | Ist |
| --- | --- | --- |
| Beispielfragen (Nr. 1 bis 7) richtig und mit Quelle | 7 von 7 | [offen] |
| Fragen insgesamt richtig und mit Quelle | mindestens 18 von 20 | [offen] |
| **Gate Phase 2** | bestanden | [offen: Fabian] |
