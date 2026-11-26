# Requirements Document — 07 Einführung und Hilfe

## Introduction
Diese Spec gibt der Web-App zwei Hilfen für die Menschen, die sie bedienen: (1) eine kurze **Einführung** auf der
Startseite, die einen neuen Benutzer in wenigen Schritten durch die Bereiche führt, die seine Rolle nutzen darf, und
(2) eine **Benutzeranleitung**, die über den Menüpunkt «Hilfe» erscheint: kontextbezogen in einer Hilfe-Leiste über der
aktuellen Seite und vollständig auf der Seite `/hilfe`, gegliedert nach Bereichen und auf die eigene Rolle zugeschnitten.
Heute zeigt die Startseite nach der Anmeldung einen Gruss und die Kacheln der erlaubten Bereiche
(`frontend/src/app/start/start.ts`); eine Anleitung gibt es nur als Dateien unter `docs/`
(`docs/anleitung-geschaeftsstelle.md`, Entwurf `docs/anleitung-verwaltung.md`), die in der App nicht erreichbar sind.

Leitgedanken (Begründung in design.md):
- **Nur Oberfläche.** Kein neuer Endpunkt, keine Tabelle, kein Protokolleintrag, keine Änderung an Rechten oder Rollen.
  Was eine Rolle sieht, folgt aus derselben Rechteprüfung wie das Menü (Rechtematrix Spec 01, 2.6 und Spec 04, 12.1; im
  Frontend `gemeinsam/anmeldung/rechte.ts`); die Oberfläche blendet nur aus, die API prüft wie bisher.
- **Die Anleitung kommt mit der App.** Sie liegt als Markdown im Repository, wird mit der App gebaut und vom Service
  Worker vorab geladen; sie ist offline lesbar, weil die Handy-Erfassung der Hauptfall ist.
- **Die Anleitung beschreibt nur, was es gibt.** Wer eine Funktion der Oberfläche ändert, zieht Anleitung und Einführung
  im selben PR nach (Requirement 7.5).
- **Nichts verlässt das Gerät.** Ob jemand die Einführung gesehen hat, merkt sich nur der Browser; niemand zählt, wer die
  Hilfe liest.

Nicht Teil dieser Spec: Videos, Bildschirmfotos (erste Fassung), Mehrsprachigkeit, Tracking oder Auswertung der Nutzung,
Änderungen an Rechtematrix, Rollen oder Endpunkten, Suche in der Anleitung, Antworten der KI auf Fragen zur Bedienung (der
Chat bleibt für Dokumente), Hilfe auf der WordPress-Seite (Spec 03), Hinweise «Was ist neu» nach Updates, Anleitungen für
den Betreiber (bleiben in README und `docs/`).

Einordnung: Wellen 22 und 23. Grundlage ist der Auftrag von Fabian vom 21.11.2026 (ohne Konzept-ID, wie Spec 04). Die
Spec braucht nur das bestehende Frontend (App-Rahmen, Navigation, Branding, Anmeldung) und kann nach dem Merge parallel zu
den übrigen Wellen umgesetzt werden (vorgeschlagen 21.11.2026, entschieden mit dem Merge dieses PR). Schreibweise der
Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit den Vermerken «manuell [Fabian]»,
«Dokument» bzw. «JUnit») und `[offen]`-Marker wie in Spec 01; «Nachweis: JUnit» steht in dieser Spec für Unit-Tests des
Frontends mit Vitest (ohne Cucumber), weil die Abdeckungsprüfung nur diese drei Vermerke kennt. Die Szenarien liegen unter
`features/hilfe/` mit dem Tag `@spec-07`, Browser-Szenarien zusätzlich mit `@browser`. «Spec 0n, x.y» bezeichnet ein
Akzeptanzkriterium, «Spec 0n, Aufgabe x.y» eine Aufgabe. Vorschläge tragen den Vermerk «vorgeschlagen 21.11.2026,
entschieden mit dem Merge dieses PR»; die Liste steht in design.md, «Entscheide».

## Glossary
- **Einführung** – Folge von höchstens sechs Schritten in einer Karte auf der Startseite (Requirement 2).
- **Schritt** – Titel, ein bis drei Sätze und ein Verweis auf den passenden Abschnitt der Anleitung; jeder Schritt hat
  eine Bedingung aus Aktionen der Rechtematrix.
- **Einführungsvermerk** – Eintrag im `localStorage` des Browsers, dass ein Benutzer eines Mandanten die Einführung
  abgeschlossen oder übersprungen hat (Requirement 1.3); ohne Benutzer-ID, Namen oder Rolle im Klartext.
