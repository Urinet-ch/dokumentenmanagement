# Spike 0.4: Test-Export aus 21.CRM (K4)

**Stand:** 08.11.2026. Grundlage ist ein Gesamtexport von Fabian («ExportAllData», 22 Tabellen als CSV, 18 Kontakte).
**Aufgabe:** 0.4 (Req 0.6, 13.1). Der echte Export liegt nur lokal bei Fabian und kommt nie ins Repository.

Beispieldateien (erfundene Werte, Kopfzeile 1:1 aus dem Export, erzeugt mit `tools/crm/beispiel_erzeugen.py`):
- `backend/src/test/resources/csv/21crm-kontakte-beispiel.csv`: 5 Kontakte (3 Organisationen, 2 Personen)
- `backend/src/test/resources/csv/21crm-verkaufschancen-beispiel.csv`: nur die Kopfzeile, weil der Export keine Verkaufschancen enthält

## Dateiformat

| Merkmal | Befund | Folge für den Import (Req 13.1) |
|---|---|---|
| Zeichensatz | Windows-1252: kein BOM, kein gültiges UTF-8, Umlaute und `ß` als Einzelbyte (z. B. `Straße (Geschäft)`) | Erkennung «UTF-8 gültig? sonst Windows-1252» genügt |
| Trennzeichen | Semikolon | – |
| Anführungszeichen | keine, auch nicht bei Feldern mit Leerzeichen | Ein `;` oder Zeilenumbruch in einem Feld verschiebt die Spalten; solche Zeilen gelten als «fehlerhaft» (Spaltenzahl ≠ Kopfzeile). Im Test-Export kommt das bei Kontakten nicht vor |
| Zeilenende | LF; die Datei endet mit LF | CRLF trotzdem annehmen (andere Tabellen des Exports mischen CRLF und LF) |
| Kopfzeile | deutsche Klartextnamen, 151 Spalten in `ADDRESS.csv`; die Spalte `Sekretär(in)` kommt **zweimal** vor | Zuordnung über die Spaltenposition speichern, nicht nur über den Namen |
| Wahrheitswerte | `true` / `false` | – |
| Zeitstempel | `TT.MM.JJ HH:MM:SS`, zweistelliges Jahr, ohne Zeitzone (z. B. `03.02.26 09:15:00`) | als Europe/Zurich lesen |
| Zahlen | Punkt als Dezimaltrenner (`Geografische Breite`) | – |
| IDs | `GGUID`: 32 Hex-Zeichen in Grossbuchstaben, ohne Klammern | `crm_id` = `GGUID` |

Die übrigen Tabellen des Gesamtexports (`ACTION`, `FILTER`, `VIEWDEFINITION` u. a.) sind Konfiguration von 21.CRM und für das Archiv ohne Bedeutung. `links.csv` (Verknüpfungen) ist leer.

## Kontakte (`ADDRESS.csv`)

- **Organisation oder Person** steht in `Kontakt ist eine Organisation` (`true`/`false`). Bei Organisationen ist `Name` **leer**; der Firmenname steht in `Organisation`. Bei Personen stehen `Vorname` und `Name`; `Organisation` enthält höchstens den Firmennamen als Text, es gibt keine ID-Verknüpfung zur Organisation.
- **Adresse:** genutzt werden nur die Felder «(Geschäft)»; Privat-, Besuchs- und Rechnungsadresse sind im Export leer. Personen haben oft keine PLZ (4 von 8).
- **E-Mail:** vier Felder; `Standard-E-Mail` ist ein Index: `1` = `E-Mail (Geschäft)`, `3` = `E-Mail (Privat)`, `5` = `E-Mail (Zentrale)`.
- **Telefon:** `Standard-Telefon 1` ist ein Index: `4` = `Telefon (Geschäft)`, `10` = `Telefon (Zentrale)`; dazu `PHONEID<n>` mit der Nummer nur aus Ziffern (`0041…`). Format der Anzeige: `+41 41 000 01 10`.
- **Gelöschte und deaktivierte Kontakte** sind über `Ist gelöscht` und `Kontakt deaktiviert` markiert (im Test-Export alle `false`).
- Dazu viele technische Spalten (`OWNERGUID`, `Zugriffsberechtigte`, `GEOTILEID`, `EBID-Status` …), die der Import ignoriert.

