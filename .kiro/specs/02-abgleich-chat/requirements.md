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
2. Das Archiv SOLL eine Hybridsuche anbieten: je 50 Treffer aus Volltext- und Vektorsuche, zusammengeführt mit RRF (k = 60), die besten 30 neu sortiert durch den Reranker. Spec 03, 5.7 ergänzt dieses Kriterium (13.11.2026, vorgeschlagen, entschieden mit dem Merge des Spec-PR von Spec 03): Jeder Chunk trägt den Seitenbereich (`seite_von`, `seite_bis` aus den Seitentrennern, sonst leer), und die Hybridsuche gibt je Dokument den besten Chunk mit, damit Treffer die Fundstelle zeigen können (umgesetzt in Spec 02, Aufgaben 6.3 und 6.4, und Spec 03, Aufgabe 11.13).
3. FALLS der Embedding- oder Reranker-Dienst nicht erreichbar ist, DANN SOLL das Archiv auf die reine Volltextsuche zurückfallen und dies in der Antwort kennzeichnen.
4. Das Archiv SOLL für bestehende Dokumente eine einmalige Nachberechnung der Embeddings als Auftrag anbieten.
5. Die Hybridsuche SOLL Mandant und Vertraulichkeit genau wie die Volltextsuche einhalten (Spec 01, Requirement 1.2, 2.3 und Rechtematrix 2.6).
6. Die Hybridsuche SOLL bei 50'000 Dokumenten innert 2 Sekunden (p95, Messpunkt Server; Messbedingungen wie Spec 01, Requirement 3.2) antworten.
7. WENN der Benutzer in der Web-App mit einem Suchbegriff sucht, DANN SOLL das Archiv standardmässig die Hybridsuche verwenden und höchstens die 30 besten Treffer in der Reihenfolge des Rerankers zeigen, ohne Blättern und ohne Gesamtzahl; die Volltextsuche nach Spec 01, Requirement 14 SOLL als ausdrücklich gewählter Modus mit ihrem bisherigen Verhalten (Sortierung nach Relevanz oder Datum, Blättern, Gesamtzahl) und als Rückfall nach 2.3 verfügbar bleiben (neu 14.11.2026, entschieden von Fabian: «Hybrid wird Standard»). Suchen ohne Suchbegriff (nur Filter) und die Sortierung nach Datum laufen als Volltextsuche; fällt die Hybridsuche nach 2.3 zurück, zeigt die Web-App die Volltexttreffer in derselben Form (höchstens 30, ohne Blättern) mit dem Hinweis, dass nur im Volltext gesucht wurde (vorgeschlagen 14.11.2026, entschieden mit dem Merge dieses Spec-PR).
8. Das Archiv SOLL jede Hybridsuche in `ki_verbrauch` mit dem Zweck `SUCHE` erfassen (Tokens je Aufruf und Mandant wie Spec 01, Requirement 7.8: eine Zeile je Aufruf von Embedding-Modell und Reranker, ohne Suchbegriff und ohne Inhalte) und diesen Zweck in den Auswertungen des Verbrauchs nach Zweck ausweisen; ein Limit oder Budget für die Suche SOLL es nicht geben (neu 14.11.2026, entschieden von Fabian: «Nur protokollieren»).

### Requirement 3: Chat-Agent mit Werkzeugen (C1)
**User Story:** Als Inhaber möchte ich Fragen zu Dokumenten und Zahlen in eigenen Worten stellen,
damit ich nicht selbst suchen und rechnen muss.