- **Neuer Benutzer** – angemeldeter Benutzer mit Mandant und einer der vier Rollen, für den in diesem Browser kein
  Einführungsvermerk besteht. Ein **bekannter Benutzer** hat einen.
- **Benutzeranleitung** (kurz **Anleitung**) – alle Texte, die die Hilfe-Leiste und die Seite `/hilfe` zeigen.
- **Abschnitt** – Teil der Anleitung zu einem Bereich der App, mit fester Sprungmarke; ein **Unterabschnitt** gehört zu
  einem Abschnitt und kann eine eigene Bedingung haben.
- **Bedingung** – Liste von Aktionen der Rechtematrix; erfüllt, wenn die Rolle mindestens eine davon darf und, falls
  angegeben, keine der ausgeschlossenen Aktionen. Ohne Bedingung ist ein Abschnitt **allgemein**.
- **Hilfe-Katalog** – die eine Liste im Code mit allen Abschnitten: Anker, Titel, Datei, übergeordneter Abschnitt,
  Bedingung, zugeordnete Pfade der App (design.md, «Hilfe-Katalog»).
- **Sprungmarke** – Anker eines Abschnitts in der Adresse, `/hilfe#<anker>`.
- **Hilfe-Leiste** – Bereich über der aktuellen Seite, der die Anleitung kontextbezogen zeigt, ohne die Seite zu verlassen
  (Requirement 4).
- **Kontext** – der Abschnitt, den der Hilfe-Katalog der aktuellen Adresse zuordnet (z. B. `/eingangskorb` →
  `#eingangskorb`).

---

### Requirement 1: Neuer Benutzer – erkennen und merken (ohne Konzept-ID)
**User Story:** Als neue Person im Archiv möchte ich beim ersten Besuch eine kurze Einführung sehen, und als bekannte
Person möchte ich sie danach nicht mehr sehen, damit ich mich schnell zurechtfinde, ohne gestört zu werden.

#### Acceptance Criteria
1. WENN ein angemeldeter Benutzer mit Mandant und einer der vier Rollen die Startseite `/` öffnet und in diesem Browser
   für seinen Mandanten (Claim `mandant`) und seine Benutzer-ID (Claim `sub`) kein Einführungsvermerk besteht, DANN SOLL
   die App die Einführung zeigen.
2. WENN für den angemeldeten Benutzer ein Einführungsvermerk besteht, DANN SOLL die App die Startseite ohne Einführung
   zeigen, mit Gruss und Kacheln wie bisher (Spec 01, Aufgabe 2.7, und der Anzahl im Eingangskorb nach Spec 01, 8.6).
3. WENN der Benutzer die Einführung abschliesst oder überspringt, DANN SOLL die App den Einführungsvermerk im
   `localStorage` unter dem Schlüssel `archiv.einfuehrung` ablegen: je Benutzer ein Eintrag mit dem SHA-256 von
   `<mandant>:<sub>` als Kennung, der Version der Einführung, der Art (`ABGESCHLOSSEN` oder `UEBERSPRUNGEN`) und dem Datum.
   Die App SOLL weder Benutzer-ID, Benutzername, Name, E-Mail noch Rolle im Klartext ablegen und den Vermerk nie an den
   Server senden (vorgeschlagen 21.11.2026, entschieden mit dem Merge dieses PR).
4. Die App SOLL die Vermerke je Mandant und Benutzer getrennt führen: Meldet sich im selben Browser ein anderer Benutzer
   an oder derselbe Benutzer in einem anderen Mandanten, SOLL für ihn die Einführung erscheinen, solange er keinen eigenen
   Vermerk hat; die Vermerke anderer Benutzer SOLLEN dabei unverändert bleiben.
5. FALLS der Browser den Speicher nicht erlaubt oder der gespeicherte Wert nicht lesbar ist (privates Fenster, gesperrter
   Speicher, beschädigter Eintrag), DANN SOLL die App die Einführung zeigen, den Vermerk nach Abschluss oder Überspringen
   bis zum Neuladen der Seite im Arbeitsspeicher halten, einen beschädigten Eintrag beim nächsten Schreiben ersetzen und
   keine Fehlermeldung zeigen.
6. Die App SOLL höchstens 20 Einführungsvermerke führen und beim Schreiben eines weiteren den ältesten entfernen.
   Nachweis: JUnit (Vitest `einfuehrung-vermerk.spec.ts`).