### Zuordnung auf `kontakt` (Vorschlag für design.md)

| Feld | Spalte(n) |
|---|---|
| `crm_id` | `GGUID` |
| `typ` | `Kontakt ist eine Organisation`: `true` → Organisation, sonst Person |
| `name` | Organisation: `Organisation`; Person: `Name` |
| `vorname` | `Vorname` (nur Personen) |
| `strasse`, `plz`, `ort` | `Straße (Geschäft)`, `PLZ (Geschäft)`, `Ort (Geschäft)` |
| `email` | Feld gemäss `Standard-E-Mail`, sonst das erste gefüllte aus Geschäft, Zentrale, Geschäft 2, Privat |
| `telefon` | Feld gemäss `Standard-Telefon 1`, sonst `Telefon (Geschäft)`, `Telefon (Zentrale)`, `Mobil (Geschäft)` |
| `erstellt`, `geaendert` | `Erzeugt am`, `Geändert am` |
| (übersprungen) | Zeilen mit `Ist gelöscht = true` |

Eine Zuordnung «Spalte → Feld» allein reicht dafür nicht: `name` hängt vom Typ ab, `email` und `telefon` von einem Index. Vorschlag: Das Archiv erkennt die Kopfzeile des 21.CRM-Gesamtexports und nutzt dafür ein fest eingebautes Profil «21.CRM» mit diesen Regeln; andere CSV-Dateien laufen über den Zuordnungsdialog (Req 13.2).

**Anforderungsänderung (Vorschlag):** Req 13.10 nennt die Pflichtspalte «Name». Im 21.CRM-Export ist `Name` bei allen Organisationen leer. Neu: Pflicht ist eine Spalte für das Feld `name`, beim Profil «21.CRM» `Name` **oder** `Organisation`; eine Zeile ohne beides ist «fehlerhaft».

## Verkaufschancen (`GWOPPORTUNITY.csv`)

Der Export enthält keine Verkaufschancen, nur die Kopfzeile (32 Spalten). Für Req 13.9 naheliegend (abgeleitet, ohne Daten nicht geprüft):

| Feld | Spalte |
|---|---|
| `crm_id` | `GGUID` |
| Kontakt | `ACCOUNTGUID` (vermutlich die `GGUID` des Kontakts); `Kunde` als Text |
| `titel` | `Name` |
| `phase` | `Phase` (dazu `Status`, `Wahrscheinlichkeit`) |
| `betrag` | `Gesamt` mit `Währung` |
| `datum` | `Beginn` (oder `Ende`; Format vermutlich wie die Zeitstempel) |

Die leere Datei eignet sich als Testfall für Req 13.11 (`CSV_LEER`, «keine Datenzeilen»).

## Offene Punkte

- **Verkaufschancen prüfen:** Fabian legt in 21.CRM zwei Test-Verkaufschancen an (eine mit Betrag, eine ohne) und exportiert erneut. Danach Zuordnung, Datumsformat und `ACCOUNTGUID` bestätigen und die Beispieldatei mit erfundenen Zeilen füllen.
- **Exportweg:** Neben dem Gesamtexport bietet 21.CRM vermutlich einen Listen-Export aus der Kontaktansicht. Ob dieser andere Spaltennamen oder ein anderes Format liefert, ist ungeprüft; der Import soll den Gesamtexport als Standard nehmen.
- **Felder mit `;`:** Kommen in Notizen oder Adresszusätzen Semikolons vor, bricht die Zeile. Prüfen, ob 21.CRM solche Felder beim Export maskiert (im Test-Export kein Fall).
