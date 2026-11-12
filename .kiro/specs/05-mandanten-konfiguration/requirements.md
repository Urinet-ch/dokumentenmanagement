# Requirements Document — 05 Konfiguration je Mandant

## Introduction
Diese Spec macht die fachliche Ordnung des Archivs pro Mandant konfigurierbar: Dokumentarten,
Klassifikationen, Aufbewahrungsregeln und Regeln zu den Dokumenten. Ein Mandant wie `vhf-gsk` soll ganz andere
Dokumentkategorien, andere Aufbewahrungsfristen und andere Regeln haben können als `urinet`, ohne
Programmänderung und ohne Release (steering/product.md: «Ein neuer Mandant ist Konfiguration, kein neues Projekt»).

Spec 01 legt die Dokumentarten schon pro Mandant ab (Requirement 9, Tabelle im Schema des Mandanten). Diese Spec
ergänzt, was dort fest im Code, in den Steering-Dateien oder nur für `urinet` formuliert ist: Klassifikationen mit
eigenen Wertelisten, weitere Bezugspunkte der Aufbewahrungsfrist, deklarative Regeln je Dokumentart,
Belegnummer-Muster, «selbst ausgestellt» und die KI-Hinweise je Art und Mandant, eine versionierte
Konfigurationsdatei je Mandant und die Pflege in der App. Wo diese Spec Verhalten aus Spec 01 bis 04 erweitert,
steht das als «Spec 05 ergänzt …»; die Folgeänderungen in den anderen Specs listet design.md, Abschnitt
«Folgeänderungen».

Fest und für alle Mandanten gleich bleiben (Begründung in design.md, «Abgrenzung»): die Vertraulichkeitsstufen
`NORMAL`, `INTERN`, `PERSONAL` und die Rechtematrix, die Zusatzfeld-Gruppen `finanz`, `vertrag`, `frei` mit ihren
Spalten, die Bedeutung der Felder `zugriff` und `veroeffentlicht` der öffentlichen Schnittstelle (Spec 03), die
festen Eigenschaften der Art `WISSENSQUELLE` (Spec 04), die Sperrlogik nach Spec 01, Requirement 6, und die
Löschung am Fristende (später, Spec 01, 9.9).

Grundlage ist der Auftrag von Fabian vom 11.11.2026, nicht das Konzept vom 08.11.2026; die Requirements tragen
deshalb keine Konzept-ID («ohne Konzept-ID», wie Spec 04); wo ein Requirement eine Konzept-Anforderung erweitert,
steht der Bezug in der Klammer. Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag
`@req-<x.y>`, Ausnahmen mit Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»),
Problem Details und `[offen]`-Marker wie in Spec 01. Die Szenarien liegen unter `features/konfiguration/` mit dem
Tag `@spec-05`. «Spec 0n, x.y» bezeichnet ein Akzeptanzkriterium, «Spec 0n, Aufgabe x.y» eine Aufgabe.

Einordnung: Die Wellen 17 und 18 folgen nach Gate Phase 1 und spätestens vor Welle 10 (Spec 03), damit `vhf-gsk`
direkt mit seiner eigenen Konfiguration startet und keine Datenmigration der Felder aus Spec 03 nötig wird
(entschieden von Fabian am 11.11.2026).

## Glossary
- **Konfiguration** – die fachliche Ordnung eines Mandanten: Dokumentarten, Klassifikationen, Regeln, KI-Texte
  und die Einstellungen `geschaeftsjahr_ende` und `kennungen`.
- **Konfigurationsversion** – fortlaufende Nummer je Mandant; jede wirksame Änderung der Konfiguration erzeugt
  eine neue Version mit vollständigem Abbild und SHA-256.
- **Basisversion** – die Konfigurationsversion, auf der eine Konfigurationsdatei beruht (Feld `basisVersion`).
- **Konfigurationsdatei** – JSON-Datei mit der ganzen Konfiguration eines Mandanten nach dem Schema
  `konfiguration.schema.json`; der Seed eines Mandanten ist seine erste Konfigurationsdatei.
- **Klassifikation** – vom Mandanten definierte Einteilung mit einer festen, flachen Werteliste (z. B.
  «Fachgebiet» mit «Bad», «Eis», «Technik»); einer Dokumentart zugeordnet, einfach oder mehrfach wählbar.
- **Klassifikationswert** – ein Eintrag der Werteliste mit stabilem Code und Namen.
- **Fristbezug** – Datum, ab dem die Jahre einer Aufbewahrungsregel zählen («ab» in Spec 01, 9.3).
- **Dokumentregel** (kurz Regel) – deklarative Prüfung einer Dokumentart; ihr **Regeltyp** stammt aus einer festen
  Menge (Requirement 5); Schwere `FEHLER` oder `WARNUNG`.