7. FALLS dem angemeldeten Benutzer kein Mandant oder keine der vier Rollen zugeordnet ist, DANN SOLL die App keine
   Einführung zeigen, sondern wie bisher die Meldung «Ihrem Benutzer ist kein Mandant oder keine Rolle im Archiv
   zugeordnet. Bitte wenden Sie sich an den Inhaber.»

### Requirement 2: Inhalt der Einführung nach Rolle und Mandant (ohne Konzept-ID)
**User Story:** Als neue Person möchte ich in der Einführung nur die Bereiche kennenlernen, die ich nutzen darf, damit
ich nicht nach Funktionen suche, die mir nicht offenstehen.

#### Acceptance Criteria
1. Die Einführung SOLL aus höchstens sechs Schritten in dieser Reihenfolge bestehen und jeden Schritt nur zeigen, wenn
   seine Bedingung erfüllt ist (Texte nach design.md, «Schritte der Einführung»; vorgeschlagen 21.11.2026, entschieden mit
   dem Merge dieses PR):

| Nr. | Schritt | Inhalt | Bedingung |
| --- | --- | --- | --- |
| 1 | Willkommen | Name des Mandanten aus dem Branding, wozu das Archiv dient, die eigene Rolle | immer |
| 2 | Dokumente erfassen | Fotografieren am Handy, Dateien hochladen, Warteschlange ohne Netz | A1 |
| 3 | Prüfen und bestätigen | Eingangskorb und Vorschlag der KI; mit A7 prüfen und bestätigen, ohne A7 die selbst erfassten Dokumente verfolgen | A5 |
| 4 | Finden | Suche, Chat mit Quelle, Dokumentansicht; Wissen, wenn A15 | A2 |
| 5 | Bestand und Abschluss | nur die erlaubten unter Kontakte (A6), Importe (A12), Jahresexport (`EXPORT`), Verwaltung (A11, A13, A14) | mindestens eine davon |
| 6 | Hilfe finden | wo die Hilfe ist und wie die Einführung erneut erscheint | immer |

2. Die Einführung SOLL keinen Bereich und keine Aktion nennen, die die Rolle nach der Rechtematrix nicht darf, und dafür
   dieselbe Rechteprüfung verwenden wie das Menü (`darf` in `rechte.ts`), ohne eigene Kopie der Rechte. Im Mandanten
   `urinet` ergeben sich damit: `inhaber` Schritte 1 bis 6; `mitarbeiter` 1, 2, 3 (ohne Bestätigen), 4 und 6;
   `treuhaender` 1, 4, 5 (nur Jahresexport) und 6; im Mandanten `vhf-gsk` `geschaeftsstelle` 1, 2, 3, 4, 5 (nur Kontakte)
   und 6.
3. Die Einführung SOLL Namen und Logo des Mandanten aus dem Branding zeigen (`GET /branding`, Spec 03, 2.1; im Frontend
   `BrandingService`) und keinen Mandantennamen fest im Text führen.
4. Die Texte der Einführung und der Anleitung SOLLEN für alle Mandanten gleich sein; nur Name und Logo aus dem Branding
   unterscheiden sich, und der Code SOLL nie die Kennung eines Mandanten abfragen. Ein zusätzlicher Satz zu den auf der
   Webseite erscheinenden Dokumenten ist nicht vorgesehen (Entscheid Fabian vom 24.11.2026).

### Requirement 3: Bedienung der Einführung (ohne Konzept-ID)
**User Story:** Als neue Person möchte ich die Einführung in meinem Tempo durchgehen, jederzeit überspringen und später
wieder aufrufen können, damit sie mir hilft und mich nicht aufhält.

#### Acceptance Criteria
1. Die App SOLL die Einführung als Karte (Baustein `.karte`) zwischen Seitenkopf und Kacheln der Startseite zeigen, nicht
   als modalen Dialog; die Kacheln SOLLEN darunter sichtbar und bedienbar bleiben (vorgeschlagen 21.11.2026, entschieden
   mit dem Merge dieses PR).
2. Die Einführung SOLL in jedem Schritt den Fortschritt als Text «Schritt <n> von <m>» (m = Zahl der Schritte der Rolle),
   den Knopf «Überspringen», ab Schritt 2 den Knopf «Zurück» und im letzten Schritt «Fertig» statt «Weiter» zeigen.
3. WENN der Benutzer im letzten Schritt «Fertig» wählt, DANN SOLL die App den Vermerk `ABGESCHLOSSEN` ablegen, die Karte
   schliessen, den Fokus auf die Überschrift des Seitenkopfs setzen und als Statusmeldung (`role="status"`) «Einführung
   abgeschlossen. Sie finden sie jederzeit unter ‹Hilfe›.» zeigen.
