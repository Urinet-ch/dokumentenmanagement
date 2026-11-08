# UriNet Archiv — Umsetzungsplan

Stand 08.11.2026 · Grundlage: Konzept «UriNet Archiv — Konzept und Anforderungen» und die
Specs unter `.kiro/specs/`.

**Rund 35 Arbeitstage in 13 Wellen und 4 Phasen. Nach jeder Phase steht ein messbares Gate;
die nächste Phase beginnt erst, wenn es erfüllt ist.**

## 1. Aufbau der Spec

| Ordner | Inhalt | Wellen | Aufwand |
| --- | --- | --- | --- |
| `.kiro/steering/` | `product.md` (Warum), `tech.md` (Stack und Regeln), `structure.md` (Ordner, Namen) | – | – |
| `.kiro/specs/01-archiv-kern/` | Phase 0 + 1: 17 Requirements, Design, Aufgaben | 0–5 | ca. 19 Tage |
| `.kiro/specs/02-abgleich-chat/` | Phase 2: 13 Requirements, Design, Aufgaben | 6–9 | ca. 10 Tage |
| `.kiro/specs/03-zweiter-mandant/` | Phase 3: 9 Requirements, Design, Aufgaben | 10–12 | ca. 6 Tage |

Jede Spec folgt dem Kiro-Muster: `requirements.md` (User Stories + Akzeptanzkriterien in EARS),
`design.md` (Architektur, Schnittstellen, Datenmodell, Fehler, Tests), `tasks.md` (Aufgaben mit
Rückverweis `_Anforderungen: x.y_`).

## 2. Crew und Rollen

| Rolle | Wer | Aufgaben |
| --- | --- | --- |
| Product Owner / Reviewer | Fabian | Requirements und Design freigeben, PRs prüfen, Gates abnehmen, Aufgaben **[Fabian]** |
| Backend-Agent | Kiro (Spec-Aufgaben **[Backend]**) | Quarkus, Datenbank, Worker, KI |
| Frontend-Agent | Kiro bzw. Joel (**[Frontend]**) | Angular-PWA, WordPress-Plugin |
| Infra-Agent | Kiro (**[Infra]**), Fabian für Zugänge | Docker, Infomaniak, Sicherung |
| QA | Kiro (**[QA]**) + Fabian | Last-, Isolations-, Sicherheits- und Abnahmetests |

Joel eignet sich für klar abgegrenzte Frontend-Aufgaben (2.7, 3.6, 4.4, 4.6, 7.4, 11.4, 11.5),
jeweils mit Review durch Fabian.

## 3. Ablauf mit Kiro

1. Im Repository `Urinet-ch/dokumentenmanagement` (angelegt 08.11.2026) den Ordner `.kiro/` und `docs/` aus diesem Paket einchecken.
2. In Kiro öffnen: Die Steering-Dateien sind mit `inclusion: always` in jeder Sitzung aktiv.
3. Spec `01-archiv-kern` öffnen → **Requirements** lesen und freigeben (bei Bedarf «Analyze Requirements»).
4. **Design** prüfen und freigeben – das ist der wichtigste Prüfpunkt (Architektur, nicht Syntax).
5. **Tasks** wellenweise ausführen: alle Aufgaben einer Welle starten (parallel nach Rolle), PRs prüfen, Welle abschliessen.
6. Nach Welle 0, 5, 9 und 12 das Gate abnehmen und im Abschnitt 6 eintragen.
7. Erkenntnisse aus Spikes (Welle 0) zurück in `design.md` schreiben, bevor Welle 1 startet.

Konventionen pro Aufgabe:
- Branch `feature/<spec>-<nr>-<stichwort>`, z. B. `feature/01-2.6-ocr`.
- Commit `feat(verarbeitung): OCR mit OCRmyPDF (Req 5)`.
- PR-Beschreibung nennt die erfüllten Akzeptanzkriterien und die Tests dazu.
- Aufgabe gilt als erledigt, wenn die Definition of Done aus `tech.md` erfüllt ist.

Empfohlene Kiro-Hooks (in Kiro anlegen):
- Beim Speichern einer Java-Datei: betroffene Tests ausführen.
- Bei Änderungen an `db/**`: prüfen, dass jede Migration ein neues File ist (keine Änderung bestehender).
- Bei Änderungen an REST-Resources: OpenAPI neu erzeugen und Frontend-Client aktualisieren.