#### Acceptance Criteria
1. Das Archiv SOLL einen Chat-Agenten anbieten, der ausschliesslich diese Werkzeuge aufrufen darf: `dokumente_suchen`, `dokument_zeigen`, `kennzahl`, `offene_posten`, `fristen`, `vollstaendigkeit`. Spec 04, 8.1 ergänzt die Liste um die nur lesenden Werkzeuge `wissen_index` und `wissen_seite_zeigen`; sonst bleibt sie abschliessend (umgesetzt in Spec 04, Aufgabe 15.2). Spec 05, 5.4 und 8.8 ergänzen dieses Kriterium: `vollstaendigkeit` beruht auf dem Lückenbericht nach den Regeln `FOLGE` und `ERWARTET` des Mandanten; `kennzahl` und `offene_posten` bietet das Archiv dem Agenten nur an, wenn der Mandant Finanzfunktionen hat (`Funktionen.finanzVerfuegbar()`; Lückenbericht und Funktionen umgesetzt in Spec 05, Aufgaben 17.6 und 18.2).
2. WENN eine Frage Summen, Anzahlen oder Zeiträume betrifft, DANN SOLL der Agent das Werkzeug `kennzahl` verwenden; das Ergebnis SOLL aus einer vordefinierten SQL-Abfrage stammen und unverändert in die Antwort übernommen werden.
3. Das Werkzeug `kennzahl` SOLL Einnahmen oder Ausgaben für einen Zeitraum liefern, gruppiert nach Jahr, Monat, Partner oder Dokumentart, wahlweise nach Belegdatum oder Zahlungsdatum. Spec 05, 8.8 ergänzt dieses Kriterium: Ohne Finanzfunktionen des Mandanten (`Funktionen.finanzVerfuegbar()`) bietet das Archiv `kennzahl` dem Agenten nicht an (umgesetzt in Spec 05, Aufgabe 18.2).
4. WO der Zahlstatus unbekannt ist (keine AbaNinja-Verbindung), SOLL `offene_posten` die Rechnungen mit Fälligkeit liefern und ausdrücklich angeben, dass der Zahlstatus nicht bekannt ist. Spec 05, 8.8 ergänzt dieses Kriterium: Ohne Finanzfunktionen des Mandanten (`Funktionen.finanzVerfuegbar()`) bietet das Archiv `offene_posten` dem Agenten nicht an (umgesetzt in Spec 05, Aufgabe 18.2).
5. Das Werkzeug `fristen` SOLL Vertragsfristen (`naechste_frist`, `gueltig_bis`), Fälligkeiten und Gültigkeitsdaten von Offerten für einen Zeitraum liefern.
6. Das Werkzeug `vollstaendigkeit` SOLL für ein Geschäftsjahr Lücken melden: fehlende Nummern in den Belegnummern-Folgen, fehlende monatliche Lohnabrechnungen und Kontoauszüge seit Beginn, und – mit AbaNinja – Lieferantenbelege ohne Archivdokument. Spec 05, 5.4 ergänzt dieses Kriterium: Die Lücken in Nummernfolgen und die fehlenden Monate liefert der Lückenbericht nach den Regeln `FOLGE` und `ERWARTET` der Konfiguration des Mandanten statt fester Muster (für `urinet` aus dem Seed: Ausgangsrechnungen, Lohnabrechnungen, Kontoauszüge; Spec 05 design.md, «Regeltypen»; Lückenbericht umgesetzt in Spec 05, Aufgabe 17.6).
7. Der Agent SOLL in Deutsch (Schweiz) antworten, Beträge als `CHF 1'234.50` formatieren und die Datenbasis nennen (z. B. «nach Rechnungsdatum, 3 Rechnungen»).
8. Der Agent SOLL innert 10 Sekunden (p95, Messpunkt Server; Messbedingungen wie Spec 01, Requirement 3.2) mit der ersten Textausgabe beginnen und die Antwort fortlaufend streamen.

### Requirement 4: Antworten mit Quellen (C2)
**User Story:** Als Inhaber möchte ich jede Antwort nachprüfen können,
damit ich der Zahl auch vor dem Treuhänder trauen kann.

#### Acceptance Criteria
1. Jede Antwort SOLL eine Liste der verwendeten Quellen enthalten (Dokumenttitel mit Link oder «Datenstand: <Zeitpunkt>, <n> Belege»). Spec 04, 8.5 ergänzt die Quellenform «Wissensseite (Titel, Link, Stand) mit Quelldokumenten» (umgesetzt in Spec 04, Aufgabe 15.2).
2. FALLS kein Werkzeug ein passendes Ergebnis liefert, DANN SOLL der Agent sagen, dass er keinen Beleg gefunden hat, und keine Vermutung als Tatsache formulieren. Spec 05, 5.4 ergänzt dieses Kriterium: Das Werkzeug `vollstaendigkeit` bietet das Archiv nur Rollen an, die A3 und A4 uneingeschränkt sehen (`inhaber`, `treuhaender`, `geschaeftsstelle`); für andere Rollen fehlt es in der Liste der Werkzeuge, statt gefilterte Lücken zu liefern, die falsche Lücken zeigen könnten.
3. WENN der Benutzer auf eine Quelle tippt, DANN SOLL die App das Dokument öffnen (mit Protokolleintrag).

