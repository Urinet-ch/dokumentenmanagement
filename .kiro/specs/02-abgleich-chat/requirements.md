# Requirements Document — 02 Abgleich und Chat (Phase 2)

## Introduction
Diese Spec erweitert den Archiv-Kern (`01-archiv-kern`) um den E-Mail-Eingang, die
semantische Suche, den Chat-Agenten mit festen Werkzeugen, den Jahresexport für den
Treuhänder und – nur wenn der Konzept-Entscheid E1 «AbaNinja Basic» positiv ausfällt
(`docs/umsetzungsplan.md`, Abschnitt 6) – die Anbindung an AbaNinja (Kontakte, Rechnungen mit
Status, Lieferantenbelege, Webhooks).

Voraussetzung: Gate Phase 1 erfüllt. Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario
mit Tag `@req-<x.y>`, Ausnahmen mit Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»),
Problem Details und `[offen]`-Marker wie in Spec 01. Rollen und Sichtbarkeit richten sich nach der
Rechtematrix in Spec 01, Requirement 2.6.

## Glossary
- **Chunk** – Textabschnitt eines Dokuments (ca. 3'000 Zeichen) mit Embedding für die semantische Suche.
- **Hybridsuche** – Kombination aus Volltext- und Vektorsuche, zusammengeführt per Reciprocal Rank Fusion (RRF) und neu sortiert durch den Reranker.
- **Werkzeug** – fest programmierte, getestete Java-Methode, die der Chat-Agent aufrufen darf.
- **Quelle** – Dokument oder Datenstand, auf den sich eine Antwort stützt (mit Link).
- **AbaNinja-Verbindung** – pro Mandant hinterlegtes API-Token und Konto-UUID.

---

### Requirement 1: E-Mail-Eingang (D7)
**User Story:** Als Inhaber möchte ich Rechnungen per E-Mail an `archiv@urinet.ch` weiterleiten,
damit digitale Belege ohne Download ins Archiv kommen.

#### Acceptance Criteria
1. Das Archiv SOLL das Postfach pro Mandant alle 10 Minuten per IMAP über TLS abrufen.
2. WENN eine Nachricht von einem zugelassenen Absender eintrifft, DANN SOLL das Archiv jeden Anhang vom Typ PDF, JPEG, PNG, HEIC oder TIFF als eigenes Dokument (Quelle `EMAIL`) erfassen und Betreff, Absender und Datum als Hinweis an die KI geben.
3. WENN eine Nachricht keinen unterstützten Anhang hat, DANN SOLL das Archiv die Nachricht als Dokument der Art «Korrespondenz» erfassen (Original `.eml`, PDF/A aus dem Text).
4. FALLS der Absender nicht auf der Zulassungsliste steht, DANN SOLL das Archiv die Nachricht in den IMAP-Ordner `Quarantaene` verschieben und nichts erfassen.
5. WENN eine Nachricht verarbeitet ist, DANN SOLL das Archiv sie in den IMAP-Ordner `Archiviert` verschieben; dieselbe Message-ID SOLL nie zweimal erfasst werden.
6. WENN ein Dokument per E-Mail erfasst wird, DANN SOLL das Archiv es wie einen Upload verarbeiten (Dublettenprüfung nach Spec 01, 3.4) und in den Eingangskorb legen, nie direkt archivieren; bis zur Bestätigung gilt es nach Spec 01, 2.21 als `PERSONAL`.

### Requirement 2: Semantische Suche (S2)
**User Story:** Als Inhaber möchte ich auch mit anderen Worten als im Dokument suchen können,
damit ich «Unfallversicherung» finde, auch wenn auf der Police «UVG» steht.

#### Acceptance Criteria
1. WENN ein Dokument archiviert wird, DANN SOLL das Archiv seinen Text in Abschnitte (ca. 3'000 Zeichen, 400 Zeichen Überlappung) teilen und für jeden Abschnitt ein Embedding über Infomaniak AI Services speichern.
2. Das Archiv SOLL eine Hybridsuche anbieten: je 50 Treffer aus Volltext- und Vektorsuche, zusammengeführt mit RRF (k = 60), die besten 30 neu sortiert durch den Reranker.
3. FALLS der Embedding- oder Reranker-Dienst nicht erreichbar ist, DANN SOLL das Archiv auf die reine Volltextsuche zurückfallen und dies in der Antwort kennzeichnen.
4. Das Archiv SOLL für bestehende Dokumente eine einmalige Nachberechnung der Embeddings als Auftrag anbieten.
5. Die Hybridsuche SOLL Mandant und Vertraulichkeit genau wie die Volltextsuche einhalten (Spec 01, Requirement 1.2, 2.3 und Rechtematrix 2.6).
6. Die Hybridsuche SOLL bei 50'000 Dokumenten innert 2 Sekunden (p95, Messpunkt Server; Messbedingungen wie Spec 01, Requirement 3.2) antworten.

### Requirement 3: Chat-Agent mit Werkzeugen (C1)
**User Story:** Als Inhaber möchte ich Fragen zu Dokumenten und Zahlen in eigenen Worten stellen,
damit ich nicht selbst suchen und rechnen muss.

#### Acceptance Criteria
1. Das Archiv SOLL einen Chat-Agenten anbieten, der ausschliesslich diese Werkzeuge aufrufen darf: `dokumente_suchen`, `dokument_zeigen`, `kennzahl`, `offene_posten`, `fristen`, `vollstaendigkeit`.
2. WENN eine Frage Summen, Anzahlen oder Zeiträume betrifft, DANN SOLL der Agent das Werkzeug `kennzahl` verwenden; das Ergebnis SOLL aus einer vordefinierten SQL-Abfrage stammen und unverändert in die Antwort übernommen werden.
3. Das Werkzeug `kennzahl` SOLL Einnahmen oder Ausgaben für einen Zeitraum liefern, gruppiert nach Jahr, Monat, Partner oder Dokumentart, wahlweise nach Belegdatum oder Zahlungsdatum.
4. WO der Zahlstatus unbekannt ist (keine AbaNinja-Verbindung), SOLL `offene_posten` die Rechnungen mit Fälligkeit liefern und ausdrücklich angeben, dass der Zahlstatus nicht bekannt ist.
5. Das Werkzeug `fristen` SOLL Vertragsfristen (`naechste_frist`, `gueltig_bis`), Fälligkeiten und Gültigkeitsdaten von Offerten für einen Zeitraum liefern.
6. Das Werkzeug `vollstaendigkeit` SOLL für ein Geschäftsjahr Lücken melden: fehlende Nummern in den Belegnummern-Folgen, fehlende monatliche Lohnabrechnungen und Kontoauszüge seit Beginn, und – mit AbaNinja – Lieferantenbelege ohne Archivdokument.
7. Der Agent SOLL in Deutsch (Schweiz) antworten, Beträge als `CHF 1'234.50` formatieren und die Datenbasis nennen (z. B. «nach Rechnungsdatum, 3 Rechnungen»).
8. Der Agent SOLL innert 10 Sekunden (p95, Messpunkt Server; Messbedingungen wie Spec 01, Requirement 3.2) mit der ersten Textausgabe beginnen und die Antwort fortlaufend streamen.

### Requirement 4: Antworten mit Quellen (C2)
**User Story:** Als Inhaber möchte ich jede Antwort nachprüfen können,
damit ich der Zahl auch vor dem Treuhänder trauen kann.

#### Acceptance Criteria
1. Jede Antwort SOLL eine Liste der verwendeten Quellen enthalten (Dokumenttitel mit Link oder «Datenstand: <Zeitpunkt>, <n> Belege»).
2. FALLS kein Werkzeug ein passendes Ergebnis liefert, DANN SOLL der Agent sagen, dass er keinen Beleg gefunden hat, und keine Vermutung als Tatsache formulieren.
3. WENN der Benutzer auf eine Quelle tippt, DANN SOLL die App das Dokument öffnen (mit Protokolleintrag).

### Requirement 5: Schutzregeln des Chats
**User Story:** Als Inhaber möchte ich, dass der Chat nur zeigt, was der fragende Benutzer sehen darf,
damit Vertraulichkeit und Mandantentrennung auch im Chat gelten.

#### Acceptance Criteria
1. Die Werkzeuge SOLLEN Mandant und Benutzer ausschliesslich aus dem Anfragekontext beziehen; keine Werkzeug-Parameter für Mandant oder Benutzer.
2. Die Werkzeuge SOLLEN die Vertraulichkeitsregeln (Spec 01, Requirement 2.3, 2.21 und Rechtematrix 2.6, Zeilen A2 bis A4) anwenden, auch in Summen, Anzahlen und Lücken von `kennzahl`, `offene_posten`, `fristen` und `vollstaendigkeit`.
3. Das Archiv SOLL Dokumentinhalte, die an das Sprachmodell gehen, als Daten kennzeichnen; Anweisungen in Dokumenten SOLLEN keine Werkzeuge mit Nebenwirkungen auslösen können (alle Werkzeuge sind nur lesend).
4. Das Archiv SOLL jedes Gespräch mit Fragen, Werkzeugaufrufen, Quellen und Tokens speichern und nach 12 Monaten löschen.
5. FALLS ein Benutzer mehr als 100 Fragen pro Stunde stellt, DANN SOLL das Archiv weitere Fragen bis zum Ablauf der Stunde ablehnen.

### Requirement 6: Chat-Oberfläche
**User Story:** Als Inhaber möchte ich den Chat am Handy und am Mac nutzen,
damit ich unterwegs schnell nachschauen kann.

#### Acceptance Criteria
1. Die App SOLL eine Chat-Seite mit Eingabefeld, gestreamter Antwort, Quellen-Chips und den letzten 20 Gesprächen bieten.
2. WENN der Agent ein Werkzeug aufruft, DANN SOLL die App einen kurzen Hinweis zeigen («suche Rechnungen …»).
3. Die Chat-Seite SOLL bei 375 px Breite ohne horizontales Scrollen bedienbar sein.

### Requirement 7: Jahresexport für den Treuhänder (B4)
**User Story:** Als Inhaber möchte ich alle Belege eines Geschäftsjahres auf einmal übergeben,
damit der Abschluss ohne Zusammensuchen möglich ist.

#### Acceptance Criteria
1. WENN ein Benutzer mit Rolle `inhaber` oder `treuhaender` einen Export für ein Geschäftsjahr anfordert, DANN SOLL das Archiv als Auftrag ein ZIP erstellen mit den PDF/A-Fassungen aller Dokumente des Geschäftsjahres im Status `ARCHIVIERT` (bei Vermerk `pdfa_fehlt` nach Spec 01, 5.9 mit dem Original), `belegliste.csv` (ID, Art, Datum, Partner, Belegnummer, Richtung, Betrag, Währung, SHA-256, Dateiname), `pruefsummen.sha256` und den Manifesten des Jahres.
2. WENN der Export fertig ist, DANN SOLL das Archiv einen Download-Link (24 Stunden gültig) anzeigen und per E-Mail melden.
3. Das Archiv SOLL jeden Export mit Benutzer, Zeit und Prüfsumme des ZIP protokollieren.
4. Dateinamen im ZIP SOLLEN dem Muster `<datum>_<art>_<belegnummer|id>_<partner>.pdf` folgen (nur ASCII, ohne Leerzeichen).
5. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `geschaeftsstelle` einen Jahresexport anfordert oder abruft, DANN SOLL das Archiv mit HTTP 403 antworten und keinen Auftrag einreihen.
6. Die E-Mail aus 7.2 SOLL nur den Link und die Export-ID enthalten, keine Dokumentinhalte (Spec 01, Requirement 16.7).

### Requirement 8: AbaNinja-Verbindung (I3, nur bei Entscheid E1 = ja)
**User Story:** Als Inhaber möchte ich AbaNinja einmal verbinden,
damit Kontakte, Rechnungen und Belege automatisch abgeglichen werden.

#### Acceptance Criteria
1. WO für den Mandanten eine AbaNinja-Verbindung hinterlegt ist, SOLL das Archiv die Funktionen der Requirements 9–12 ausführen; sonst SOLL das Archiv sie nicht anbieten und keine Fehler melden.
2. Das Archiv SOLL API-Token und Webhook-Geheimnis nur verschlüsselt (AES-GCM, Schlüssel nur als Secret-Datei unter `/run/secrets/` nach Spec 01, 16.9) speichern und nie in Logs oder Antworten ausgeben.
3. WENN der Inhaber die Verbindung speichert, DANN SOLL das Archiv sie mit einem Testaufruf prüfen und das Ergebnis anzeigen.
4. FALLS AbaNinja mit HTTP 429 oder 5xx antwortet, DANN SOLL das Archiv mit exponentiellem Backoff wiederholen und nach 5 Fehlversuchen den Inhaber benachrichtigen.
5. Das Archiv SOLL nie Zahlungen in AbaNinja erfassen oder Rechnungen als bezahlt markieren.

### Requirement 9: Kontakte aus AbaNinja
#### Acceptance Criteria
1. WENN der tägliche Abgleich läuft, DANN SOLL das Archiv Firmen- und Personenadressen aus AbaNinja lesen und mit den Kontakten im Archiv abgleichen (AbaNinja-UUID, sonst E-Mail, sonst Name + PLZ).
2. Das Archiv SOLL Felder aus dem CSV-Import, die AbaNinja nicht kennt, beim Abgleich unverändert lassen.
3. FALLS ein Kontakt in beiden Quellen widersprüchliche Adressen hat, DANN SOLL das Archiv den AbaNinja-Wert übernehmen und die Abweichung in einer Liste «Kontakte prüfen» zeigen.

### Requirement 10: Ausgangsrechnungen mit Status aus AbaNinja
#### Acceptance Criteria
1. Das Archiv SOLL Rechnungen aus AbaNinja über die Belegnummer mit archivierten Ausgangsrechnungen verknüpfen; Aliasnummern (z. B. `R0001` = `2026-001`) SOLLEN in einer Alias-Tabelle gepflegt werden können.
2. WENN sich Status, Zahlungsdatum oder offener Betrag einer Rechnung in AbaNinja ändert, DANN SOLL das Archiv `zahlstatus` und `bezahlt_am` aktualisieren, ohne eine Metadaten-Version mit Grund zu verlangen (Systemänderung, protokolliert).
3. FALLS eine AbaNinja-Rechnung kein Archivdokument hat, DANN SOLL das Archiv das PDF aus AbaNinja holen und als Dokument (Quelle `ABANINJA`) in den Eingangskorb legen.

### Requirement 11: Lieferantenbelege aus AbaNinja
#### Acceptance Criteria
1. WENN in AbaNinja ein Lieferantenbeleg erstellt oder geändert wird, DANN SOLL das Archiv Belegnummer (`F0001` …), Lieferant, Betrag, Fälligkeit und Status übernehmen.
2. WENN zum Beleg ein PDF existiert und im Archiv noch kein nicht verworfenes Dokument mit gleicher Prüfsumme (Spec 01, 3.4) oder Belegnummer vorhanden ist, DANN SOLL das Archiv das PDF als Dokument in den Eingangskorb legen; sonst SOLL es nur verknüpfen.

### Requirement 12: Webhooks von AbaNinja
#### Acceptance Criteria
1. Das Archiv SOLL einen Webhook-Endpunkt pro Mandant anbieten und die Echtheit jeder Meldung gemäss AbaNinja-Dokumentation prüfen.
2. FALLS die Echtheit nicht bestätigt werden kann, DANN SOLL das Archiv mit HTTP 401 antworten und nichts verarbeiten.
3. WENN eine gültige Meldung eintrifft, DANN SOLL das Archiv innert 1 Sekunde mit HTTP 2xx antworten und die Verarbeitung als Auftrag einreihen; doppelte Meldungen SOLLEN keine doppelte Wirkung haben.
4. Der tägliche Abgleich SOLL verpasste Webhooks nachholen.

### Requirement 13: Abnahme des Chats
**User Story:** Als Inhaber möchte ich vor dem Einsatz wissen, dass der Chat richtig antwortet,
damit ich mich auf ihn verlassen kann.

#### Acceptance Criteria
1. Das Archiv-Projekt SOLL eine Prüfliste mit den 7 Beispielfragen aus `product.md` und mindestens 13 weiteren Fragen mit erwarteter Antwort und erwarteten Quellen verwenden; weil die erwarteten Antworten echte Geschäftsdaten enthalten, SOLL sie wie der Golden-Datensatz (Spec 01, 0.1) nur ausserhalb des Repositorys unter `ARCHIV_GOLDEN_DIR` liegen, im Repository nur Schema und Test.
2. WENN beim Lauf der Prüfliste gegen den Produktivbestand alle 7 Beispielfragen und mindestens 18 von 20 Fragen insgesamt korrekt und mit Quelle beantwortet werden, DANN SOLL das Gate «Phase 2 bestanden» gelten. Der Lauf erfolgt nur manuell, nie in der CI. Nachweis: manuell [Fabian].

## Optionen (nicht Teil des Plans, Aufgaben mit `*`)
- I4: eigene Rechnungen als PDF nach AbaNinja importieren (ersetzt Nacherfassung).
- I5: Bankbewegungen aus AbaNinja für «bezahlt am».
- C3: Archiv-Werkzeuge als MCP-Server für Claude (nur lesend, Token pro Benutzer).

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| D7 | 1 |
| S2 | 2 |
| C1 | 3, 5, 6 |
| C2 | 4 |
| B4 | 7 |
| I3 | 8–12 |
| Gate Phase 2 | 13 |