## 4. Wellen, Abhängigkeiten und Aufwand

```mermaid
flowchart LR
  W0[W0 Klären<br/>3 T] --> G0{Gate 0<br/>≥45/50}
  G0 --> W1[W1 Fundament<br/>3 T] --> W2[W2 Dokumente,<br/>Ablage, OCR<br/>4 T] --> W3[W3 KI, Eingangskorb,<br/>Handy<br/>4 T] --> W4[W4 CSV, Suche,<br/>Generatoren<br/>3 T] --> W5[W5 GeBüV,<br/>Betrieb<br/>2 T] --> G1{Gate 1<br/>Belege 2026}
  G1 --> W6[W6 Mail, Embeddings,<br/>Export<br/>3 T] --> W7[W7 Chat<br/>4 T] --> W9[W9 Abnahme<br/>1 T] --> G2{Gate 2<br/>7 Fragen}
  W6 --> W8[W8 AbaNinja<br/>2 T · nur E1=ja] --> W9
  G2 --> W10[W10 Mandant VHF<br/>2 T] --> W11[W11 Schnittstelle,<br/>Plugin<br/>3 T] --> W12[W12 Abnahme<br/>1 T] --> G3{Gate 3<br/>online}
```

| Welle | Inhalt | Aufgaben | Parallel (Rollen) | Aufwand |
| --- | --- | --- | --- | --- |
| 0 | Golden-Datensatz, Spikes S3/AI, Test-Export 21.CRM, Prototyp | 01: 0.1–0.6 | Fabian ∥ Backend ∥ Infra | 3 T |
| 1 | Gerüst, CI, Dev-Umgebung, Mandanten, Rollen, API-Tokens | 01: 1.1–1.6 | Infra ∥ Backend | 3 T |
| 2 | Dokumentarten, Datenmodell, S3, Upload, Aufträge, OCR, Frontend-Gerüst | 01: 2.1–2.7 | Backend ∥ Infra ∥ Frontend | 4 T |
| 3 | Kontakte, KI-Vorschlag, Lernen, Bestätigen, Versionen, Eingangskorb, PWA | 01: 3.1–3.7 | Backend ∥ Frontend | 4 T |
| 4 | CSV-Import, Suche, Generator-Client, Drive-Import | 01: 4.1–4.8 | Backend ∥ Frontend | 3 T |
| 5 | Protokoll, Manifest, Prüflauf, Produktion, Sicherung, Überwachung, Verfahrensbeschreibung, Übernahme | 01: 5.1–5.8 | Backend ∥ Infra ∥ Fabian | 2 T |
| 6 | E-Mail-Eingang, Embeddings, Hybridsuche, Jahresexport | 02: 6.1–6.5 | Backend ∥ Frontend ∥ Fabian | 3 T |
| 7 | Werkzeuge, Agent, SSE, Chat-Oberfläche | 02: 7.1–7.4 | Backend ∥ Frontend | 4 T |
| 8 | AbaNinja (nur E1 = ja) | 02: 8.1–8.6 | Backend ∥ Frontend | 2 T |
| 9 | Chat-Prüfliste und Abnahme | 02: 9.1 | Fabian ∥ QA | 1 T |
| 10 | AVV, Mandant VHF/GSK, Branding, Erfassung, Altbestand | 03: 10.1–10.5 | Backend ∥ Frontend ∥ Fabian | 2 T |
| 11 | Öffentliche Schnittstelle, Chatbot, WordPress-Plugin | 03: 11.1–11.5 | Backend ∥ Frontend | 3 T |
| 12 | Bericht, Vollexport/Abbau, Isolation/Sicherheit, Einführung | 03: 12.1–12.4 | Backend ∥ QA ∥ Fabian | 1 T |
| | **Total** | | | **ca. 35 T** |

Die Tage sind Aufwand von Fabian (Steuern, Prüfen, manuelle Aufgaben) bei Umsetzung durch
Agenten; ohne Agenten ist mit etwa dem doppelten Aufwand zu rechnen.

## 5. Zeitplan (Richtwerte, Nebenerwerb)

