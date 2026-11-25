# Chat-Prüfliste – Vorbereitung

**Status: vorbereitet, nicht ausgeführt.** Diese Checkliste ist für den manuellen Lauf von Aufgabe 9.1
(Spec 02, Req 13.1–13.2) durch Fabian. Sie enthält bewusst keine Fragen, Antworten, Namen, Dokument-IDs
oder erwarteten Werte. Die ausführliche Prüfliste mit Erwartungen bleibt ausserhalb des Repositories.

## Schutz und Voraussetzungen

- [ ] Fabian hat den Lauf freigegeben und einen aktuellen, für den Test geeigneten Stand bestimmt.
- [ ] Es werden ausschliesslich synthetische Dokumente, Benutzer, Kontakte, Beträge, Daten und IDs verwendet.
- [ ] Die Testdaten enthalten alle für die Fragen benötigten Fakten und Quellen; keine echten Dokumente oder
  Personendaten sind im Testmandanten vorhanden.
- [ ] Der Chat verwendet das konfigurierte Infomaniak-Modell. Ein Lauf mit LM Studio oder einem anderen lokalen
  Modell ist kein Abnahmenachweis.
- [ ] Die externe Prüfliste enthält mindestens 20 Fragen: die sieben vorgeschriebenen Beispielfragen sowie
  mindestens 13 zusätzliche Fragen mit erwarteten Kernwerten und Quellen. Für jede Frage gibt es passende
  synthetische Testdaten.
- [ ] Es ist ein Testzugang mit der Rolle `inhaber` bereit. Zugangsdaten und Tokens werden nicht in dieses Protokoll,
  in Screenshots oder in CI-Ausgaben geschrieben.

**Widerspruch bei den Testdaten:** Falls eine vorgeschriebene Frage nur mit echten Daten beantwortbar wäre, diese
Frage nicht mit Echtdaten ausführen. Lauf anhalten und als offen an Fabian melden.

## Ablauf

1. Stand, Modell und synthetischen Testbestand prüfen. Fehlt eine Voraussetzung, Lauf nicht starten.
2. Mit dem Testzugang `inhaber` anmelden und die Zugehörigkeit zum vorgesehenen Testmandanten prüfen.
3. Die Fragen der externen Prüfliste einzeln und in der vorgegebenen Reihenfolge über die Chat-Oberfläche stellen.
   Zwischen den Fragen mindestens fünf Sekunden warten.
4. Pro Frage prüfen, ob der Antwortstrom regulär mit «fertig» endet, die erwarteten Kernwerte enthält und die
   erwarteten Quellen nennt. Eine Quelle gilt nur dann als passend, wenn sie zu den erwarteten synthetischen
   Dokument-IDs oder zum erwarteten Zahlenwerkzeug gehört.
5. Für jede Frage den automatischen Vorschlag «richtig/falsch» und «Quelle ja/nein» mit der Antwort und den Quellen
   vergleichen. Fabian bestätigt das Ergebnis selbst; der automatische Vorschlag ist keine Abnahme.
6. Zeit bis zum ersten Text-Ereignis je Frage messen. Nur Nummern, Resultate und Messwerte festhalten; keine Inhalte
   der Frage, Antwort oder Quelle kopieren.
7. Nach Abschluss nur die aggregierten Ergebnisse in [phase2.md](./phase2.md) nachtragen. Vollständige Prüfliste
   und Laufbericht bleiben ausserhalb des Repositories.

## Prüfpunkte

Für jede Zeile «Antwort» nur dann als bestanden markieren, wenn alle erwarteten Kernwerte enthalten sind. «Quelle»
ist nur bestanden, wenn alle erwarteten Quellen geliefert werden. Bei Abweichung die Frage nicht wiederholen, um
ein Ergebnis zu ersetzen; Abweichung als nicht bestanden dokumentieren.

| Nr. | Gruppe | Antwort | Quelle | Bemerkung ohne Inhalte |
| ---: | --- | --- | --- | --- |
| 1 | Beispielfrage | [ ] | [ ] | |
| 2 | Beispielfrage | [ ] | [ ] | |
| 3 | Beispielfrage | [ ] | [ ] | |
| 4 | Beispielfrage | [ ] | [ ] | |
| 5 | Beispielfrage | [ ] | [ ] | |
| 6 | Beispielfrage | [ ] | [ ] | |
| 7 | Beispielfrage | [ ] | [ ] | |
| 8 | Zusatzfrage | [ ] | [ ] | |
| 9 | Zusatzfrage | [ ] | [ ] | |
| 10 | Zusatzfrage | [ ] | [ ] | |
| 11 | Zusatzfrage | [ ] | [ ] | |
| 12 | Zusatzfrage | [ ] | [ ] | |
| 13 | Zusatzfrage | [ ] | [ ] | |
| 14 | Zusatzfrage | [ ] | [ ] | |
| 15 | Zusatzfrage | [ ] | [ ] | |
| 16 | Zusatzfrage | [ ] | [ ] | |
| 17 | Zusatzfrage | [ ] | [ ] | |
| 18 | Zusatzfrage | [ ] | [ ] | |
| 19 | Zusatzfrage | [ ] | [ ] | |
| 20 | Zusatzfrage | [ ] | [ ] | |

## Abnahmekriterien

- Die sieben Beispielfragen sind alle richtig beantwortet und mit Quelle belegt: **7 von 7**.
- Insgesamt sind mindestens 18 Fragen richtig beantwortet und mit Quelle belegt: **mindestens 18 von 20**.
- Das 95. Perzentil der Zeit bis zum ersten Text-Ereignis beträgt höchstens **10'000 ms**.
- Es gab keinen Test mit echten Dokumenten oder Personendaten; keine Frage-, Antwort- oder Quelleninhalte stehen
  in einem Bericht im Repository.

Bei 401, einem Fehlerereignis, fehlenden synthetischen Quelldaten oder einer unerwarteten Antwort den Lauf
unterbrechen. Keine Tokens, Inhalte oder Stacktraces in das Abnahmeprotokoll übernehmen.

## Ergebnis

| Kennzahl | Soll | Ergebnis |
| --- | --- | --- |
| Beispielfragen richtig und mit Quelle | 7 von 7 | offen |
| Fragen insgesamt richtig und mit Quelle | mindestens 18 von 20 | offen |
| Zeit bis zum ersten Text-Ereignis, p95 | höchstens 10'000 ms | offen |
| Gesamtergebnis | Fabian bestätigt | offen |

**Noch nicht ausgeführt.** Laufdatum, Commit/Image und Messwerte erst nach dem manuellen Lauf durch Fabian
im vorgesehenen Abnahmeprotokoll erfassen.
