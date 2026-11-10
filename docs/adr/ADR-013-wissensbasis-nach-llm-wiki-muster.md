# ADR-013: Wissensbasis nach dem LLM-Wiki-Muster, gespeichert im Mandanten-Schema, KI schreibt nur Entwürfe

**Status:** Vorgeschlagen – Freigabe per eigenem Merge mit Spec 04 (Aufgabe 13.1), nicht Teil von 0.6.

**Datum:** 09.11.2026

## Kontext
Fabian will Inhalte, z. B. aus einem hochgeladenen PDF, einer Wissensbasis hinzufügen, die wie sein LLM-Wiki
aufgebaut ist (Auftrag 09.11.2026, Spec 04). Kräfte und Randbedingungen:
- **Muster LLM-Wiki (OKF v0.2):** Wissen wird beim Ingest einmal herausgezogen und in verlinkte Seiten eingearbeitet,
  mit Index, Log, Belegen je Aussage und Lint. Es ist kein Index für Textstücke (Chunk-RAG); gefunden wird über Index,
  Links und Volltext. Im Vorbild schreibt das LLM das Wiki selbst, der Mensch kuratiert die Quellen.
- **KI nur für Vorschläge (ADR-006):** Im Archiv entscheidet bei Metadaten immer ein Mensch mit Recht. Eine
  Wissensbasis, die der Chat als Quelle zitiert (Spec 02, 4.1), hat dasselbe Fehlerrisiko wie Metadaten.
- **Sichtbarkeit (ADR-009):** Jeder Lesepfad muss über die zentrale Sichtbarkeitsregel laufen, auch Zähler und
  Aggregate. Eine Seite fasst Wissen aus mehreren Dokumenten mit verschiedener Vertraulichkeit zusammen.
- **Mandanten (ADR-002):** ein Schema je Mandant, Schema nur über Liquibase; Sicherung über die bestehende Zweitkopie
  (ADR-008).
- **Plattform (ADR-010):** Stufe A mit 700 MiB Speicherbudget (ab 10.11.2026 900 MiB, siehe Nachtrag in ADR-010) und Worker-Parallelität 1; Auftragsschlange in
  PostgreSQL statt Broker (ADR-003).
- **Prompt-Injection:** Eine Wissensquelle ist fremder Text. Spec 02, 5.3 verlangt nur lesende Chat-Werkzeuge.
- **Quellen:** Wissens-PDFs sollen normale Archivdokumente sein (eigene Art `WISSENSQUELLE`, ohne gesetzliche
  Pflicht, ohne Object Lock nach Spec 01, 6.12; Entscheid 09.11.2026).

## Entscheid
1. **Seiten nach dem LLM-Wiki-Muster.** Seitentypen, Felder analog OKF-Frontmatter (`type`, `title`, `description`,
   `tags`, `status`, `stale_after`, `generated`, `verified`, `sources`), Fussnoten mit stabilen Schlüsseln, Index mit
   einer Zeile je Seite, Log nur anfügend, Abläufe Ingest, Query und Lint (Spec 04, Requirements 2, 3, 5, 8, 10).
   Quellen sind Archivdokumente mit PDF-Seite und wörtlichem Auszug oder andere Seiten.
2. **Speicherung im Mandanten-Schema, nicht in Dateien oder Git.** Seiten, Versionen (nur anfügend wie
   `dokument_version`), Quelleneinträge, Hüllen, Links, Entwürfe und Log liegen in `m_<code>`. OKF ist Exportformat
   (Spec 04, Requirement 17).
3. **Die KI schreibt nur Entwürfe.** Ingest und Lint erzeugen Änderungen je Seite mit Diff. Wirksam werden sie erst,
   wenn ein Mensch mit Recht A17 sie annimmt (Inhaber; im Mandanten `vhf-gsk` die Geschäftsstelle). Die Freigabe setzt
   `verified` mit `human:<benutzer>`; jede Annahme gilt einer einzelnen Änderung, deren Diff angezeigt wurde, ein
   gesammeltes Annehmen gibt es nicht. Das erweitert den Grundsatz von ADR-006 von Metadaten auf Wissen. ADR-006,
   Punkt 1 («genau ein Aufruf je Beleg») gilt unverändert für den Metadaten-Vorschlag (`KI_VORSCHLAG`); der Ingest ist
   ein eigener, nachgelagerter Zweck mit mehreren Aufrufen (Entscheid 8) und ändert keine Metadaten. Die Art
   `WISSENSQUELLE` wählt ein Mensch; der KI-Vorschlag kennt sie nicht, damit Gate 0 gültig bleibt (entschieden von Fabian am 09.11.2026).
   Ingest gibt es vorerst nur für diese Art (entschieden von Fabian am 09.11.2026); eine Erweiterung auf andere Arten steht als Option in Spec 04.