- **Lückenbericht** – Liste fehlender Nummern bzw. Zeiträume nach den Regeln `FOLGE` und `ERWARTET`.
- **Selbst ausgestellt** – Attribut einer Dokumentart: Der Mandant ist Aussteller (für `urinet` heute
  `public.mandant.eigene_arten`, Spec 01 design.md).
- **Belegnummer-Muster** – reguläre Ausdrücke je Dokumentart, an denen das Archiv Belegnummern dieser Art erkennt
  (heute global in steering/structure.md).
- **Reservierte Felder** – die Zusatzfelder `zugriff` und `veroeffentlicht` (Spec 03), deren Definition fest ist.

---

### Requirement 1: Konfiguration je Mandant (ohne Konzept-ID, erweitert M1 und D6)
**User Story:** Als Betreiber möchte ich jedem Mandanten eine eigene fachliche Ordnung geben,
damit ein Verband andere Kategorien, Fristen und Regeln erhält als eine Firma, ohne dass ich Code ändere.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant eine eigene Konfiguration führen, bestehend aus den Dokumentarten (Spec 01,
   Requirement 9, erweitert nach Requirement 2), den Klassifikationen (Requirement 3), den Regeln
   (Requirement 5), den KI-Texten (Requirement 8.2) und den Einstellungen `geschaeftsjahr_ende` (Spec 01, 9.6) und
   `kennungen` (Spec 01, 7.5).
2. Das Archiv SOLL Arten, Klassifikationen, Regeln, KI-Texte und Konfigurationsversionen eines Mandanten nur im
   Schema dieses Mandanten speichern und lesen; `geschaeftsjahr_ende` und `kennungen` bleiben in `public.mandant`
   und SOLLEN nur über die Zeile des aktuellen Mandanten gelesen und geändert werden. Eine Änderung SOLL nie die
   Konfiguration, die Dokumente oder die KI-Vorschläge eines anderen Mandanten verändern.
3. WENN die Konfiguration eines Mandanten wirksam geändert wird (über die App, die API oder eine
   Konfigurationsdatei), DANN SOLL das Archiv in derselben Transaktion eine neue Konfigurationsversion mit dem
   vollständigen Abbild, SHA-256, Zeit, Benutzer bzw. Betreiber und Quelle (`APP`, `DATEI`, `SEED`, `KOMMANDO`,
   `MIGRATION`) anlegen und
   die Änderung mit Version, alten und neuen Werten der geänderten Elemente im Zugriffsprotokoll festhalten;
   scheitert einer der Schritte, SOLL keine Änderung wirksam sein (wie Spec 01, 10.8). Eine Änderung ohne
   Unterschied zum aktuellen Stand SOLL keine Version erzeugen.
4. WENN ein Dokument bestätigt, über einen Generator archiviert oder in seinen Metadaten geändert wird, DANN SOLL
   das Archiv die dabei angewandte Konfigurationsversion am Dokument und in der Dokumentversion (Spec 01, 10.1)
   festhalten, damit nachvollziehbar bleibt, welche Arten, Fristen und Regeln galten.
5. Das Archiv SOLL jede frühere Konfigurationsversion eines Mandanten unverändert lesbar halten; Versionen
   SOLLEN nur angefügt, nie geändert oder gelöscht werden, auch nicht mit den Rechten des Schema-Eigentümers.
   Nachweis: JUnit (Test gegen `UPDATE`, `DELETE` und `TRUNCATE`).
6. WENN ein neuer Mandant angelegt wird (Spec 01, 1.5; Spec 03, 1.1), DANN SOLL das Archiv seine Konfiguration
   aus `backend/src/main/resources/seed/<code>/konfiguration.json` und dem mandantenunabhängigen Seed-Teil
   (Spec 04, 1.1) als Version 1 mit Quelle `SEED` einspielen. Spec 05 ergänzt Spec 01, 1.5: Diese Datei ersetzt
   `seed/<code>/dokumentarten.json`.
7. WENN sich die Konfiguration ändert, während Dokumente in Verarbeitung oder im Eingangskorb liegen, DANN SOLL das
   Archiv diese Dokumente bei der Bestätigung nach der dann gültigen Version prüfen; ein schon erzeugter
   KI-Vorschlag SOLL bleiben, Werte, die es in der neuen Version nicht mehr gibt, SOLL der Eingangskorb als
   ungültig markieren.

### Requirement 2: Dokumentarten je Mandant (ohne Konzept-ID, erweitert D6)
**User Story:** Als Betreiber möchte ich bei jeder Dokumentart festlegen, ob der Mandant sie selbst ausstellt,
woran ihre Nummern zu erkennen sind und wie die KI mit ihr umgeht, damit diese Regeln nicht für alle Mandanten gleich
im Code stehen.