4. WENN der Benutzer «Überspringen» wählt, DANN SOLL die App den Vermerk `UEBERSPRUNGEN` ablegen, die Karte schliessen,
   den Fokus auf die Überschrift des Seitenkopfs setzen und als Statusmeldung «Einführung übersprungen. Sie finden sie
   jederzeit unter ‹Hilfe›.» zeigen.
5. WENN der Benutzer in der Anleitung «Einführung erneut zeigen» wählt, DANN SOLL die App die Startseite mit der
   Einführung ab Schritt 1 zeigen (Adresse `/?einfuehrung`), auch wenn ein Vermerk besteht; Abschluss oder Überspringen
   SOLLEN den Vermerk ersetzen.
6. WENN der Benutzer die Startseite verlässt, ohne die Einführung abzuschliessen oder zu überspringen, DANN SOLL kein
   Vermerk entstehen und die App den aktuellen Schritt bis zum Neuladen der Seite behalten; nach dem Neuladen beginnt die
   Einführung wieder bei Schritt 1.
7. Die Einführung SOLL als Bereich mit Überschrift (`section` mit `aria-labelledby`) erscheinen, mit der Tastatur
   vollständig bedienbar sein, beim Wechsel des Schritts den Fokus auf die Überschrift des neuen Schritts setzen und den
   Fortschritt nie nur über Farbe oder Punkte zeigen; Übergänge SOLLEN `prefers-reduced-motion` beachten.
8. Die Einführung SOLL nichts ausser Navigation auslösen: keine Daten ändern und keine Anfrage an die API stellen.

### Requirement 4: Menüpunkt «Hilfe» und Hilfe-Leiste (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich auf jeder Seite mit einem Griff die Anleitung zu dieser Seite öffnen, ohne dass
meine Eingaben, Aufnahmen oder mein Chatverlauf verloren gehen, damit ich mir selbst weiterhelfen kann.

#### Acceptance Criteria
1. Die App SOLL in der Kopfzeile des App-Rahmens neben dem Knopf für das Farbschema einen Knopf «Hilfe» mit dem neuen
   Symbol `hilfe` zeigen, für jeden Besucher (angemeldet mit Rolle, angemeldet ohne Rolle und abgemeldet); auf schmalen
   Bildschirmen nur das Symbol mit dem zugänglichen Namen «Hilfe» (vorgeschlagen 21.11.2026, entschieden mit dem Merge
   dieses PR).
2. WENN der Benutzer «Hilfe» wählt, DANN SOLL die App die Hilfe-Leiste über der aktuellen Seite öffnen, ohne zu
   navigieren: Adresse, Eingaben, noch nicht übernommene Aufnahmen und der Chatverlauf der Seite darunter SOLLEN
   unverändert bleiben (vorgeschlagen 21.11.2026, entschieden mit dem Merge dieses PR).
3. Die Hilfe-Leiste SOLL den Abschnitt zeigen, den der Hilfe-Katalog der aktuellen Adresse zuordnet (längster passender
   Pfad wie beim Menü, `aktiverPfad` in `navigation.ts`); FALLS keine Zuordnung besteht oder der Abschnitt für die Rolle
   nicht sichtbar ist (Requirement 6.2), DANN SOLL sie das Inhaltsverzeichnis der sichtbaren Abschnitte zeigen.
4. Die Hilfe-Leiste SOLL den Titel des Abschnitts, seinen Text, einen Link «Ganze Anleitung öffnen» (`/hilfe#<anker>`)
   und den Knopf «Schliessen» zeigen. Links auf andere Abschnitte SOLLEN den Inhalt der Leiste wechseln, ohne zu
   navigieren; Links auf Seiten der App SOLLEN navigieren und die Leiste schliessen.
5. Die Hilfe-Leiste SOLL als Dialog mit dem Namen «Hilfe» über der Seite liegen (ab 960 px rechts, mindestens 480 px,
   höchstens 760 px und etwa 42 % der Fensterbreite breit, darunter in ganzer Breite; geändert 26.11.2026 nach der
   Abnahme, vorher höchstens 420 px), beim Öffnen den Fokus auf ihre Überschrift setzen, den Fokus in der Leiste halten,
   mit Escape und «Schliessen» schliessen und den Fokus danach auf den Knopf «Hilfe» zurückgeben.
6. Die App SOLL zusätzlich an diesen Stellen einen Link «So funktioniert es» zeigen, der die Hilfe-Leiste mit dem
   genannten Unterabschnitt öffnet: auf der Seite «Erfassen» bei der Warteschlange und bei fehlerhaften Uploads
   (`#erfassen-offline`), auf der Anmeldekarte der Startseite ohne Netz (`#erfassen-offline`) und bei der Meldung ohne
   Mandant oder Rolle (`#probleme`).