4. **Belege werden deterministisch geprüft.** Dokument-ID, PDF-Seite und Auszug stammen aus dem Schritt
   «Kernaussagen» und werden gegen den Seitentext geprüft. Der Schritt, der Seiten schreibt, verweist nur auf
   vorgegebene Schlüssel. Entfernte Aussagen ohne Begründung und externe Links sperren die Annahme.
5. **Nie still überschreiben.** Widersprüche bleiben als markierter Block mit beiden Quellen stehen, bis ein Mensch
   entscheidet.
6. **Sichtbarkeit aus Hülle und Mindeststufe.** Eine Seite ist nur sichtbar, wenn der Benutzer jedes Dokument sehen
   darf, auf dem sie direkt oder über Quellseiten beruht, und ihre Mindeststufe sehen darf. Die Mindeststufe hält fest,
   was die KI beim Schreiben im Kontext hatte (Kontextstufe) und was ein Freigeber von Hand erhöht hat; sie sinkt nie,
   weil sich nicht prüfen lässt, ob Wissen ohne Fussnote in den Text gelangt ist. Die Regel wird live über den
   `VertraulichkeitFilter` ausgewertet, für jede Version mit deren eigener Hülle. Beim Ingest sieht die KI nur Seiten
   bis zur Stufe der Wissensquelle. `PERSONAL`-Dokumente sind bei keinem Anlass Quelle einer Seite (Ingest, Lint,
   manuell, Analyse); Seiten tragen nur `NORMAL` oder `INTERN`, und `ueberblick` entsteht nur aus `NORMAL`-Wissen (entschieden von Fabian am 09.11.2026).
7. **Schrittweise Erschliessung statt ganzes Wiki.** Die KI liest Index und höchstens `max-seiten` Seiten; der Chat
   nutzt zuerst Index und Seiten, dann die Quelldokumente. Die Hybridsuche aus Spec 02 ergänzt das (Eingrenzung
   grosser Indizes, Suche nach Seiten).