### Requirement 5: Schutzregeln des Chats
**User Story:** Als Inhaber möchte ich, dass der Chat nur zeigt, was der fragende Benutzer sehen darf,
damit Vertraulichkeit und Mandantentrennung auch im Chat gelten.

#### Acceptance Criteria
1. Die Werkzeuge SOLLEN Mandant und Benutzer ausschliesslich aus dem Anfragekontext beziehen; keine Werkzeug-Parameter für Mandant oder Benutzer.
2. Die Werkzeuge SOLLEN die Vertraulichkeitsregeln (Spec 01, Requirement 2.3, 2.21 und Rechtematrix 2.6, Zeilen A2 bis A4) anwenden, auch in Summen, Anzahlen und Lücken von `kennzahl`, `offene_posten`, `fristen` und `vollstaendigkeit`. Spec 05, 5.4 ergänzt dieses Kriterium: `vollstaendigkeit` erhalten nur Rollen, die A3 und A4 uneingeschränkt sehen; für andere Rollen fehlt das Werkzeug in der Liste, statt gefilterte Lücken zu liefern, die falsche Lücken zeigen könnten.
3. Das Archiv SOLL Dokumentinhalte, die an das Sprachmodell gehen, als Daten kennzeichnen; Anweisungen in Dokumenten SOLLEN keine Werkzeuge mit Nebenwirkungen auslösen können (alle Werkzeuge sind nur lesend).
4. Das Archiv SOLL jedes Gespräch mit Fragen, Werkzeugaufrufen, Quellen und Tokens speichern und nach 12 Monaten löschen.
5. FALLS ein Benutzer mehr als 100 Fragen pro Stunde stellt, DANN SOLL das Archiv weitere Fragen bis zum Ablauf der Stunde ablehnen.

### Requirement 6: Chat-Oberfläche
**User Story:** Als Inhaber möchte ich den Chat am Handy und am Mac nutzen,
damit ich unterwegs schnell nachschauen kann.

#### Acceptance Criteria
1. Die App SOLL eine Chat-Seite mit Eingabefeld, gestreamter Antwort, Quellen-Chips und den letzten 20 Gesprächen bieten. Spec 04, 12.4 und 16.6 ergänzen: Quellen-Chips zeigen Wissensseiten und Dokumente unterscheidbar, und Benutzer mit Recht A18 erhalten an einer Antwort die Aktion «Als Analyse vorschlagen», nur in Gesprächen ohne Aufruf von `kennzahl`, `offene_posten`, `fristen` oder `vollstaendigkeit` (umgesetzt in Spec 04, Aufgabe 15.3).
2. WENN der Agent ein Werkzeug aufruft, DANN SOLL die App einen kurzen Hinweis zeigen («suche Rechnungen …»).
3. Die Chat-Seite SOLL bei 375 px Breite ohne horizontales Scrollen bedienbar sein.

### Requirement 7: Jahresexport für den Treuhänder (B4)
**User Story:** Als Inhaber möchte ich alle Belege eines Geschäftsjahres auf einmal übergeben,
damit der Abschluss ohne Zusammensuchen möglich ist.

#### Acceptance Criteria
1. WENN ein Benutzer mit Rolle `inhaber` oder `treuhaender` einen Export für ein Geschäftsjahr anfordert, DANN SOLL das Archiv als Auftrag ein ZIP erstellen mit den PDF/A-Fassungen aller Dokumente des Geschäftsjahres im Status `ARCHIVIERT` (bei Vermerk `pdfa_fehlt` nach Spec 01, 5.9 mit dem Original), `belegliste.csv` (ID, Art, Datum, Partner, Belegnummer, Richtung, Betrag, Währung, SHA-256, Dateiname), `pruefsummen.sha256` und den Manifesten des Jahres. Spec 04, 1.6 ergänzt: Dokumente der Art `WISSENSQUELLE` SOLLEN nicht in den Jahresexport und nicht in `belegliste.csv` gelangen, weil sie keine Belege sind (umgesetzt in Spec 04, Aufgabe 13.2). Spec 05, 8.8 ergänzt dieses Requirement: Hat der Mandant keine Finanzfunktionen (`Funktionen.finanzVerfuegbar()`), beantwortet das Archiv den Jahresexport für Rollen mit Exportrecht mit HTTP 404; für die übrigen Rollen bleibt es bei HTTP 403 nach 7.5 (umgesetzt in Spec 05, Aufgabe 18.2).
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