7. Das Inhaltsverzeichnis der Hilfe-Leiste SOLL kompakt sein (Abschnitte nebeneinander) und sich auf- und zuklappen
   lassen (Knopf «Abschnitte» mit dem gewählten Abschnitt und `aria-expanded`); beim Öffnen der Leiste mit einem Abschnitt ist es
   zugeklappt, damit der Text den Platz bekommt; ohne Abschnitt ist es aufgeklappt. Aufgenommen am 26.11.2026 nach der Abnahme (Fabian).

### Requirement 5: Seite `/hilfe` – die ganze Anleitung (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich die ganze Anleitung für meine Rolle an einem Ort lesen und gezielt zu einem Bereich
springen können, damit ich nachschlagen und Links auf eine Stelle weitergeben kann.

#### Acceptance Criteria
1. Die App SOLL unter `/hilfe` eine Seite ohne Rechte-Wächter anbieten, mit Seitenkopf (Vorzeile: Name aus dem Branding,
   Überschrift «Hilfe», Untertitel), einem Inhaltsverzeichnis (`nav` mit dem Namen «Inhalt der Anleitung») der sichtbaren
   Abschnitte und den Abschnitten in derselben Reihenfolge; der Titel im Browser lautet «Hilfe» mit dem Namen aus dem
   Branding wie bei den übrigen Seiten.
2. Jeder Abschnitt und Unterabschnitt SOLL eine feste Sprungmarke haben (Liste in design.md, «Hilfe-Katalog»). WENN
   `/hilfe#<anker>` geöffnet wird, DANN SOLL die App zum Abschnitt scrollen und den Fokus auf seine Überschrift setzen;
   FALLS der Anker unbekannt oder für die Rolle nicht sichtbar ist, DANN SOLL sie die Seite von oben zeigen, ohne
   Fehlermeldung und ohne Hinweis auf verborgene Abschnitte.
3. WENN der Benutzer einen Eintrag des Inhaltsverzeichnisses wählt, DANN SOLL die App die Adresse auf `/hilfe#<anker>`
   setzen und nach 5.2 springen; der Zurück-Knopf des Browsers SOLL zur vorherigen Stelle führen.
4. Die Seite SOLL bei 375 px einspaltig sein (Inhaltsverzeichnis als Karte oben) und ab etwa 1100 px zweispaltig mit dem
   Inhaltsverzeichnis links, das beim Scrollen sichtbar bleibt.
5. Die Seite SOLL angemeldeten Benutzern mit Rolle den Knopf «Einführung erneut zeigen» anbieten (Requirement 3.5).

### Requirement 6: Inhalt der Anleitung nach Rolle (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich in der Anleitung genau die Bereiche finden, die ich nutzen darf, beschrieben mit
den Begriffen, die ich in der App sehe, damit ich die Schritte direkt nachvollziehen kann.

#### Acceptance Criteria
1. Die Anleitung SOLL diese Abschnitte in dieser Reihenfolge führen (Unterabschnitte und Texte nach design.md,
   «Hilfe-Katalog»; vorgeschlagen 21.11.2026, entschieden mit dem Merge dieses PR):

| Abschnitt | Anker | Bedingung |
| --- | --- | --- |
| Erste Schritte (Anmelden, Navigation, Farbschema, Abmelden, die eigene Rolle) | `erste-schritte` | allgemein |
| Erfassen, auch am Handy und ohne Netz | `erfassen` | A1 |
| Eingangskorb | `eingangskorb` | A5 |
| Suche | `suche` | A2 |
| Chat | `chat` | A2 |
| Dokumentansicht | `dokument` | A2 |
| Kontakte | `kontakte` | A6 |
| Wissen | `wissen` | A15 |
| Importe | `importe` | A12 |
| Jahresexport | `jahresexport` | `EXPORT` |
| Verwaltung | `verwaltung` | A11, A13 oder A14 |
| Wenn etwas nicht klappt | `probleme` | allgemein |

2. Die App SOLL einem angemeldeten Benutzer mit Rolle genau die Abschnitte und Unterabschnitte zeigen, deren Bedingung
   seine Rolle erfüllt, mit derselben Rechteprüfung wie das Menü; andere SOLLEN weder im Inhaltsverzeichnis noch im Text
   noch in der Hilfe-Leiste erscheinen.