#### Acceptance Criteria
1. Spec 05 ergänzt Spec 01, 9.1: Jede Dokumentart SOLL zusätzlich die Attribute `selbst_ausgestellt` (ja/nein),
   `belegnummer_muster` (Liste regulärer Ausdrücke, optional mit der benannten Gruppe `nr`), `fremdbeleg_art`
   (optional: Art, auf die die KI einen Vorschlag dieser Art umstuft, wenn der Swiss QR Code einen fremden
   Zahlungsempfänger nennt; Spec 01 design.md, Abschnitt «KI (`ki`)», Nachbearbeitung), `ohne_zahlungsdaten_art` (optional: Art, auf die
   die KI umstuft, wenn kein Swiss QR Code, keine IBAN und keine Fälligkeit vorliegen; heute Regel E2),
   `ki_vorschlag` (ja/nein, Standard ja), `ki_felder` (Zusatzfelder, die die KI vorschlägt), `ki_hinweise` (Text für
   den Prompt), `reihenfolge` (Anzeige) und ihre Klassifikationen (Requirement 3.4) tragen.
2. Das Archiv SOLL die Prüfung «Belegnummer schon vergeben» (Spec 01, 8.9 und 11.3) für genau die Arten mit
   `selbst_ausgestellt = ja` anwenden und die Liste nicht mehr aus `public.mandant.eigene_arten` lesen.
3. Das Archiv SOLL die Belegnummer-Muster eines Mandanten nur aus seinen Dokumentarten lesen (Spec 01, 7.5, 7.9
   und 7.12); Spec 05 ergänzt steering/structure.md: Die dort genannten Muster sind die Werte des Mandanten
   `urinet`, wo eine Regel `FOLGE` sie braucht mit benannten Gruppen (gleiche Treffer, z. B.
   `^(?<jahr>\d{4})-(?<nr>\d{3})$`).