| Zeitraum | Phase | Wellen | Gate |
| --- | --- | --- | --- |
| Nov–Dez 2026 | 0 Klären | 0 | ≥ 45/50 Belege korrekt |
| Jan–Apr 2027 | 1 Archiv-Kern | 1–5 | alle Belege 2026 im Archiv, Prüflauf grün |
| Mai–Jul 2027 | 2 Abgleich und Chat | 6–9 | 7 Beispielfragen + ≥ 18/20 richtig mit Quelle |
| ab Etappe 2 VHF/GSK | 3 Zweiter Mandant | 10–12 | VHF/GSK-Archiv online |

Fixpunkte: Vorstandsentscheid VHF/GSK 19.11.2026 (bestimmt E4 und den Start von Phase 3);
Jahresabschluss 2026 (Phase 1 sollte vorher die Belege 2026 enthalten, sonst Abschluss 2026
noch klassisch).

## 6. Gates und Entscheide

| Gate / Entscheid | Wann | Kriterium / Frage | Ergebnis |
| --- | --- | --- | --- |
| Gate 0 | Ende Welle 0 | ≥ 45/50 Belege in Art, Datum, Partner, Betrag korrekt; Spikes dokumentiert | [offen] |
| E2 Eigenbau | Gate 0 | Eigenbau Quarkus/Angular bestätigen (Specs gehen davon aus) | [offen] |
| E3 PWA | Gate 0 | Handy zuerst als PWA (Specs gehen davon aus) | [offen] |
| E5 Papier | vor Welle 5 | Papieroriginale nach Scan vernichten? Verfahrensbeschreibung anpassen | [offen] |
| Gate 1 | Ende Welle 5 | Belege 2026 im Archiv, Prüflauf ohne Abweichung | [offen] |
| E1 AbaNinja Basic | vor Welle 8 | Abo Basic (CHF 21/Mt.)? Nein → Welle 8 entfällt | [offen] |
| Gate 2 | Ende Welle 9 | 7 Beispielfragen + ≥ 18/20 | [offen] |
| E4 VHF/GSK | nach 19.11.2026 | Archiv in Etappe 2 der Offerte O-2026-002 oder später | [offen] |
| Gate 3 | Ende Welle 12 | Archiv online, Geschäftsstelle veröffentlicht selbständig | [offen] |

Offene Klärungen aus dem Konzept: K1 Bildeingabe (Spike 0.3), K2 Zeitbudget, K3 Treuhänder
(Aufgabe 5.7), K4 21.CRM-Export (Aufgabe 0.4), K5 Serverkosten (Aufgabe 5.4).

## 7. Risiken

| Risiko | Wirkung | Massnahme |
| --- | --- | --- |
| KI-Erkennung unter 45/50 | Phase 1 bringt weniger Zeitersparnis | Prompt und Beispiele verbessern; sonst Vorschläge nur für Art/Partner, Rest manuell |
| Object Lock pro Objekt nicht verfügbar | zweistufige Ablage nicht möglich | Rückfall: zwei Buckets (`eingang` ohne, `archiv` mit Standard-Aufbewahrung) |
| Infomaniak ohne strukturierte Antworten / Tool Calling | Extraktor bzw. Chat aufwendiger | JSON-Modus + Validierung; Chat mit Apertus oder späterem Modell, Werkzeugauswahl per Klassifikation |
| Embedding-Dimension > 4'000 | kein HNSW-Index | anderes Modell (Qwen3-Embedding-8B) oder Dimension reduzieren |
| Zeitbudget im Nebenerwerb | Verzögerung | Wellen sind einzeln abschliessbar; Phase 2/3 verschiebbar ohne Folgen für Phase 1 |
| AbaNinja-API ändert sich | Abgleich bricht | Adapter gekapselt, Vertragstests, Tagesabgleich holt nach |

## 8. Laufende Kosten (Richtwerte)

| Posten | Kosten |
| --- | --- |
| Server Infomaniak | [CHF __]/Mt. (K5) |
| Object Storage | nach Verbrauch, einige GB pro Jahr |
| Infomaniak AI Services | ca. CHF 1.50 pro 1'000 Chat-Fragen (Mistral), Erfassung ähnlich pro 1'000 Dokumente |
| Swiss Backup | [CHF __]/Mt. |
| AbaNinja Basic | CHF 21/Mt., nur bei E1 = ja |