3. WENN niemand angemeldet ist oder dem Benutzer kein Mandant oder keine Rolle zugeordnet ist, DANN SOLL die Anleitung nur
   die allgemeinen Abschnitte und den Unterabschnitt `erfassen-offline` zeigen, dazu den Hinweis «Nach der Anmeldung sehen
   Sie die Anleitung für alle Bereiche Ihrer Rolle.»
4. Die Anleitung SOLL nur Funktionen beschreiben, die auf `main` umgesetzt sind; geplante Funktionen SOLLEN nicht
   erscheinen, auch nicht als «bald verfügbar». Nachweis: Dokument (Review jeder Aufgabe nach der Pflegeregel 7.5).
5. Die Anleitung SOLL zu Beginn die eigene Rolle mit der Bezeichnung aus der Kopfzeile nennen («Sie sind als
   ‹Mitarbeiter› angemeldet. Diese Anleitung zeigt die Bereiche, die Ihre Rolle nutzen darf.»), ohne eigene Liste der
   Rechte.
6. Jeder Abschnitt SOLL beschreiben, wozu der Bereich dient, die wichtigsten Abläufe als nummerierte Schritte mit den
   Namen der Menüpunkte und Knöpfe aus der App, Einschränkungen der Rolle nur dort, wo eine besteht (z. B. «Sie sehen im
   Eingangskorb nur Dokumente, die Sie selbst erfasst haben.»), und häufige Meldungen mit dem, was zu tun ist.
   Nachweis: Dokument.
7. Der Abschnitt `erfassen` SOLL die Erfassung am Handy als Hauptfall ausführlich beschreiben: Kamera und mehrere Seiten,
   Zuschnitt, Hochladen von Dateien, App auf dem Startbildschirm, Warteschlange ohne Netz mit «wartet auf Upload»,
   automatisches Hochladen, fehlerhafte Einträge und Wiederholung (Spec 01, Requirement 4). Nachweis: Dokument.

### Requirement 7: Quelle, Darstellung und Pflege der Texte (ohne Konzept-ID)
**User Story:** Als Entwickler (Agent oder Mensch) möchte ich die Anleitung wie Code im Repository pflegen und mit jeder
Änderung einer Funktion nachführen, damit sie nie veraltet und nichts Gefährliches darstellt.

#### Acceptance Criteria
1. Die Anleitung SOLL als Markdown-Dateien im Repository unter `frontend/public/anleitung/` liegen, je Abschnitt und
   Unterabschnitt eine Datei `<anker>.md`, mit der App gebaut und unter `/anleitung/<anker>.md` ausgeliefert werden; nicht
   unter `docs/` oder `.kiro/` (sonst gälte eine Änderung als «nur Spec», tech.md). Kurze Texte der Oberfläche (Knöpfe,
   Meldungen, Schritte der Einführung) SOLLEN in `texte.de-CH.ts` stehen (vorgeschlagen 21.11.2026, entschieden mit dem
   Merge dieses PR).
2. Der Hilfe-Katalog SOLL die einzige Liste der Abschnitte sein (Anker, Titel, Datei, übergeordneter Abschnitt,
   Bedingung, zugeordnete Pfade). Nachweis: JUnit (Vitest `hilfe-katalog.spec.ts`: jede Datei des Katalogs besteht, jede
   Datei im Ordner steht im Katalog, Anker eindeutig und in Kleinbuchstaben mit Bindestrich, Bedingungen nur aus
   bekannten Aktionen, zugeordnete Pfade sind Routen der App).
3. Die App SOLL das Markdown im Browser in einen Baum aus erlaubten Teilen umsetzen (Überschriften der Stufen 2 bis 4,
   Absätze, Listen, fett, kursiv, Code, Hinweis-Block, Links) und ihn nur über Interpolation darstellen, nie über
   `innerHTML` und ohne `bypassSecurityTrust…`; andere Konstrukte (HTML, Bilder, Tabellen) SOLLEN als Text erscheinen.
   Nachweis: JUnit (Vitest `anleitung-markdown.spec.ts`).
4. Die App SOLL in der Anleitung nur Links auf Pfade der App (beginnend mit `/`, bekannte Route) und auf Sprungmarken
   (`#<anker>`) als Link darstellen; alle anderen Ziele (`http:`, `https:`, `mailto:`, `javascript:`, unbekannte Pfade)
   SOLLEN als Text erscheinen. Nachweis: JUnit (Vitest `anleitung-markdown.spec.ts` und `anleitung-inhalt.spec.ts`: keine
   Datei enthält einen solchen Link).