4. FALLS ein Belegnummer-Muster kein gültiger Ausdruck nach den Regeln für Zusatz-Schemas ist (JDK-Syntax,
   höchstens 1'000 Zeichen, Spec 01 design.md), DANN SOLL das Archiv die Änderung mit HTTP 422
   `KONFIGURATION_UNGUELTIG` ablehnen. Das Archiv SOLL jedes Muster auch zur Laufzeit (Vorauswahl der Beispiele,
   KI-Nachbearbeitung, Lückenbericht) nur mit der Zeitgrenze `archiv.dokumentart.muster-zeitgrenze` auswerten und
   ein Muster, das sie überschreitet, als «trifft nicht zu» behandeln und protokollieren.
5. Das Archiv SOLL Arten mit `ki_vorschlag = nein` nie in die Liste der Dokumentarten für den KI-Vorschlag
   aufnehmen (Spec 01, 7.1).
6. Spec 05 ergänzt Spec 04, 1.1 und 1.5: WO die Art `WISSENSQUELLE` besteht (ab Spec 04, Aufgabe 13.2), SOLL sie
   in jeder Konfiguration vorhanden sein und Code, Gruppe `frei`, mindestens die Pflichtfelder `titel` und `datum`,
   Fristregel «unbefristet», `gesetzliche_pflicht = nein` und `ki_vorschlag = nein` fest tragen (Entscheid Fabian
   09.11.2026); FALLS eine Änderung oder Konfigurationsdatei davon abweicht, DANN SOLL das Archiv sie mit HTTP 422
   `KONFIGURATION_UNGUELTIG` ablehnen. Zusatzfelder (auch als Pflicht, z. B. `zusatz.zugriff` nach Spec 04, 1.1) und
   Klassifikationen darf ein Mandant ergänzen.
7. FALLS eine Art die reservierten Felder `zugriff` oder `veroeffentlicht` anders definiert als Spec 03 design.md
   (Typ, Werte, Standard, Pflicht), sie in `ki_felder` nennt oder eine Klassifikation diese Codes trägt, DANN SOLL
   das Archiv die Konfiguration mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen.
8. FALLS `fremdbeleg_art` oder `ohne_zahlungsdaten_art` auf eine inaktive Art oder auf eine Art mit niedrigerer
   Standard-Vertraulichkeit (`NORMAL` < `INTERN` < `PERSONAL`) verweist, DANN SOLL das Archiv die Konfiguration
   mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen.
9. Ein Mandant SOLL beliebig viele eigene Arten mit eigenen Codes führen können; das Archiv SOLL keine Art-Codes
   eines Mandanten in Programmlogik voraussetzen, ausser `WISSENSQUELLE` (Spec 04). Nachweis: JUnit (ArchUnit-
   bzw. Quelltext-Test: keine Art-Codes von `urinet` in `backend/src/main/java`).

### Requirement 3: Klassifikationen (ohne Konzept-ID)
**User Story:** Als Geschäftsstelle eines Verbands möchte ich Dokumente nach unseren eigenen Einteilungen
(z. B. Fachgebiet) ordnen und filtern, damit Mitglieder Merkblätter so finden, wie wir sie denken.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant Klassifikationen mit Code, Name, Beschreibung, Werteliste (je Wert Code, Name,
   Beschreibung, Reihenfolge, aktiv), «mehrfach» (ja/nein), «KI darf vorschlagen» (ja/nein), «Suchfilter»
   (ja/nein) und «öffentlich filterbar» (ja/nein) führen. Die Werteliste ist flach.
2. FALLS ein Code einer Klassifikation oder eines Werts nicht dem Muster `^[a-z][a-z0-9_]{0,39}$` folgt, schon
   vergeben ist oder nach dem Anlegen geändert werden soll, DANN SOLL das Archiv die Änderung mit HTTP 422
   `KONFIGURATION_UNGUELTIG` ablehnen; Namen und Beschreibungen SOLLEN änderbar sein.
3. FALLS eine Klassifikation oder ein Wert noch an einem Dokument hängt, DANN SOLL das Archiv die Löschung mit
   HTTP 409 `KONFIGURATION_IN_GEBRAUCH` verweigern und nur «inaktiv» erlauben; ein inaktiver Wert bleibt an
   bestehenden Dokumenten sichtbar und ist für neue Zuordnungen nicht wählbar.
4. Das Archiv SOLL einer Dokumentart null oder mehr Klassifikationen zuordnen, je Zuordnung mit «Pflicht»
   (ja/nein) und optionalem Standardwert aus der aktiven Werteliste.
5. WENN ein Dokument bestätigt, über einen Generator archiviert oder in seinen Metadaten geändert wird, DANN SOLL
   das Archiv prüfen, dass nur Klassifikationen der Art gesetzt sind, neu gesetzte Werte aktiv sind (unverändert
   übernommene inaktive Werte bleiben erlaubt), eine einfache Klassifikation höchstens einen Wert hat und jede
   Pflicht-Klassifikation einen Wert hat; sonst HTTP 422 `KLASSIFIKATION_UNGUELTIG` mit den betroffenen
   Klassifikationen. KI-Vorschläge für Klassifikationen gelten erst mit der Bestätigung als Zuordnung.
6. WENN die Art eines Dokuments geändert wird, DANN SOLL das Archiv Werte von Klassifikationen, die der neuen Art
   nicht zugeordnet sind, entfernen und das in der Dokumentversion festhalten (Spec 01, 10.1); Standardwerte der
   neuen Art SOLLEN nur leere Klassifikationen vorbelegen.
7. Das Archiv SOLL jede Änderung der Klassifikationswerte eines archivierten Dokuments wie eine
   Metadatenänderung versionieren (Spec 01, 10.1, Grund Pflicht) und protokollieren.
8. Freie Tags (Spec 01, 7.2 und 14.1) SOLLEN unverändert neben den Klassifikationen bestehen bleiben.

### Requirement 4: Aufbewahrungsregeln je Mandant (ohne Konzept-ID, erweitert D6 und B1)
**User Story:** Als Betreiber möchte ich je Dokumentart festlegen, wie lange und ab welchem Datum aufbewahrt wird,
damit ein Verband z. B. Protokolle ab dem Sitzungsdatum oder Publikationen unbefristet aufbewahrt.

#### Acceptance Criteria
1. Spec 05 ergänzt Spec 01, 9.3: Als Fristbezug SOLL das Archiv neben `GESCHAEFTSJAHR` und `GUELTIG_BIS` auch
   `BELEGDATUM` (Belegdatum des Dokuments) und `FELD` (ein Datumsfeld der Zusatzfelder der Art) anbieten;
   `aufbewahren_bis` ist das Bezugsdatum plus die Jahre der Regel, bei `GESCHAEFTSJAHR` wie bisher das Ende des
   Geschäftsjahres plus die Jahre.
2. FALLS der Fristbezug `FELD` gewählt wird und das genannte Feld im Zusatz-Schema der Art fehlt, nicht vom Typ
   `string` mit `format: date` ist oder nicht zu den Pflichtfeldern der Art gehört, DANN SOLL das Archiv die
   Konfiguration mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen.
3. FALLS beim Fristbezug `BELEGDATUM` das Belegdatum fehlt, DANN SOLL das Archiv das Eingangsdatum verwenden
   (wie Spec 01, 9.3 für das Geschäftsjahr).
4. FALLS beim Fristbezug `FELD` das Feld eines Dokuments leer ist (Bestand vor der Regel, Artwechsel), DANN SOLL
   das Archiv das Dokument als unbefristet behandeln (wie Spec 01, 9.3 beim Vertrag ohne `gueltig_bis`, Sperre nach
   6.9) und den Fall im Eingangskorb bzw. in der Antwort als Warnung zeigen.
5. Spec 05 ergänzt Spec 01, 9.7: WENN das Bezugsdatum eines Dokuments geändert wird (Belegdatum bzw. das Feld nach
   4.1), DANN SOLL das Archiv `aufbewahren_bis` nach Spec 01, 9.7 neu berechnen, einschliesslich «Frist und Sperre
   belassen und den Konflikt protokollieren» bei Pflicht oder Sperre und «neues Ergebnis übernehmen» ohne beides.
6. Spec 01, 6.3, 6.9, 6.12, 9.7, 9.8 und 9.10 SOLLEN für alle Fristbezüge und alle Mandanten unverändert gelten:
   Sperre nur bei `gesetzliche_pflicht`, unbefristet ohne Datum, Neuberechnung nach den Entscheiden vom
   10.11.2026, «Frist abgelaufen» beim Altbestand, Regeländerungen erlaubt und protokolliert, Sperren nur
   verlängern.

### Requirement 5: Regeln zu den Dokumenten (ohne Konzept-ID)
**User Story:** Als Betreiber möchte ich je Mandant Regeln hinterlegen, etwa dass eine Ausgabe-Nummer je Reihe und
Jahr nur einmal vorkommt, damit Fehler beim Erfassen auffallen, ohne dass dafür programmiert wird.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant Regeln mit Code, Dokumentart (eine Art oder alle Arten), Regeltyp, Parametern,
   Schwere (`FEHLER` oder `WARNUNG`), Meldung und «aktiv» führen. Regeltypen sind genau:
   - `PFLICHT_WENN`: Ein Feld oder eine Klassifikation ist Pflicht, wenn ein anderes Feld oder eine
     Klassifikation einen genannten Wert hat;
   - `EINDEUTIG`: Die Kombination der genannten Felder kommt in der Art (bzw. allen Arten) höchstens einmal unter
     den bestätigten Dokumenten vor (Status `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `ARCHIVIERT`, auch über einen
     Generator archivierte), ohne das geprüfte Dokument selbst; leere Werte zählen nicht;
   - `FOLGE`: Ein ganzzahliges Feld oder die Gruppe `nr` eines Belegnummer-Musters bildet je Art und Zeitraum
     (`GESCHAEFTSJAHR`, ein ganzzahliges Jahresfeld der Art oder `GESAMT`) über die bestätigten Dokumente eine
     lückenlose Folge; der Bericht nach einem Import (Spec 03, 4.2) zählt zusätzlich die Dokumente dieses Imports im
     Eingangskorb;
   - `ERWARTET`: Je Zeitraum (`MONAT` oder `JAHR`) wird mindestens ein Dokument der Art erwartet, ab einem Datum
     oder ohne Datum ab dem ersten bestätigten Dokument der Art.
2. WENN ein Dokument bestätigt, über einen Generator archiviert oder in seinen Metadaten geändert wird, DANN SOLL
   das Archiv die aktiven Regeln der Typen `PFLICHT_WENN` und `EINDEUTIG` prüfen; eine verletzte Regel mit
   Schwere `FEHLER` SOLL es mit HTTP 422 `REGEL_VERLETZT` und den Codes und Meldungen der Regeln ablehnen, eine
   verletzte Regel mit Schwere `WARNUNG` SOLL den Vorgang nicht aufhalten und die Warnung in der Antwort und im
   Eingangskorb zeigen (wie Spec 01, 8.10). Meldungen SOLLEN keine Dokument-IDs oder Werte von Dokumenten nennen,
   die der Benutzer nicht sehen darf (wie Spec 01, 3.4).
3. WENN bei einer Metadatenänderung keines der Felder einer Regel oder Pflicht-Klassifikation geändert wird, DANN
   SOLL das Archiv eine schon vorher bestehende Verletzung nur als Warnung zeigen, damit Korrekturen am Bestand
   möglich bleiben.
4. Das Archiv SOLL Regeln der Typen `FOLGE` und `ERWARTET` nie beim einzelnen Dokument durchsetzen, sondern als
   Lückenbericht je Art und Zeitraum liefern, nur für Rollen, die alle Vertraulichkeitsstufen uneingeschränkt sehen
   (A3 und A4 «erlaubt»: `inhaber`, `treuhaender`, `geschaeftsstelle`), sonst HTTP 403; so meldet der Bericht keine
   Lücke, die nur durch ein unsichtbares Dokument entsteht.
5. FALLS sich eine Regel auf ein Feld bezieht, das kein Grundfeld (Spec 01 design.md, Dokumentart), kein Feld der
   Gruppen der Art, kein Zusatzfeld aus ihrem Zusatz-Schema und keine Klassifikation der Art ist, oder ein Feldname
   nicht dem Muster `^[a-z][a-z0-9_]{0,39}$` (mit Präfix `zusatz.` bzw. `klassifikation.`) folgt, DANN SOLL das
   Archiv die Konfiguration mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen.
6. WENN eine Regel neu angelegt, verschärft (strengere Schwere, zusätzliche Felder bei `PFLICHT_WENN`, weniger
   Felder bei `EINDEUTIG`) oder aktiviert wird, DANN SOLL das Archiv sie nur auf künftige Bestätigungen,
   Archivierungen und Änderungen anwenden, bereits archivierte Dokumente unverändert lassen und die Zahl der
   bestätigten Dokumente melden, die sie verletzen würden.
7. Regeln SOLLEN keine Programmlogik enthalten: keine Skripte und keine Ausdrücke ausser den Parametern ihres Typs.
   Nachweis: JUnit (Schema der Regelparameter ohne freie Ausdrücke).

### Requirement 6: Konfigurationsdatei (ohne Konzept-ID)
**User Story:** Als Betreiber möchte ich die Konfiguration eines Mandanten als Datei prüfen, vergleichen und
einspielen, damit ein neuer Verband in einem Schritt eingerichtet und jede Änderung im Repository nachvollziehbar
ist.

#### Acceptance Criteria
1. Das Archiv SOLL die Konfiguration eines Mandanten als Konfigurationsdatei exportieren und eine
   Konfigurationsdatei prüfen, mit dem aktuellen Stand vergleichen und einspielen können, über das
   Admin-Kommando (`archiv mandant konfiguration exportieren|pruefen|vergleichen|einspielen <code> [<datei>]`) und
   über die API (Recht A11).
2. WENN eine Konfigurationsdatei geprüft oder verglichen wird, DANN SOLL das Archiv nichts ändern und alle Befunde
   (Pfad in der Datei, Meldung) bzw. alle hinzugefügten, geänderten, deaktivierten und entfernten Elemente
   ausgeben, je mit der Zahl der betroffenen Dokumente.
3. WENN eine Konfigurationsdatei eingespielt wird, DANN SOLL das Archiv alle Prüfungen dieser Spec und von Spec 01,
   Requirement 9 anwenden und die Datei ganz oder gar nicht übernehmen; Elemente, die in der Datei fehlen, aber
   noch Dokumente haben, SOLL es deaktivieren statt löschen und das im Ergebnis nennen.
4. FALLS die Basisversion in der Datei nicht der aktuellen Konfigurationsversion des Mandanten entspricht, DANN
   SOLL das Archiv das Einspielen über die API mit HTTP 409 `KONFIGURATION_VERALTET` und über das Admin-Kommando
   mit einem Exit-Code ungleich 0 ablehnen, damit eine Änderung in der App nicht unbemerkt überschrieben wird.
5. WENN eine exportierte Datei unverändert wieder eingespielt wird, DANN SOLL das Archiv keine Änderung und keine
   neue Version erzeugen (Rundlauf); Kommentarfelder (`_…`) SOLL es beim Einspielen ignorieren und nicht
   exportieren.
6. FALLS eine Konfigurationsdatei ein Feld enthält, das `konfiguration.schema.json` nicht kennt (u. a. `mandant`,
   Dokument-IDs, Werte von Dokumenten, Tokens), oder ein Text die Grenzen nach design.md überschreitet, DANN SOLL das
   Archiv sie mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen; der Mandant ergibt sich beim Einspielen nur aus dem
   Aufruf (`<code>` bzw. Anmeldung), nie aus der Datei. FALLS eine eingespielte Datei `kiFreigegeben` anders setzt als
   der aktuelle Stand, DANN SOLL das Archiv sie ebenso ablehnen; die Freigabe setzt nur das Admin-Kommando (8.4).
7. Ein Test SOLL jede Datei `backend/src/main/resources/seed/*/konfiguration.json`, zusammengeführt mit dem
   Seed-Teil `_alle` (Spec 04, 1.1) so wie 1.6 sie einspielt, gegen `konfiguration.schema.json` und alle Prüfungen
   dieser Spec prüfen und sie Feld für Feld gegen die
   Stammdaten-Tabelle des Mandanten vergleichen (für `urinet` Spec 01 design.md, für `vhf-gsk` Spec 03 design.md,
   sobald sie dort steht). Spec 05 ergänzt Spec 01, 9.2: Der Test gilt für jeden Seed. Nachweis: JUnit
   (`KonfigurationSeedTest`).

### Requirement 7: Pflege in der App (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich Dokumentarten, Klassifikationen und Regeln in der App pflegen und
vergleichen, damit ich dafür keinen Betreiber brauche.

#### Acceptance Criteria
1. Spec 05 ergänzt Spec 01, 2.6 und 2.17: Das Recht A11 umfasst zusätzlich Klassifikationen, Regeln und KI-Texte
   (anlegen, ändern, deaktivieren, löschen), das Prüfen, Vergleichen, Exportieren und Einspielen einer
   Konfigurationsdatei und das Lesen früherer Konfigurationsversionen; Bezeichnung der Zeile und Rechte je Rolle
   bleiben wie in Spec 01 (für `geschaeftsstelle` nach dem Entscheid in Spec 03, 3.5). FALLS ein Benutzer ohne A11
   eine dieser Aktionen ausführt, DANN SOLL das Archiv mit HTTP 403 antworten.
2. Das Archiv SOLL allen Archiv-Rollen die Dokumentarten, Klassifikationen und Werte mit ihrem Status «aktiv»
   lesbar machen (Auswahllisten nur aktive, Anzeige auch inaktive); Regeln, KI-Texte, Versionen und Export nur mit
   A11.
3. Die App SOLL Seiten zum Pflegen der Dokumentarten (inkl. Fristregel, Pflichtfelder, Zusatzfelder,
   Klassifikationen und KI-Attribute der Art), der Klassifikationen mit Wertelisten und der Regeln anbieten, dazu
   die Liste der Konfigurationsversionen mit dem Vergleich zweier Versionen und den Import einer
   Konfigurationsdatei mit Vorschau nach 6.2 vor dem Einspielen.
4. Die App SOLL Zusatzfelder über einen Feld-Editor pflegen lassen (je Feld Code, Name, Typ Text, Ganzzahl,
   Dezimalzahl, Datum, Ja/Nein oder Auswahl mit Werteliste, Pflicht, Beschreibung) und daraus das Zusatz-Schema
   erzeugen; ein Schema, das der Editor nicht darstellen kann, SOLL sie als JSON zeigen und nur so ändern lassen.
5. Eingangskorb, Detailansicht und Generator-Prüfung SOLLEN die Felder und Klassifikationen der gewählten Art aus
   der Konfiguration darstellen bzw. prüfen; wechselt die Art im Eingangskorb, SOLL die App die Felder der neuen
   Art zeigen (erfüllt auch Spec 03, Aufgabe 10.4 «Eingangskorb zeigt Felder je Dokumentart»).
6. FALLS eine Änderung einzelner Elemente (`/dokumentarten`, `/klassifikationen`, `/regeln`, KI-Texte,
   `/mandant/einstellungen`) ohne
   `If-Match` mit der aktuellen Konfigurationsversion eintrifft, DANN SOLL das Archiv mit HTTP 428 `VERSION_FEHLT`
   antworten, bei abweichender Version mit HTTP 412 `VERSION_KONFLIKT` (wie Spec 01, 10.4 und 10.6). Beim
   Einspielen einer Datei gilt nur die Basisversion nach 6.4.

### Requirement 8: Wirkung in KI, Suche und Schnittstellen (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich, dass Vorschläge, Filter und Schnittstellen die Ordnung meines Mandanten
kennen, damit ich keine fremden Kategorien sehe.

#### Acceptance Criteria
1. Spec 05 ergänzt Spec 01, 7.1 und 7.2: Das Archiv SOLL Prompt und Antwortschema des KI-Vorschlags aus der
   Konfiguration des Mandanten bilden: Arten mit `ki_vorschlag = ja` und ihre Beschreibungen, `ki_hinweise` und die
   Markierung `selbst_ausgestellt`, die
   `ki_felder` der Arten mit Typ und Beschreibung, die Klassifikationen mit «KI darf vorschlagen» und ihre aktiven
   Werte; das Antwortschema SOLL nur diese Codes und Werte zulassen. Werte, die nicht in der Konfiguration stehen,
   SOLL das Archiv als «kein Vorschlag» für dieses Feld behandeln. `zugriff` und `veroeffentlicht` schlägt die KI
   nie vor (Spec 03, 3.3).
2. Das Archiv SOLL die festen Texte des Prompts (Rolle und Feldregeln, Spec 01 design.md, Abschnitt «KI (`ki`)») als
   KI-Texte des Mandanten führen und die Nachbearbeitung (Umstufungen, Begrenzung der Sicherheit der Belegnummer)
   nur über die Art-Attribute nach 2.1 und `kennungen` steuern.
3. Für den Mandanten `urinet` SOLLEN Prompt, Antwortschema und Nachbearbeitung nach dem Umstellen auf die
   Konfiguration Zeichen für Zeichen bzw. Fall für Fall gleich bleiben wie vorher, damit Gate 0 und der Golden-Lauf
   gültig bleiben (Spec 01, 0.3 und 0.9). Nachweis: JUnit (Vergleich mit gespeichertem Prompt, Schema und den
   Fällen der Nachbearbeitung).
4. SOLANGE die KI für einen Mandanten nicht freigegeben ist (`kiFreigegeben`, gesetzt nur über das Admin-Kommando
   mit Verweis auf die Abnahme nach 8.9), SOLL das Archiv für dessen Dokumente keinen KI-Vorschlag erzeugen und sie
   ohne Vorschlag in den Eingangskorb legen; `urinet` ist freigegeben (Gate 0).
5. Spec 05 ergänzt Spec 01, 14.1 und 14.2: Die Suche SOLL nach jedem Klassifikationswert mit «Suchfilter» filtern
   (ODER innerhalb, UND zwischen Klassifikationen), je Wert die Zahl der Treffer liefern, nur über Dokumente, die der
   Benutzer sehen darf (Spec 01, 14.5), und die Namen der Klassifikationswerte im Volltext finden.
6. FALLS der Code einer Klassifikation mit «öffentlich filterbar» einem festen Parameter der öffentlichen
   Schnittstelle von Spec 03 entspricht (Liste in design.md), DANN SOLL das Archiv die Konfiguration mit HTTP 422
   `KONFIGURATION_UNGUELTIG` ablehnen. Die Filter selbst ergänzt Spec 03, 5.1 und 7.2 (Folgeänderung): neben Reihe
   (= Code der Dokumentart) und Jahr nach Klassifikationen mit «öffentlich filterbar», und nur diese werden
   ausgegeben.
7. Spec 05 ergänzt Spec 01, Requirement 11 und 12 sowie Spec 03, 4.1: Generatoren und Importe SOLLEN
   Klassifikationswerte mit ihrem Code übergeben können; es gelten 3.5 und 5.2.
8. Das Archiv SOLL je Mandant die Angabe «Finanzfunktionen verfügbar» liefern (`GET /mandant/funktionen`), die
   genau dann gilt, wenn er mindestens eine aktive Art mit der Gruppe `finanz` hat. Danach richten sich (Folgeänderung
   Spec 02, 3.1 und Requirement 7 sowie Spec 03, 1.3) die Werkzeuge `kennzahl` und `offene_posten` und der
   Jahresexport: ohne Finanzfunktionen bietet das Archiv die Werkzeuge dem Agenten nicht an und beantwortet den
   Export für Rollen mit Exportrecht mit HTTP 404, für die übrigen Rollen bleibt es bei HTTP 403 nach Spec 02, 7.5.
9. Ein Mandant ohne eigenen Golden-Datensatz (z. B. `vhf-gsk`) SOLL die KI-Freigabe nach 8.4 erst nach einer
   Abnahme erhalten: [offen: Verfahren der Abnahme ohne Golden-Datensatz – Entscheid vor Welle 10].
   Nachweis: manuell [Fabian].

### Requirement 9: Bestand und Abnahme (ohne Konzept-ID, nicht funktional)
**User Story:** Als Inhaber von Urinet möchte ich, dass sich mit dieser Spec an meinem Archiv nichts ändert,
damit Abnahme und Belege gültig bleiben.

#### Acceptance Criteria
1. WENN die Migration dieser Spec läuft, DANN SOLL das Archiv für jeden bestehenden Mandanten
   `selbst_ausgestellt` aus `public.mandant.eigene_arten` übernehmen (ist die Spalte leer, für `urinet` die Arten
   `AUSGANGSRECHNUNG`, `OFFERTE` und `LOHNABRECHNUNG` nach Spec 01 design.md, Abschnitt «Mandant (`mandant`)»,
   Mandanten-Einstellungen, für andere Mandanten keine), für `urinet` die Belegnummer-Muster nach 2.3,
   `fremdbeleg_art`, `ohne_zahlungsdaten_art`, die KI-Texte und die Regeln aus `seed/urinet/konfiguration.json` setzen
   und die Konfigurationsversionen mit Quelle `MIGRATION` anlegen; Dokumente, Fristen und Sperren SOLLEN unverändert
   bleiben. Nachweis: JUnit (Migrationstest mit Schema im Stand vor Spec 05).
2. Nach der Migration SOLL der Export der Konfiguration von `urinet` dieselben Arten, Fristen und Pflichtfelder
   liefern wie vorher und mit `seed/urinet/konfiguration.json` samt Seed-Teil `_alle` übereinstimmen. Nachweis: JUnit (Migrationstest und
   `KonfigurationSeedTest`).
3. Ein automatisierter Test SOLL zeigen, dass Arten, Klassifikationen, Regeln, KI-Texte und
   Konfigurationsversionen eines Mandanten über keinen Weg (UI, API, Admin-Kommando, Konfigurationsdatei, KI,
   Suche, Lückenbericht) in einem anderen Mandanten sichtbar oder wirksam sind (Test `MandantenIsolationIT` nach
   Spec 03 design.md; liegt er noch nicht vor, legt Aufgabe 18.5 ihn an, Spec 03, Aufgabe 12.3 erweitert ihn).
   Nachweis: JUnit (`MandantenIsolationIT`).
4. Ein Beispiel-Mandant in den Tests SOLL mit einer Konfigurationsdatei ohne eine einzige Art, Klassifikation oder
   Regel von `urinet` vollständig funktionieren: erfassen, bestätigen mit Klassifikation und Regelprüfung,
   archivieren mit Fristbezug `FELD`, suchen mit Filter, Lückenbericht.

---

## Rückverfolgung Konzept → Spec

| Konzept | Requirement |
| --- | --- |
| M1 (Mandantenfähigkeit) | 1 |
| D6 (Dokumentarten, Aufbewahrung) | 1, 2, 4 |
| B1 (Unveränderbarkeit, Sperre) | 4 |
| ohne Konzept-ID (Auftrag Fabian 11.11.2026) | 3, 5, 6, 7, 8, 9 |
