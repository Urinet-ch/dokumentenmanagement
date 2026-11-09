# ADR-011: Hexagonale Struktur je Fachbereich, durch ArchUnit erzwungen

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026)

**Datum:** 08.11.2026

## Kontext
- Der Kern hängt an mehreren externen Diensten, deren Verhalten erst die Spikes klären: Object Storage mit Object Lock (Spike 0.2), Infomaniak AI Services (Spike 0.3), OCRmyPDF und Tika als Prozesse sowie später AbaNinja, E-Mail und WordPress (Spec 02/03).
- Die Fachlogik ist streng und muss ohne Infrastruktur testbar sein: Rechtematrix A1–A14, Fristberechnung, Belegnummer-Muster, Swiss-QR-Parser und Manifest-Fenster. Unit-Tests laufen mit JUnit 5 ohne Quarkus (tech.md, Tests und Qualität).
- Entwicklung und Tests sollen ohne echte Dienste möglich sein. Echte Daten gehören nicht in die CI (tech.md Regel 6), und KI-Aufrufe laufen in Unit- und Integrationstests gegen WireMock (tech.md, Tests und Qualität).
- Zwei Agenten (Claude Code, GitHub Copilot) schreiben parallel Code (CLAUDE.md). Eine Regel, die nur als Konvention gilt, verwässert dabei schnell, deshalb muss der Build sie prüfen.
- Das Backend ist ein Maven-Modul `backend` mit zwei Laufarten (tech.md, Stack).

## Entscheid
1. Jeder Fachbereich unter `ch/urinet/archiv/<fachbereich>/` ist gleich gegliedert (structure.md):
   - `domain/`: Records, Invarianten und Domain-Services, ohne Quarkus, JPA oder Jackson.
   - `application/`: Anwendungsfälle und Ports, eingehend die Use Cases, ausgehend z. B. `AblagePort`, `KiPort` und `OcrPort`.
   - `adapter/in/rest/` (Resources, DTOs), `adapter/in/job/` (Auftrags-Handler des Workers, ADR-003) und `adapter/out/…` (`persistence/`, `s3/`, `ki/`, `ocr/` …) als Implementierungen der Ports.
2. Ablage, KI, OCR und Integration binden über Driven Ports an, jeweils mit Adapter und Dev-Stub, die Stubs im Profil `dev` (Req 16.13, Aufgabe 1.2). Beispiele: `AblagePort` mit `S3Ablage` und Stub im Dateisystem, `KiPort` mit Adapter gegen Infomaniak AI und Stub mit festen Antworten, `OcrPort`.
3. Panache-Entitäten liegen im Adapter (`adapter/out/persistence`), nicht in der Domain (Aufgabe 2.2).
4. Abhängigkeiten gehen nur in der Richtung `adapter → application → domain` (tech.md Regel 12). Fachbereiche greifen nur über `application` aufeinander zu.
5. ArchUnit-Tests erzwingen das in `mvn verify`, ein Verstoss bricht den Build. Geprüft werden mindestens:
   - `domain` hängt von nichts ab, `application` nur von `domain`, `adapter` von `application` und `domain`;
   - Fachbereiche greifen nur über `application` aufeinander zu;
   - Code eines Mandanten greift auf Tabellen in `public` nur über `auftrag` zu (ADR-002);
   - Beträge sind nie `double` (tech.md Regel 9);
   - Secrets werden nie über `System.getenv` gelesen (tech.md Regel 7).