5. Jeder PR, der eine Funktion der Oberfläche hinzufügt, ändert oder entfernt (auch Menü, Rechte in der Oberfläche,
   Meldungen, die die Anleitung nennt), SOLL die Anleitung und, wo betroffen, Einführung und Hilfe-Katalog im selben PR
   nachführen oder im PR begründen, warum das nicht nötig ist; die Regel steht als Ergänzung der Definition of Done in
   `tech.md` (vorgeschlagen 21.11.2026, entschieden mit dem Merge dieses PR). Nachweis: Dokument.
6. FALLS eine Datei der Anleitung weder vom Server noch aus dem Speicher des Service Workers geladen werden kann, DANN
   SOLL die App beim betroffenen Abschnitt den Hinweis «Dieser Teil der Anleitung ist gerade nicht verfügbar. Bitte
   versuchen Sie es später erneut.» mit dem Knopf «Erneut versuchen» zeigen und die übrigen Abschnitte weiter zeigen.
7. Die Anleitungen unter `docs/` (`anleitung-geschaeftsstelle.md`, `anleitung-verwaltung.md`) SOLLEN nach der Übernahme
   in die App nur noch enthalten, was nicht in die App gehört (Wege des Betreibers, Notweg, Kontaktstellen), und für die
   Bedienung auf die Anleitung in der App verweisen, damit nichts doppelt gepflegt wird; Spec 06, 9.3 und Spec 06, Aufgabe
   21.5 bleiben gültig, der Teil zur Bedienung der Verwaltung kommt mit 21.5 in die App (vorgeschlagen 21.11.2026,
   entschieden mit dem Merge dieses PR). Nachweis: Dokument.

### Requirement 8: Offline verfügbar (ohne Konzept-ID)
**User Story:** Als Benutzer am Handy möchte ich die Anleitung auch ohne Netz lesen, damit ich beim Erfassen unterwegs
nachschlagen kann.

#### Acceptance Criteria
1. Die App SOLL alle Dateien unter `/anleitung/` mit der App im Service Worker vorab laden (Asset-Gruppe mit
   `installMode: prefetch` in `ngsw-config.json`), damit Hilfe-Leiste und Seite `/hilfe` nach dem ersten Laden der App
   ohne Netz vollständig erscheinen.
2. WENN eine neue Version der App bereitsteht, DANN SOLL die neue Anleitung zusammen mit der App aktiv werden (derselbe
   Hinweis «Neue Version verfügbar» wie heute), ohne Mischstand aus alter App und neuer Anleitung.
3. SOLANGE das Gerät offline ist und niemand angemeldet ist, SOLL die Anleitung die Abschnitte nach 6.3 zeigen; die
   Hilfe-Leiste SOLL auf der Seite «Erfassen» den Unterabschnitt `erfassen-offline` zeigen.

### Requirement 9: Sprache, Gestaltung, Barrierefreiheit und Datenschutz (ohne Konzept-ID, nicht funktional)
**User Story:** Als Inhaber möchte ich, dass Einführung und Anleitung so aussehen und klingen wie die übrige App, für alle
zugänglich sind und keine Daten preisgeben, damit sie Vertrauen schaffen.

#### Acceptance Criteria
1. Einführung, Hilfe-Leiste und Seite `/hilfe` SOLLEN nach `docs/oberflaeche/README.md` gestaltet sein (Seitenkopf,
   Karte, Hinweis, Farben nur über Tokens, Symbole über `app-symbol`, neues Symbol `hilfe` am Ende von `SYMBOLE`);
   `npm run pruefe:gestaltung` SOLL grün sein. Nachweis: manuell [Fabian] (Bildschirmfotos bei 375 und 1280 px, hell und
   dunkel, im PR jeder Aufgabe mit Oberfläche).
2. Einführung, Hilfe-Leiste und Seite `/hilfe` SOLLEN ab 375 px ohne waagrechtes Scrollen bedienbar sein und WCAG 2.1 AA
   erfüllen (Tastatur, sichtbarer Fokus, Kontrast in beiden Farbschemata, Überschriften in sinnvoller Reihenfolge,
   Statusmeldungen als `role="status"`); axe SOLL keinen Befund melden.
3. Die Texte SOLLEN in Deutsch (Schweiz) mit «ss» statt «ß» und Guillemets «…» geschrieben sein, Datum und Beträge wie in
   `product.md`, mit der Anrede «Sie» und mit denselben Namen für Menüpunkte und Knöpfe wie in `texte.de-CH.ts`.
   Nachweis: JUnit (Vitest `anleitung-inhalt.spec.ts`: kein «ß», keine Anführungszeichen „…“ oder "…" ausserhalb von
   Code) und Dokument (Review der Wortwahl).