8. **Teilaufträge mit Budget, Quellen einzeln.** Höchstens ein KI-Aufruf je Teilauftrag in `public.auftrag`,
   Tokenbudget je Ingest (Startwert 300'000) und Lint und ein eigenes Monatsbudget der Wissensbasis im Mandanten-Schema
   (Startwert 1'000'000 Tokens je Mandant, entschieden von Fabian am 09.11.2026; nicht das Budget des öffentlichen Chatbots); lange PDFs abschnittsweise oder nach Seitenbereich. Je Mandant ist höchstens ein
   Ingest in Arbeit oder zur Freigabe offen; weitere warten und planen danach gegen die neuen Versionen (wie im LLM-Wiki:
   Quellen einzeln einarbeiten).

## Betrachtete Alternativen
1. **Git-Repository bzw. Markdown-Dateien je Mandant (wie das LLM-Wiki selbst)** – abgelehnt:
   - Sichtbarkeit je Seite und Benutzer lässt sich auf einem Dateibaum nicht als Filter in Index, Suche und Zählern
     durchsetzen (ADR-009).
   - Freigabe, Zugriffsprotokoll und Version müssen in einer Transaktion liegen (Spec 01, 10.8); Git und Datenbank
     zusammen sind nicht atomar.
   - Ein zusätzliches Repository je Mandant braucht eigene Sicherung, Zugangsdaten und Trennung neben Schema und
     Bucket (ADR-002, ADR-008) und eigene Prozesse im Speicherbudget von Stufe A.
   - Volltext und Embeddings lägen ohnehin in PostgreSQL. Nur-Anfügen lässt sich wie bei `dokument_version` mit
     Trigger und DB-Rechten erzwingen.
   - Das Gute an Git (Diff, Verlauf, offenes Format) bleibt erhalten: Diff und Versionen im Archiv, OKF-Export als
     Datei.
2. **Wissens-PDFs als Dateien im Bucket statt als Archivdokumente** – abgelehnt: zweiter Erfassungsweg ohne OCR,
   Dublettenprüfung, Eingangskorb, Vertraulichkeit und Protokoll.
3. **Nur Chunk-RAG über die Wissens-PDFs (Spec 02 genügt)** – nicht gewählt: Es sammelt kein Wissen an, erkennt keine
   Widersprüche über Quellen hinweg, und jede Frage leitet neu her. Das bleibt als Rückfall: Wissensquellen sind
   ohnehin archivierte Dokumente mit Embeddings.
4. **KI schreibt direkt, Mensch prüft im Nachhinein (wie im LLM-Wiki)** – abgelehnt: Der Chat würde ungeprüfte
   Aussagen als Quelle zitieren, und Prompt-Injection aus einem PDF erreichte ohne Hürde den Chat-Kontext. Das
   widerspricht ADR-006.
5. **Vertraulichkeit je Seite von Hand** – abgelehnt: fehleranfällig; die Hülle macht die Regel prüfbar. Erhöhen von
   Hand auf `INTERN` bleibt möglich und wirkt über die Mindeststufe auf die Sichtbarkeit.
6. **Chat-Werkzeug «wissen_schreiben»** – abgelehnt: Spec 02, 5.3 erlaubt nur lesende Werkzeuge. Aus dem Chat entsteht
   nur ein Analyse-Entwurf durch eine Handlung des Benutzers, ohne KI-Aufruf.

## Folgen
- **Positiv:** Die Verantwortung für Wissen liegt wie bei Metadaten bei einem Menschen; jede Fassung ist mit Freigeber,
  Entwurf und Quelle nachvollziehbar und im Manifest gehasht.
- **Positiv:** Die bestehenden Bausteine (Erfassung, `VertraulichkeitFilter`, `KiPort`, Auftragsschlange, Hybridsuche,
  Sicherung) werden wiederverwendet; ein neuer Fachbereich `wissen`, kein neuer Dienst.
- **Positiv:** Der OKF-Export macht die Wissensbasis portabel und mit dem LLM-Wiki austauschbar.
- **Negativ:** Jeder Ingest braucht eine Freigabe, typischerweise 5 bis 15 Seiten, jede einzeln mit angesehenem Diff.
  Gemildert durch Diff je Seite und die Prüfung ohne KI vor der Freigabe; ein gesammeltes Annehmen gibt es bewusst
  nicht, weil `verified` mit `human:` sonst eine Prüfung behauptete, die nicht stattfand.
- **Negativ:** Weil je Mandant nur ein Ingest offen sein darf, staut ein nicht freigegebener Entwurf alle weiteren
  Wissensquellen. Das ist der Preis dafür, dass jede Quelle gegen den aktuellen Stand geplant wird (akzeptiert,
  entschieden von Fabian am 09.11.2026).
- **Negativ:** Strenge Sichtbarkeit: Eine Seite mit einem einzigen `INTERN`-Dokument in der Hülle oder mit
  Mindeststufe `INTERN` ist für `mitarbeiter` ganz unsichtbar. Ändert ein Ingest einer `INTERN`-Quelle eine
  `NORMAL`-Seite, wird diese dauerhaft `INTERN` (die Freigabe zeigt das mit `STUFE_STEIGT` an; akzeptiert, entschieden von Fabian am 09.11.2026). `ueberblick` bleibt `NORMAL` und nimmt
  deshalb kein `INTERN`-Wissen auf.
- **Negativ:** Das Wissen kann nach Vertraulichkeit zerfallen: Ein `NORMAL`-Ingest sieht `INTERN`-Seiten nicht. Einen
  belegten Pfad erkennt das Archiv (Hinweis «Seite auf höherer Stufe vorhanden – von Hand zusammenführen»), eine
  Doppelseite unter anderem Namen nicht; der Lint mit gemischten Paaren mildert das.
- **Negativ:** Nur-Anfügen kollidiert mit einer späteren Löschung nach revDSG; offen bis zur Spec zur Löschfunktion.
- **Folgeänderungen:** Rechtematrix um A15–A19, Port `DokumentEreignisse` und `\f` im Volltext in Spec 01,
  Werkzeugliste, Quellenform und Jahresexport in Spec 02, Seed-Felder für `vhf-gsk` in Spec 03, neue Auftragsarten,
  Protokollaktionen, Fachbereich in `structure.md`, Eintrag in `docs/adr/README.md`, Nachträge zu ADR-006, ADR-009 und
  ADR-011; siehe Spec 04, design.md, Abschnitt «Folgeänderungen in anderen Dokumenten».
- **Tests:** Ingest in der CI nur mit WireMock; Abnahme (Gate Phase 4) nur manuell auf Stufe B (tech.md Regel 6).

## Verweise
- Spec 04, Requirements 1–18; Spec 01, 1.5, 2.3, 2.6, 5.9, 6.12, 7, 10.3, 10.8, 15.1, 16.6; Spec 02, 2.1–2.5, 3.1, 4.1, 5.3, 7.1; Spec 03, 1.2, 6.4
- ADR-002, ADR-003, ADR-006, ADR-008, ADR-009, ADR-010, ADR-011, ADR-012
- Open Knowledge Format v0.2 (Spezifikation `llm-wiki/OKF-SPEC.md` im Repository `ai-entwicklung`, ausserhalb dieses Repositorys)