## Betrachtete Alternativen
- **Schichten nach Technik** (`controller/`, `service/`, `repository/` über alle Fachbereiche): Fachlogik und Zugriffe auf S3, KI und OCR vermischen sich schnell, und Domain-Logik liesse sich kaum ohne Quarkus und Infrastruktur testen. Fällt ein Spike negativ aus und ein Anbieter oder Rückfall muss her (z. B. JSON-Modus statt strukturierter Antworten, getrennte Buckets), wäre davon mehr Code betroffen als nur ein Adapter.
- **Ein Maven-Modul je Schicht oder Fachbereich**, bei dem schon der Compiler die Richtung prüft: abgelehnt, weil tech.md ein Modul `backend` festlegt. Mit ArchUnit lassen sich dieselben Regeln ohne den Mehraufwand vieler Module prüfen, und sie decken auch Regeln ab, die ein Modulschnitt nicht ausdrücken kann (`double`, `System.getenv`, Zugriff auf `public`).
- **JPA- und Jackson-Annotationen direkt in der Domain** (Active Record mit Panache auf den Domänenobjekten): spart Mapping-Code, bindet die Domain aber an das Framework und widerspricht tech.md Regel 12. Fachlogik in Entitäten wäre ohne Datenbank schwer zu testen.
- **Nur Konvention und Review ohne ArchUnit:** Bei zwei parallel arbeitenden Agenten und einem Inhaber, der jeden PR selbst prüft, würden Verstösse erst spät oder gar nicht auffallen. Ein Test im Build ist billiger als ein Review.

## Folgen
- **Positiv:** Domain-Logik wird mit JUnit 5 ohne Quarkus getestet und läuft schnell (z. B. Fristen, Belegnummern, Rechtematrix als eine Domain-Regel, Aufgabe 1.4; Durchsetzung siehe ADR-009).
- **Positiv:** Externe Dienste lassen sich austauschen: Fällt ein Spike negativ aus, ändern sich nur Adapter und Konfiguration, nicht die Anwendungsfälle (Rückfälle siehe ADR-004, ADR-007).
- **Positiv:** Mit den Dev-Stubs läuft die Entwicklung ohne Zugang zu S3, KI oder OCR. In Tests ersetzen WireMock (KI, TSA) und MinIO als Testcontainer die echten Dienste, ohne echte Daten und ohne fremde Anbieter (tech.md Regel 6).
- **Positiv:** Die Regeln sind maschinell geprüft und gelten für beide Agenten gleich.
- **Negativ:** Es braucht mehr Klassen und Mapping zwischen Domain-Records, DTOs und Panache-Entitäten.
- **Negativ:** Eine neue Abhängigkeit zwischen Fachbereichen braucht eine Schnittstelle in `application`, auch wenn ein direkter Zugriff kürzer wäre.
- **Umsetzung:** Die ArchUnit-Regeln entstehen in Aufgabe 1.6 und müssen mit jedem neuen Fachbereich und jeder neuen Regel in tech.md nachgeführt werden.
- **Tests:** Ein Dev-Stub kann vom echten Verhalten abweichen. Deshalb laufen Integrationstests gegen MinIO mit Object Lock, WireMock und das Worker-Image (design.md, Testing Strategy), und `GoldenDatensatzIT` prüft die echte KI manuell.

## Nachtrag 09.11.2026 (Spec 04)
Spec 04 fügt den Fachbereich `wissen` hinzu; die Regeln aus Punkt 5 gelten für ihn wie für alle Fachbereiche
(Spec 04, Aufgabe 13.3). Vom Fachbereich `dokument` zu `wissen` führt nur der Port `DokumentEreignisse` in
`dokument/application` (Spec 01, design.md, «Dokument»), siehe ADR-013.

## Nachtrag 09.11.2026 (JUnit-Version)
«JUnit 5» in Kontext und Folgen heisst jetzt «JUnit (Jupiter 6, über die Quarkus-BOM)», wie in tech.md (PR #24). Die
Quarkus-BOM bringt JUnit Jupiter 6; am Entscheid ändert das nichts.

## Verweise
- Req 16.13 (Nachweis: JUnit, ArchUnit-Tests), 16.14
- tech.md Regeln 6, 7, 9 und 12, Konventionen Backend, Tests und Qualität; structure.md, Paketstruktur je Fachbereich
- P16 (Tests nach Blueprint)
- ADR-002, ADR-003, ADR-004, ADR-006, ADR-007, ADR-009
- Aufgaben 1.1, 1.2, 1.4, 1.6, 2.2, 2.3, 2.6, 3.2