4. WENN die App Einführung, Hilfe-Leiste oder Seite `/hilfe` zeigt, DANN SOLL sie keine Anfrage an einen anderen Ursprung
   und keine Anfrage an `/api/` senden, die es ohne sie nicht gäbe (keine Schriften, Bilder, Skripte oder Videos von
   Dritten, keine Zählung, kein Protokolleintrag).
5. Die Anleitung SOLL nur erfundene und als Beispiel erkennbare Beispiele enthalten (z. B. «Muster AG»), keine Daten
   echter Belege, Kunden oder Personen, auch nicht die Beispielfragen der Chat-Abnahme aus `product.md`, weil sie echte
   Partner nennen. Nachweis: JUnit (Vitest `anleitung-inhalt.spec.ts`: keine Muster von E-Mail-Adressen, Telefonnummern
   und IBAN) und Dokument (Review).
6. Die Spec SOLL weder Rechtematrix noch Rollen, Endpunkte, Tabellen oder das Zugriffsprotokoll ändern; Einführung und
   Anleitung blenden nur aus wie das Menü. Nachweis: Dokument (die PRs dieser Spec ändern unter `backend/` nur
   Feature-Dateien).
7. Die Seite `/hilfe` SOLL erst bei Bedarf geladen werden (`loadComponent`), die Hilfe-Leiste erst beim ersten Öffnen
   (`@defer`), und alle Dateien der Anleitung zusammen SOLLEN höchstens 200 KiB gross sein. Nachweis: JUnit (Vitest
   `anleitung-inhalt.spec.ts`: Summe der Dateigrössen).

### Requirement 10: Tests und Abnahme (ohne Konzept-ID, nicht funktional)
**User Story:** Als Inhaber möchte ich Einführung und Hilfe für jede Rolle geprüft sehen, damit niemand Bereiche gezeigt
bekommt, die er nicht nutzen darf.

#### Acceptance Criteria
1. Das Archiv SOLL für jedes Kriterium dieser Spec ein Szenario mit `@req-<x.y>` und `@spec-07` unter `features/hilfe/`
   haben (Ausnahmen mit Nachweis-Vermerk), Browser-Szenarien mit `@browser` gegen die Attrappen wie bisher und mit allen
   vier Rollen für Einführung, Hilfe-Leiste und Seite `/hilfe`. Nachweis: JUnit und Szenarien.
2. Die Prüfungen der CI SOLLEN `@spec-07` wie die übrigen Specs behandeln: Abdeckungsprüfung (`ReqAbdeckung`, Tag
   `@spec-NN`), Aufteilung der Backend-Szenarien (`SzenarienShards`, alles ausser `@spec-01` in Shard 2) und Cucumber.js
   (`@browser`); `tech.md` nennt `@spec-01` bis `@spec-07`. Nachweis: JUnit (`ReqAbdeckungTest`, `SzenarienShardTest`).
3. Das Archiv SOLL den Abschluss in `docs/abnahme/hilfe.md` festhalten, nur mit Nummern und Ergebnissen (grüne Szenarien
   und Tests; die manuellen Prüfungen von Fabian: Einführung als neuer Benutzer je Rolle auf Stufe A, Handy bei 375 px mit
   iOS und Android, Anleitung offline am Handy, Stichprobe mit Screenreader, Lesetest der Anleitung durch eine Person, die
   das Archiv nicht kennt). Nachweis: Dokument und manuell [Fabian].

---

## Rückverfolgung Auftrag → Spec
| Auftrag Fabian (21.11.2026) | Requirement |
| --- | --- |
| «erstelle eine willkommenseite die insbesondere für neue benutzer eine kleine einführung zeigt» – wer neu ist und wo das gemerkt wird | 1 |
| – Einführung in wenigen Schritten, nach Rolle, Rechten und Mandant | 2 |
| – überspringbar, erneut aufrufbar, bestehende Startseite bleibt für bekannte Benutzer | 1.2, 3 |
| «erstelle zudem eine benutzeranleitung die über ein hilfemenüpunkt angezeigt werden kann» – Menüpunkt und Kontext | 4 |
| – ganze Anleitung mit Inhaltsverzeichnis und Sprungmarken | 5 |
| – Abschnitte je Bereich, nur die der eigenen Rolle | 6 |
| – Quelle der Texte, Darstellung, Pflegeregel | 7 |
| – offline (Handy-Erfassung) | 8 |
| Gestaltung, Sprache, Barrierefreiheit, Datenschutz, Abgrenzung | 9 |
| Tests und Abnahme | 10 |
