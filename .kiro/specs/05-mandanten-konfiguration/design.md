# Design Document — 05 Konfiguration je Mandant

## Overview
Kein neuer Dienst. Der Archiv-Dienst erhält das Modul `konfiguration/` (hexagonal wie die anderen Module,
ADR-011) und erweitert `dokumentart/`. Die Konfiguration liegt wie die Dokumentarten heute im Schema des Mandanten
(`m_<code>`, ADR-002); nur `geschaeftsjahr_ende` und `kennungen` bleiben in `public.mandant` und werden nur über die
Zeile des aktuellen Mandanten gelesen (Port `MandantEinstellungen`). Gelesen oder geschrieben wird nur der Mandant,
für den eine Anfrage, ein Auftrag oder ein Admin-Kommando läuft (`MandantKontext`, `MandantTenantResolver`).

Cache: API und Worker halten je Prozess einen Cache, geschlüsselt nach `(mandant, konfigurationsversion)`. Jede
Transaktion, die Konfiguration braucht, liest zuerst die aktuelle Versionsnummer des Mandanten (eine Zeile) und
nimmt nur einen Eintrag mit genau dieser Nummer; so sehen API und Worker ohne Benachrichtigung denselben Stand, und
ein Eintrag eines Mandanten kann nie für einen anderen gelten.

Leitgedanke: Was je Mandant fachlich verschieden ist (Kategorien, Felder, Wertelisten, Fristen, Prüfregeln,
Nummernmuster, KI-Texte), ist Konfiguration. Was Rechte, Sichtbarkeit, die öffentliche Schnittstelle, bestehende
Sperren oder die Datenbankstruktur trägt, bleibt Code und gilt für alle Mandanten gleich. Für künftige Dokumente
bleiben Standard-Vertraulichkeit, Frist und `gesetzliche_pflicht` je Art änderbar (Spec 01, 9.10, protokolliert);
keine Konfiguration kann die Rechtematrix, den öffentlichen Filter oder eine bestehende Sperre aufweichen. So bleibt
`db/mandant` ein Changelog für alle Schemas (`MandantMigration`).

## Abgrenzung (bleibt global, mit Begründung)
| Bleibt fest | Warum |
| --- | --- |
| Vertraulichkeit `NORMAL`, `INTERN`, `PERSONAL` und Rechtematrix A1–A19 | Trägt Sichtbarkeit und Rechte (ADR-009) bis in Summen, Chat und Wissensbasis; eine Stufe je Mandant hiesse Rechte je Mandant konfigurieren. Je Art bleibt die Standard-Vertraulichkeit konfigurierbar. |
| Gruppen `finanz`, `vertrag`, `frei` und ihre Spalten | Echte Spalten (`dokument_finanz`, `dokument_vertrag`) gehören in den gemeinsamen Changelog; Mandantenfelder sind Zusatzfelder (`zusatz`, JSON Schema). |
| Reservierte Felder `zugriff` und `veroeffentlicht` (Spec 03, 3.3–3.4, 5.3) | Sie entscheiden, was öffentlich wird. Ihre Definition steht fest in Spec 03 design.md (Tabelle «Reservierte Felder»; seit 13.11.2026 drei Stufen `oeffentlich`, `mitglieder`, `vorstand`, Spec 03, 3.7); `Konfiguration.pruefen` lehnt jede Abweichung ab (Req 2.7), und der Filter der öffentlichen Schnittstelle bleibt Code. |
| Feste Eigenschaften von `WISSENSQUELLE` | Entscheid 09.11.2026 (Spec 04, 1.1 und 1.5); Req 2.6. |
| Sperrlogik, Modus, Stufen, Manifest (Spec 01, 6 und 15) | GeBüV und ADR-004/007; je Art konfigurierbar bleibt nur `gesetzliche_pflicht` und die Frist. |
| Löschung am Fristende | Spec 01, 9.9: eigene, spätere Spec. |
| KI-Modell, Schwellen `archiv.ki.*`, Aufbau des Prompts | Abnahme über Gate 0; konfigurierbar sind nur die Texte und Listen nach Req 8.1/8.2, ihre Stelle im Prompt ist fest. |
| Seitentypen der Wissensbasis | Entscheid 09.11.2026 (Spec 04, 2.3); diese Spec ändert ihn nicht. |

## Components and Interfaces

### Modul `konfiguration/`
- `domain/Konfiguration` – unveränderliches Abbild: `arten`, `klassifikationen`, `regeln`, `kiTexte`,
  `einstellungen`, `version`. `Konfiguration.pruefen()` liefert alle Befunde (Pfad, Code, Meldung), nicht nur den
  ersten; dieselbe Prüfung gilt für die App, die API, die Datei und den Seed.
- `domain/Klassifikation`, `KlassifikationWert`, `ArtKlassifikation(klassifikation, pflicht, standardWert)`.
- `domain/Regel` – versiegelte Typen `PflichtWenn`, `Eindeutig`, `Folge`, `Erwartet`; je Typ ein Record mit seinen
  Parametern (keine Ausdruckssprache, Req 5.7).
- `domain/KonfigurationsVergleich` – Diff zweier Abbilder: hinzugefügt, geändert (alt/neu je Attribut),
  deaktiviert, entfernt; je Element die Zahl betroffener Dokumente über `DokumentZaehlerPort`.
- `application/KonfigurationVerwaltung` – `aktuelle()`, `version(n)`, `versionen()`, `vergleichen(datei)`,
  `pruefen(datei)`, `einspielen(datei, quelle, wer, grund)`, `exportieren()`. Einspielen: Prüfen → Vergleichen →
  (kein Unterschied: Ende ohne Version, Req 1.3, 6.5) → Anwenden in **einer** Transaktion → Version anfügen →
  Protokoll; ein Fehler in einem Schritt rollt alles zurück (Req 1.3, 6.3).
- `application/RegelPruefung` – `pruefe(dokument, alt, art, konfiguration)` → `List<Regelbefund>` (Code, Schwere,
  Meldung); aufgerufen von Bestätigen (Spec 01, Aufgabe 3.4), Metadaten ändern (Aufgabe 3.5), Generator
  (Aufgabe 4.7) und Drive-Import (Aufgabe 4.8). Mit `alt` erkennt sie, ob eine Änderung Felder einer Regel berührt
  (Req 5.3). `EINDEUTIG` fragt über einen Port mit vorbereiteter Abfrage (Feldnamen nur aus der geprüften
  Konfiguration, Werte als Parameter) nach bestätigten Dokumenten mit denselben Werten; die Meldung nennt nie eine
  Dokument-ID (wie Spec 01, Requirement 3.4).
- `application/Lueckenbericht` – `FOLGE` und `ERWARTET` je Art und Zeitraum; liefert fehlende Nummern bzw.
  Zeiträume ohne Dokument. Recht nach Req 5.4. Nutzer: Spec 02 `vollstaendigkeit`, Spec 03 Altbestandsbericht (4.2).
- `application/Funktionen` – `finanzVerfuegbar()` nach Req 8.8; Spec 02 und der Jahresexport fragen hier.
- `adapter/in/rest/KonfigurationResource`, `KlassifikationResource`, `RegelResource`, `KiTexteResource`.
- `adapter/in/kommando/KonfigurationKommando` – Admin-Kommando über `ArchivMain` wie `mandant anlegen` (ohne
  HTTP-Server); im Betrieb auf demselben Weg ausgelöst wie `archiv mandant anlegen` (Spec 03, 1.1).
- `adapter/out/persistence/…` – Panache-Repositories im Mandanten-Schema (`@Table` ohne Schema, wie
  `DokumentartEintrag`).

### Erweiterungen bestehender Module
- `dokumentart/domain/Dokumentart` erhält die Attribute nach Req 2.1. `AufbewahrungAb` erhält `BELEGDATUM` und
  `FELD`; `Aufbewahrungsregel` erhält `feld` (nur bei `FELD`). `Aufbewahrung.berechne` bekommt das Bezugsdatum über
  eine kleine Schnittstelle `Fristbezug.datum(dokument)` statt fester Parameter je Fall; Req 4.4 (leeres Feld) ergibt
  «unbefristet».
- `mandant/` – `MandantProvisionierung` liest `seed/<code>/konfiguration.json` über den Port `KonfigurationSeed`
  (heute `DokumentartSeed`) und spielt sie zusammen mit dem Seed-Teil `_alle` (Spec 04) als Version 1 mit Quelle
  `SEED` ein; `SeedPruefung` ruft `Konfiguration.pruefen()` auf. Port `MandantEinstellungen` liefert
  `geschaeftsjahr_ende` und `kennungen` des aktuellen Mandanten (heute fehlt er, siehe Spec 01 `Aufbewahrung`).
- `ki/` (Spec 01, Aufgabe 3.2) – `PromptBauer` setzt den festen Aufbau aus Spec 01 design.md zusammen und füllt ihn
  aus der Konfiguration: Rolle und Feldregeln aus den KI-Texten, Liste der Arten (`ki_vorschlag = ja`) mit
  Beschreibung, Standard-Vertraulichkeit, `ki_hinweise` und der Markierung der Arten mit `selbst_ausgestellt`, die
  `ki_felder` und die Klassifikationen nach Req 8.1.
  `AntwortSchemaBauer` bildet je Mandant das JSON-Schema (Enum der Art-Codes, Werte der Klassifikationen, Typen der
  `ki_felder`). Die Nachbearbeitung bildet die heutigen Regeln auf Art-Attribute ab:

  | Heute (Spec 01 design.md, Abschnitt «KI (`ki`)», Nachbearbeitung und Erkenntnisse aus dem Golden-Lauf) | Künftig |
  | --- | --- |
  | `eigene_arten`, «Mandant ist Aussteller» | `selbst_ausgestellt` und `kennungen` |
  | Belegnummer-Muster aus structure.md, Regel A1 (Sicherheit ≤ 0.7) | `belegnummer_muster`, `selbst_ausgestellt`, `kennungen` |
  | Fremder QR-Zahlungsempfänger: `AUSGANGSRECHNUNG`/`OFFERTE` → `LIEFERANTENBELEG`, `LOHNABRECHNUNG` nie | `fremdbeleg_art` (bei `LOHNABRECHNUNG` leer) |
  | Regel E2: `LIEFERANTENBELEG` → `QUITTUNG` ohne Zahlungsdaten | `ohne_zahlungsdaten_art` bei `LIEFERANTENBELEG` |
  | Feldregeln im Prompt (Sozialversicherungen, Policen, Mahnungen, Perioden …) | KI-Text `feldregeln` des Mandanten |

  Der Snapshot-Test (Req 8.3) vergleicht Prompt, Schema und eine Liste von Nachbearbeitungsfällen vor und nach dem
  Umstellen. Grenzen gegen zu lange Prompts: KI-Texte je höchstens 8'000 Zeichen, `ki_hinweise` je Art 1'000,
  Summe aller Texte und Beschreibungen im Prompt höchstens 24'000 Zeichen (Prüfung in `Konfiguration.pruefen`).
  Ohne Abnahme nach Req 8.4 (Schalter `ki_freigegeben` in den KI-Texten, setzt nur der Betreiber mit Verweis auf die
  Abnahme) erzeugt der Worker für den Mandanten keinen Auftrag `KI_VORSCHLAG`; `urinet` startet mit `true`.
  Abnahme eines Mandanten ohne Golden-Datensatz (Req 8.9, Entscheid Fabian 17.11.2026): Stichprobe mit der
  Geschäftsstelle, 20 bis 30 echte, repräsentative Dokumente. Die Auswertung liest die Zähler
  `archiv_ki_feld_bestaetigt_total` und `archiv_ki_feld_korrigiert_total` je Feld und Mandant (Spec 01, design.md
  «Überwachung»); je Feld gilt: bestätigt plus korrigiert mindestens 20 und korrigiert / (bestätigt plus korrigiert)
  höchstens 10 %. Weil `ki_freigegeben` nur je Mandant besteht (Tabelle `ki_texte`), müssen alle Felder die Schwelle
  erreichen; eine Freigabe je Feld wäre eine Erweiterung (Schalter je Feld, nicht entschieden). Hinweis: Die Gauge
  `archiv_ki_feld_korrekturquote` teilt korrigiert durch bestätigt (Verhältnis, Stand seit Prozessstart) und ist für
  die Schwelle nicht massgeblich; massgeblich ist der Anteil an den bewerteten Feldern über den Zeitraum der Stichprobe.
- `dokument/` – `dokument_version` (Spec 01, Aufgabe 3.5) enthält zusätzlich `klassifikationen: {<code>: [<wert>…]}`
  und `konfigurationVersion`; `PATCH /dokumente/{id}` versioniert Änderungen der Klassifikationen (Req 3.7).
- `suche/` – Filter `klassifikation.<code>=<wert>` (Req 8.5) und Facetten je Wert über `dokument_klassifikation`, im
  selben Sichtbarkeitsfilter wie Spec 01, 14.5; die Namen der zugeordneten Werte gehen mit Gewicht C in den
  `tsvector` (Neuberechnung bei Zuordnung und bei Namensänderung des Werts). Klassifikations-Codes kommen nur aus der
  geprüften Konfiguration, nie als SQL-Bezeichner aus der Anfrage.
- `integration/webseite/` (bisher `integration/wordpress/`; Spec 03, Folgeänderung; umgesetzt in Spec 03, Aufgaben
  11.2 und 11.5) – Parameter der öffentlichen Liste: zusätzlich die Codes der Klassifikationen
  mit `oeffentlich_filter`, z. B. `?fachgebiet=bad`; der feste Sicherheitsfilter aus Spec 03 design.md bleibt
  unverändert davor. Feste Parameter, die kein Klassifikations-Code sein dürfen (Req 8.6): `q`, `reihe`, `jahr`,
  `ausgabe`, `seite`, `pro_seite`, `id`, `sortierung`, `gespraech_id`, `zugriff`, `veroeffentlicht`, `seit`, `max`,
  `referenz`, `groesse`, `status`, `url`. Ausgegeben werden nur Klassifikationen mit `oeffentlich_filter`.
  Spec 03 ergänzt (Spec 03, Req 5.10, 10.3, 10.13, 11.1, 14.3; 13.11.2026): Das Paket heisst `integration/webseite/`,
  weil die Schnittstelle für jede Webseite gilt; die letzten sechs festen Parameter der Liste oben kommen hinzu, weil
  Änderungs-Feed, Umleitung und Erfassung sie im selben Vertrag nutzen.

## REST-Schnittstelle (Ergänzung zu Spec 01 design.md)
| Methode | Pfad | Zweck | Recht |
| --- | --- | --- | --- |
| GET | `/klassifikationen` | aktive und inaktive Klassifikationen und Werte mit `aktiv` (Req 7.2) | alle Rollen |
| POST, PATCH, DELETE | `/klassifikationen[/{code}]`, `/klassifikationen/{code}/werte[/{wert}]` | anlegen, ändern, deaktivieren, löschen; 409 `KONFIGURATION_IN_GEBRAUCH`, 422 `KONFIGURATION_UNGUELTIG`, 412, 428 | A11 |
| GET, POST, PATCH, DELETE | `/dokumentarten/{art}/klassifikationen[/{code}]` | Klassifikationen einer Art zuordnen mit `pflicht` und `standardWert` (Req 3.4); GET alle Rollen, Schreiben A11 (`inhaber`); `If-Match` Pflicht; 404 `NICHT_GEFUNDEN`, 422 `KONFIGURATION_UNGUELTIG` (u. a. inaktive Klassifikation als Pflicht oder mit Standardwert), 412, 428 | alle Rollen (GET), A11 |
| GET, POST, PATCH, DELETE | `/regeln[/{code}]` | Regeln pflegen; POST/PATCH melden `betroffeneDokumente` (Req 5.6); 412, 428 | A11 |
| GET, PATCH | `/konfiguration/ki-texte` | Rolle, Feldregeln, `ki_freigegeben` (nur lesen; setzt das Admin-Kommando) | A11 |
| GET | `/konfiguration` | Export der aktuellen Konfiguration als Konfigurationsdatei; `ETag` = Version | A11 |
| GET | `/konfiguration/versionen`, `/konfiguration/versionen/{n}` | Liste (Version, Zeit, Wer, Quelle, SHA-256) bzw. Abbild | A11 |
| POST | `/konfiguration/pruefen`, `/konfiguration/vergleichen` | Datei im Body; Befunde bzw. Diff; ändert nichts | A11 |
| PUT | `/konfiguration` | Datei einspielen; 200 mit neuer Version und Diff (bzw. ohne Version bei keinem Unterschied); 409 `KONFIGURATION_VERALTET`, 422 | A11 |
| GET | `/berichte/luecken?art=&jahr=` | Lückenbericht nach `FOLGE`/`ERWARTET` | `inhaber`, `treuhaender`, `geschaeftsstelle` (Req 5.4) |
| GET | `/mandant/funktionen` | `{finanz: bool}` (Req 8.8) | alle Rollen |

Alle Änderungen einzelner Elemente verlangen `If-Match` mit der Konfigurationsversion (Req 7.6); jede Antwort trägt
die neue Version als `ETag`. Bestehende Endpunkte: `POST/PATCH/DELETE /dokumentarten` nehmen die neuen Attribute an
und verlangen ab dieser Spec ebenfalls `If-Match` (Folgeänderung Spec 01); `PATCH /dokumente/{id}`,
`POST /dokumente/{id}/bestaetigen` und der Generator nehmen `klassifikationen: {<code>: [<wert>…]}` an und können
zusätzlich 422 `KLASSIFIKATION_UNGUELTIG` und 422 `REGEL_VERLETZT` liefern; Warnungen erscheinen in `warnungen[]` wie
`BELEGNUMMER_BEKANNT`. `GET/PATCH /mandant/einstellungen` verliert `eigene_arten` (Req 2.2).

## Admin-Kommando
```
archiv mandant konfiguration exportieren <code> [<datei>]
archiv mandant konfiguration pruefen     <code> <datei>
archiv mandant konfiguration vergleichen <code> <datei>
archiv mandant konfiguration einspielen  <code> <datei> --wer <name> --grund <text>
archiv mandant konfiguration ki-freigeben <code> --wer <name> --abnahme <verweis>
```
Nur für den Betreiber (Weg wie `archiv mandant anlegen`, kein HTTP). Exit-Code 0 nur bei Erfolg; `pruefen` und
`vergleichen` ändern nie etwas. Ausgabe als Text, mit `--json` maschinenlesbar. Der Mandant kommt nur aus `<code>`.
Version und Protokoll tragen `wer = 'betreiber:<name>'` und den Grund.

## Konfigurationsdatei
Schema `backend/src/main/resources/konfiguration/konfiguration.schema.json` (JSON Schema 2020-12). Felder mit `_`
am Anfang sind Kommentare; das Archiv ignoriert sie beim Einspielen und exportiert sie nicht (Req 6.5). Unbekannte
Felder sind ungültig. Beim Export setzt das Archiv `basisVersion` auf die aktuelle Version; der Seed hat keine.
`kiFreigegeben` wird exportiert, beim Einspielen aber nur geprüft: Weicht der Wert vom aktuellen ab, lehnt das Archiv
die Datei ab (Req 6.6); nur der Seed (Version 1) und `ki-freigeben` setzen ihn. Der Seed eines Mandanten enthält
`WISSENSQUELLE` nicht; sie kommt aus dem Seed-Teil `_alle`, und Prüfung und Vergleich laufen über beide zusammen
(Req 6.7, 9.2). Der Export enthält `WISSENSQUELLE` mit den Ergänzungen des Mandanten.

Beispiel (nur das Format; die Werte für `vhf-gsk` legt Spec 03 fest):
```json
{
  "format": 1,
  "basisVersion": 7,
  "einstellungen": { "geschaeftsjahrEnde": "12-31", "kennungen": { "uid": null, "ibans": [] } },
  "kiTexte": { "rolle": "Archivassistent für einen Schweizer Verband", "feldregeln": "", "kiFreigegeben": false },
  "klassifikationen": [
    { "code": "fachgebiet", "name": "Fachgebiet", "mehrfach": true, "kiVorschlag": true,
      "suchfilter": true, "oeffentlichFilter": true,
      "werte": [ { "code": "bad", "name": "Bad" }, { "code": "eis", "name": "Eis" } ] }
  ],
  "arten": [
    { "code": "PROTOKOLL", "name": "Protokoll", "gruppen": ["frei"],
      "pflichtfelder": ["titel", "zusatz.sitzung"], "vertraulichkeit": "INTERN",
      "aufbewahrung": { "jahre": 10, "ab": "FELD", "feld": "sitzung" }, "gesetzlichePflicht": false,
      "selbstAusgestellt": true, "kiVorschlag": true, "kiFelder": ["sitzung"],
      "zusatzSchema": { "type": "object", "properties": { "sitzung": { "type": "string", "format": "date" } },
                        "additionalProperties": false },
      "klassifikationen": [ { "code": "fachgebiet", "pflicht": false } ] },
    { "code": "BULLETIN", "name": "Bulletin", "gruppen": ["frei"],
      "pflichtfelder": ["titel", "zusatz.ausgabe", "zusatz.jahr"], "vertraulichkeit": "NORMAL",
      "aufbewahrung": { "jahre": null }, "gesetzlichePflicht": false, "selbstAusgestellt": true,
      "zusatzSchema": { "type": "object", "properties": { "ausgabe": { "type": "integer", "minimum": 1 },
                        "jahr": { "type": "integer", "minimum": 1900, "maximum": 2999 } },
                        "additionalProperties": false } }
  ],
  "regeln": [
    { "code": "ausgabe_eindeutig", "art": "BULLETIN", "typ": "EINDEUTIG", "schwere": "FEHLER",
      "parameter": { "felder": ["zusatz.ausgabe", "zusatz.jahr"] }, "meldung": "Diese Ausgabe gibt es schon" },
    { "code": "ausgabe_folge", "art": "BULLETIN", "typ": "FOLGE", "schwere": "WARNUNG",
      "parameter": { "feld": "zusatz.ausgabe", "zeitraum": { "feld": "zusatz.jahr" } }, "meldung": "Ausgabe fehlt" }
  ]
}
```

### Prüfungen in `Konfiguration.pruefen` (Auszug)
- Codes nach Muster (Arten `^[A-Z][A-Z0-9_]{1,39}$` wie heute, Klassifikationen, Werte und Feldnamen Req 3.2 und
  5.5), eindeutig, unveränderlich.
- Jede Referenz löst auf: Klassifikation einer Art, Standardwert (aktiv), Art und Felder einer Regel (Req 5.5),
  Fristfeld (Req 4.2), `fremdbeleg_art` und `ohne_zahlungsdaten_art` (Req 2.8), `ki_felder` (Felder des
  Zusatz-Schemas).
- Zusatz-Schemas und Pflichtfelder wie Spec 01, 9.5 (`ZusatzSchemaPruefer`), Muster nach Req 2.4, reservierte Felder
  nach Req 2.7, `WISSENSQUELLE` nach Req 2.6, Parameternamen nach Req 8.6.
- Mit Bestand: Gruppen einer Art und `mehrfach` einer Klassifikation sind nicht mehr änderbar, wenn das bestehende
  Werte ungültig machen würde (z. B. `mehrfach` → einfach bei Dokumenten mit zwei Werten). Ein geändertes
  Zusatz-Schema SOLL bestehende Werte (archiviert und im Eingangskorb) gültig lassen; sonst Ablehnung mit Pfad und
  Zahl der betroffenen Dokumente (Felder lassen sich ergänzen, optional machen oder über eine neue Art ablösen).
  Fristregel und `gesetzliche_pflicht` bleiben nach Spec 01, 9.10 änderbar.
- Grenzen (für Datei und jeden App-Aufruf gleich): höchstens 200 Arten, 50 Klassifikationen, 500 Werte je
  Klassifikation, 200 Regeln, 20 Belegnummer-Muster je Art, Datei 1 MiB; `name` ≤ 100, `beschreibung` ≤ 1'000,
  `meldung` ≤ 300 Zeichen; KI-Texte nach «Erweiterungen» (`ki/`).

## Regeltypen
| Typ | Parameter | Prüfung | Wann |
| --- | --- | --- | --- |
| `PFLICHT_WENN` | `wenn: {feld, gleich}`, `dann: [feld…]` | Hat `wenn.feld` den Wert `gleich` (bei Klassifikationen: enthält), müssen alle `dann`-Felder gesetzt sein | Bestätigen, Generator, Import, Änderung |
| `EINDEUTIG` | `felder: [feld…]`, optional `ueberAlleArten` | Kein zweites bestätigtes Dokument mit gleichen Werten in allen Feldern (Req 5.1) | wie oben |
| `FOLGE` | `feld` (ganzzahlig) oder `muster: true` (Gruppe `nr` des Belegnummer-Musters), `zeitraum`: `GESCHAEFTSJAHR`, `{feld}` (ganzzahliges Jahresfeld) oder `GESAMT`, `beginn` (Standard 1) | Fehlende Nummern zwischen `beginn` und dem Höchstwert je Zeitraum | Lückenbericht |
| `ERWARTET` | `je: MONAT` oder `JAHR`, `datum`: `BELEGDATUM` oder ein Datumsfeld, optional `ab` (sonst ab dem ersten bestätigten Dokument der Art), `nurMonat`: `zusatz.<feld>` im Format `JJJJ-MM`, optional `gruppe` (nur `je: MONAT`): `PARTNER` oder `zusatz.<feld>` | Zeiträume bis zum letzten abgeschlossenen ohne Dokument der Art (bei `gruppe` je Gruppe) | Lückenbericht |

Grundmenge von `FOLGE` und `ERWARTET` sind die bestätigten Dokumente; der Bericht im Importlauf nach Spec 03, 4.2
zählt serverseitig zusätzlich die Dokumente dieses Laufs im Eingangskorb.
Die `FOLGE`-Auswertung verarbeitet insgesamt höchstens 10'000 Belegnummern pro Bericht und prüft je Regel innerhalb
von `archiv.dokumentart.muster-zeitgrenze`; Belegnummern über 64 Zeichen gelten als ausserhalb des Musters. Bei
überschrittener Grenze bricht das Werkzeug ab, statt einen unvollständigen Bericht als vollständig auszugeben.

`nurMonat` schliesst Dokumente aus, deren Zusatzfeld nicht dem Format `JJJJ-MM` entspricht. Ohne `gruppe` gibt es eine
Monatsreihe je Dokumentart; mit `gruppe` eine Reihe je Partner bzw. je Wert des Zusatzfelds (Gross-/Kleinschreibung und
Leerzeichen am Rand gleich), jede ab ihrem ersten Dokument, wie in Spec 02 design.md (Aufgabe 7.1c) für den Übergang
festgelegt (Umsetzung 17.6, Nachbesserung Review PR #304: ohne `gruppe` gingen Lücken einer Person oder eines Kontos
unter, sobald eine andere den Monat abdeckte). Den Bericht sehen nur Rollen, die A3 und A4 voll sehen (Req 5.4).
`ab` setzt den Beginn auch dann, wenn schon frühere Dokumente vorliegen. `je: JAHR` ordnet die Dokumente nach dem Tag
des Bezugs dem Geschäftsjahr zu (Geschäftsjahresende der Einstellungen) und prüft nur abgeschlossene Geschäftsjahre.

Für `urinet` schreibt der Seed die Lücken aus Spec 02 design.md (`vollstaendigkeit`) als Regeln: `FOLGE` mit
`muster: true` auf `AUSGANGSRECHNUNG` (Muster `^(?<jahr>\d{4})-(?<nr>\d{3})$`, Zeitraum `GESCHAEFTSJAHR`) sowie
`ERWARTET` je Monat ohne `ab` für `LOHNABRECHNUNG` (`datum: BELEGDATUM`, `nurMonat: zusatz.periode`,
`gruppe: zusatz.person`) und `BANK` (`datum: zusatz.periode_bis`, `gruppe: PARTNER`). Die Belegnummer-Eindeutigkeit eigener Arten bleibt
die Tabelle `belegnummer_eigen` (Spec 01, 8.9), jetzt über `selbst_ausgestellt`.

## Data Models (neu, `db/mandant`)
Changeset-Nummern: die nächste freie Nummer in `db/mandant/changesets/` zum Zeitpunkt der Aufgabe; unten nur die
Namen.
```
-- <nnn>-dokumentart-konfiguration
alter table dokumentart add selbst_ausgestellt boolean not null default false,
                        add belegnummer_muster text[] not null default '{}',
                        add fremdbeleg_art text null references dokumentart(code),
                        add ohne_zahlungsdaten_art text null references dokumentart(code),
                        add ki_vorschlag boolean not null default true,
                        add ki_felder text[] not null default '{}',
                        add ki_hinweise text null,
                        add reihenfolge int not null default 0,
                        add aufbewahrung_feld text null;
-- neuer Check für aufbewahrung_ab mit BELEGDATUM und FELD (alter Check entfernt);
-- (aufbewahrung_ab = 'FELD') = (aufbewahrung_feld is not null)
ki_texte(id int pk check (id = 1), rolle text not null, feldregeln text not null, ki_freigegeben boolean not null)

-- <nnn>-klassifikation
klassifikation(code text pk, name text not null, beschreibung text null, mehrfach boolean not null,
               ki_vorschlag boolean not null, suchfilter boolean not null, oeffentlich_filter boolean not null,
               aktiv boolean not null default true)
klassifikation_wert(klassifikation text references klassifikation(code), code text, name text not null,
                    beschreibung text null, reihenfolge int not null default 0, aktiv boolean not null default true,
                    primary key (klassifikation, code))
dokumentart_klassifikation(art text references dokumentart(code), klassifikation text references klassifikation(code),
                           pflicht boolean not null, standard_wert text null,
                           primary key (art, klassifikation),
                           foreign key (klassifikation, standard_wert) references klassifikation_wert)
dokument_klassifikation(dokument_id uuid references dokument(id), klassifikation text, wert text,
                        primary key (dokument_id, klassifikation, wert),
                        foreign key (klassifikation, wert) references klassifikation_wert)
-- Index (klassifikation, wert) für Filter und Facetten

-- <nnn>-regel
regel(code text pk, art text null references dokumentart(code), typ text not null
      check (typ in ('PFLICHT_WENN','EINDEUTIG','FOLGE','ERWARTET')), parameter jsonb not null,
      schwere text not null check (schwere in ('FEHLER','WARNUNG')), meldung text not null,
      aktiv boolean not null default true)

-- <nnn>-konfiguration-version
konfiguration_version(version int pk, zeit timestamptz not null, wer text not null, grund text null,
                      quelle text not null check (quelle in ('APP','DATEI','SEED','KOMMANDO','MIGRATION')),
                      inhalt jsonb not null, sha256 char(64) not null)
-- nur Einfügen: Trigger BEFORE UPDATE OR DELETE (je Zeile) und BEFORE TRUNCATE (je Anweisung), gilt auch für
-- archiv_owner; Tabelle in LiquibaseMandantDatenbank.NUR_EINFUEGEN aufnehmen (wie das Zugriffsprotokoll)
alter table dokument add konfiguration_version int null references konfiguration_version(version);

-- <nnn>-protokoll-konfiguration
-- Check der Protokollaktionen neu mit zusätzlich KONFIGURATION_AENDERN (wie 004-protokoll-regel-aendern)
```
Die Migration (Req 9.1) weist bestehenden Dokumenten und Dokumentversionen Version 1 zu und legt den Snapshot aus
Dokumentarten und Mandanteneinstellungen an (Quelle `MIGRATION`). Soweit ein Eintrag im öffentlichen Mandantenregister
besteht, übernimmt sie daraus die Einstellungen. Bei der Erstprovisionierung ist der Mandant während der
Schema-Migration noch nicht im öffentlichen Register; Version 1 verwendet dann die dokumentierten Standardwerte.
BEFORE-INSERT-Trigger setzen bei neuen Dokumenten die aktuelle Konfigurationsversion und übernehmen sie bei einer
Dokumentversion vom Dokument. Die Trigger qualifizieren die Tabellen über das Schema der betroffenen Zeile. Spätere
Changesets dieser Spec, die Bestand ergänzen (z. B. die Regeln von `urinet` mit dem Changeset `<nnn>-regel`), legen je
eine weitere Version mit Quelle `MIGRATION` an.
`public.mandant.eigene_arten` bleibt als Spalte bestehen (bestehende Changesets bleiben unverändert), wird aber nicht
mehr gelesen; ein späteres Changeset kann sie entfernen.

Umsetzung 17.2 (Review PR #251): Das Abbild enthält `einstellungen` und `arten`; `zusatzSchema` steht im Abbild als
kanonischer JSON-Text (Schlüssel sortiert, kompakt), damit Migration (jsonb), Client-Text und Vergleich dieselbe Form
haben. Das Abbild der Migration und das der App sind gleich aufgebaut (Arten nach Code in Byte-Reihenfolge, leere
Listen statt `null`, `ohne Schema = {"type":"object","properties":{},"additionalProperties":false}`). Ein neuer
Mandant erhält Version 1 vom Changeset (leer, Quelle `MIGRATION`) und nach dem Seed Version 2 mit Quelle `SEED`.
Änderungen an `PATCH /mandant/einstellungen` erzeugen bis 17.7 keine eigene Version; sie gehen in die nächste Version
ein (die Bindung an `If-Match` kommt mit 17.7). `basisSicherstellen()` legt für Schemas ohne Version 1 diese beim
ersten Zugriff an (Quelle `MIGRATION`). Das Zugriffsprotokoll trägt je Änderung die geänderten Pfade mit alt und neu.
Die Module `dokumentart` und `konfiguration` hängen über ihre `application`-Schichten gegenseitig voneinander ab
(bewusste Ausnahme bis 17.3, danach über einen Port für das Arten-Abbild aufzulösen).

Umsetzung 17.3: Changeset 040 ergänzt die Dokumentart-Spalten und übernimmt bei bestehenden `urinet`-Arten die
Ausstellerliste, Nummern-Muster und Umstufungsziele in das Konfigurationsabbild mit Quelle `MIGRATION`. Die
Übergangs-Seeds `dokumentarten.json` enthalten dieselben Spec-05-Attribute, damit neu provisionierte Mandanten bis
zur Ablösung des Seed-Formats in 17.7 keine abweichenden Standardwerte erhalten. Danach stammen selbst ausgestellte
Arten ausschliesslich aus `DokumentartRepository`; `public.mandant.eigene_arten` wird nur noch durch Changeset 040
gelesen. Belegnummer-Muster werden als JDK-RegEx mit höchstens 1'000 Zeichen geprüft; die Auswertungs-Zeitgrenze gilt
bei der späteren Laufzeitnutzung in 18.1 (dort mit ReDoS-Test, siehe tasks.md). Weitere Grenzen der Prüfung
(technische Obergrenzen, nicht aus den Anforderungen): höchstens 200 Arten je Konfiguration (`ANZAHL_UNGUELTIG`) und
höchstens 20 Belegnummer-Muster je Art (`MUSTER_UNGUELTIG`). Das Changeset heisst 040, weil 039 mit
`039-webseite-erfassung` (PR #253) schon vergeben ist; der Fremdbezug (`fremdbeleg_art`, `ohne_zahlungsdaten_art`)
wird in `DokumentartVerwaltung` vorab geprüft: unbekannte Zielart 422 `KONFIGURATION_UNGUELTIG`, Löschen einer Art,
auf die eine andere verweist, 409 `DOKUMENTART_IN_GEBRAUCH`. Reservierte Felder (Req 2.7): Die Prüfung liegt im Port
`ZusatzSchemaPruefer.reservierteFelderGueltig` und läuft über `DokumentartPruefung` für jeden Pfad, der Arten
speichert (Verwaltung, Seed); `Konfiguration.pruefen` bleibt rein fachlich ohne Schema-Bibliothek. `zugriff` und
`veroeffentlicht` müssen direkt unter `properties` der Wurzel stehen (nicht über patternProperties, Kombinatoren
oder `$ref`); der Standard wird typgenau geprüft (`veroeffentlicht`: JSON-`false`; `zugriff`: Text `mitglieder`
oder `vorstand`, nie `oeffentlich`). `vorstand` als Standard ist ohne Art-Code zulässig, für jede Art (Req 2.7,
Entscheid Fabian 18.11.2026, PR #290, Punkt 3); wegen Req 2.9 bindet die Prüfung den Startwert von
`VORSTANDSPROTOKOLL` im Seed `vhf-gsk` (Aufgabe 10.4, Entscheid Fabian 17.11.2026) nicht an einen Art-Code. Die
Vorbelegung der Auswahl im Eingangskorb mit dem Standard der Art belegt `eingangskorb-karte.spec.ts` (PR #301).

Umsetzung 17.4: Changeset 042 ergänzt `aufbewahrung_feld` und erweitert den Check für den Fristbezug, ohne bestehende
Arten, Dokumentfristen oder Sperren umzuschreiben. `BELEGDATUM` rechnet taggenau; ohne Belegdatum gilt der Eingangstag
in `Europe/Zurich`, auch beim Jahreswechsel und während der Sommerzeit. Alle taggenauen Bezüge verwenden
`LocalDate.plusYears`: Der 29. Februar wird im Nicht-Schaltjahr zum 28. Februar (wie bisher bei `GUELTIG_BIS`).
`Fristbezug` liefert das benannte Zusatzdatum ohne JSON-Abhängigkeit in der Domain. Das Feld steht ohne Präfix
`zusatz.` in `aufbewahrungFeld` (API) bzw. `aufbewahrung_feld` (Datenbank und Übergangs-Seed); es muss direkt unter
`properties` als `string` mit `format: date` definiert und in `pflichtfelder` als `zusatz.<feld>` enthalten sein.
Schema- und Seed-Prüfung nutzen dafür denselben Port `ZusatzSchemaPruefer`; `Konfiguration.pruefen` prüft die
fachliche Pflichtfeld-Zuordnung.

Fehlend, `null` oder leer bedeutet bei vorhandenen Dokumenten unbefristet, nicht ein Ersatzdatum. Der Eingangskorb
und die Dokumentantwort führen dann `FRISTBEZUG_FEHLT` ohne Dokumentverweis, auch nach Bestätigung oder einem
Artwechsel; andere Pflichtfelder bleiben unverändert verpflichtend. Der Generator verlangt bei neuen Dokumenten
weiter sämtliche Pflichtfelder. Ungültige, nicht leere Datumswerte ergeben `ZUSATZ_UNGUELTIG`, keinen stillen
Rückfall. Ein Wechsel der Art darf das Fristfeld leer lassen; das Entfernen eines bereits vorhandenen Pflichtwerts
derselben Art bleibt nach Spec 01, 8.3 verboten. Eine Änderung des Zusatzdatums löst dieselbe Neuberechnung wie das
Belegdatum aus: bei Pflicht oder bestehender Sperre früheres Ergebnis nur als Konflikt protokollieren, ohne beides
übernehmen. Änderungen anderer Zusatzfelder lösen keine Fristneuberechnung aus. Die bestehende Sperrpflege bleibt
unverändert; insbesondere bleiben nicht pflichtige Dokumente ohne vorhandene Sperre ungesperrt. Konfigurationsabbild
und Regelprotokoll enthalten auch den Feldnamen, nie dessen Dokumentwert.

Umsetzung 17.5 (Review PR #289): `klassifikationenUngueltig` bedeutet in Eingangskorb und Einzelabruf dasselbe: die
Klassifikationen, an denen die nächste Speicherung nach der gültigen Version scheitern würde (Req 1.7). Unbestätigt
gilt die Prüfung der Bestätigung (inaktive, gelöschte, der Art nicht zugeordnete Werte, mehrere Werte bei einer
einfachen Klassifikation); fehlende Pflicht-Werte zählen nicht, sie kommen mit der Bestätigung. Bestätigt gilt die
Prüfung der Änderung (behaltene inaktive Werte erlaubt, fehlende Pflicht-Werte zählen). Das Protokoll nennt bei Name,
Beschreibung und Standardwert von Klassifikationen alten und neuen Wert im Klartext wie bei Arten (Req 1.3); Werte
an Dokumenten stehen nie darin. Eine Zuordnung zu einer unbekannten Art ergibt 404 `NICHT_GEFUNDEN`. Namen von
Klassifikationen und Werten enthalten keine Steuerzeichen, Beschreibungen nur Zeilenumbruch und Tabulator.

Protokoll: `KONFIGURATION_AENDERN` mit `{version, aenderungen: [{pfad, alt, neu}]}` für jede Konfigurationsänderung,
**zusätzlich** zu `REGEL_AENDERN`: Ändern sich Jahre, «ab» oder `gesetzliche_pflicht` einer Art, schreibt das Archiv
weiter `REGEL_AENDERN` nach Spec 01, 9.10 (Entscheid 10.11.2026), mit Verweis auf die Version.
Datensparsame Ausnahme bei `einstellungen.kennungen.uid` und `.ibans` (Req 1.3, geändert 18.11.2026, vorgeschlagen
in PR #290, entschieden mit dessen Merge): Diese Änderungen enthalten nur `{pfad, aenderung: "geändert"}`, keine alten
oder neuen Werte. Grund ist die Datensparsamkeit: Die Kennungen stehen vollständig im unveränderlichen
Konfigurationsabbild und müssen im Zugriffsprotokoll nicht vervielfältigt werden (Spec 01, 16.7 regelt Datenstandort
und CI-Logs, nicht dieses Protokoll, und trägt die Ausnahme nicht). Die Regel steht an einer Stelle:
`Konfiguration.Aenderung.ohneWerte()` (alle Pfade unter `einstellungen.kennungen.`), der Adapter
`ZugriffKonfigurationAenderungsProtokoll` gibt das Merkmal an `ProtokollDetails.Aenderung` weiter, das die Werte vor
jeder Serialisierung entfernt; die Protokoll-Domain kennt keine Konfigurationspfade. `toString()` von
`Konfiguration.Aenderung` und `Konfiguration.Kennungen` gibt UID und IBANs nicht aus. Andere Elemente behalten die
bisherige Form mit `alt` und `neu`.
Altzeilen: Protokollzeilen `KONFIGURATION_AENDERN` aus der Zeit vor PR #290 können UID und IBAN im Klartext als
`alt`/`neu` enthalten (bisher nur Stufe A mit Testdaten). Das Zugriffsprotokoll ist nur anfügend (Append-only,
Aufbewahrung nach Spec 01, 10.9), darum bleiben sie unverändert (kein UPDATE). Ob sie bereinigt werden, ist offen
für Fabian (PR #290). Das unveränderliche Konfigurationsabbild enthält die vollständigen Einstellungen weiterhin; dort
lässt sich die Änderung mit der protokollierten Version nachvollziehen.

Nachweise der Nachverfolgung: `KonfigurationMigrationTest` migriert zwei getrennte synthetische Mandantenschemas
mit allen echten Changesets bis 036, legt Bestand an und führt erst dann 037 aus. Dokumente, Fassungen und
Einstellungen bleiben erhalten, ihre Version verweist auf das mit gültigem SHA-256 lesbare Migrationsabbild.
Der Dokumentarten-Seed ist bereits eine JDBC-Transaktion einschliesslich des nachgelagerten Referenz-Batches;
`DokumentartSeedIdempotenzTest` belegt jetzt Abbruch nach der ersten Art sowie Abbruch im Referenz-Batch, vollständigen
Rollback, erfolgreiche Wiederholung und Unverändertheit eines zweiten Mandanten. Keine Änderung alter Changesets
und keine zusätzliche Transaktionslogik für einen bereits atomaren Schritt.

## Error Handling (neue Codes)
| Status | Code | Wann |
| --- | --- | --- |
| 422 | `KONFIGURATION_UNGUELTIG` | Datei oder Element verletzt Schema oder Prüfungen; `befunde[]` mit `pfad`, `meldung` |
| 409 | `KONFIGURATION_IN_GEBRAUCH` | Löschen eines Elements mit Dokumenten |
| 409 | `KONFIGURATION_VERALTET` | `basisVersion` ≠ aktuelle Version |
| 422 | `KLASSIFIKATION_UNGUELTIG` | Werte am Dokument passen nicht zur Art (Req 3.5) |
| 422 | `REGEL_VERLETZT` | Regel mit Schwere `FEHLER` verletzt; `regeln[]` mit `code`, `meldung` |

`FehlercodeSpecTest` vergleicht das Enum `Fehlercode` mit der Tabelle in Spec 01 design.md. Darum trägt die
Aufgabe, die einen Code einführt, ihn im selben PR in diese Tabelle ein (mit Verweis «Spec 05, Req x.y»).
Ebenso trägt die Aufgabe, die das Recht A11 erweitert, das in Spec 01, 2.6 und 2.17 im selben PR nach
(`RechtematrixSpecTest`); die Bezeichnung der Zeile A11 bleibt.

## Testing Strategy
- JUnit: `Konfiguration.pruefen` (jede Prüfung ein Fall), `RegelPruefung` je Typ, `Lueckenbericht`,
  `KonfigurationsVergleich`, `Aufbewahrung` mit `BELEGDATUM` und `FELD`, `KonfigurationSeedTest` (Req 6.7),
  Rundlauf Export → Einspielen ergibt keine Version (Req 6.5), Snapshot-Test von Prompt, Antwortschema und
  Nachbearbeitung für `urinet` (Req 8.3), Append-only der Versionen (Req 1.5), Quelltext-Test ohne Art-Codes von
  `urinet` (Req 2.9), Zeitgrenze der Muster zur Laufzeit (Req 2.4).
- Cucumber unter `features/konfiguration/` (`@spec-05`) mit einem synthetischen Test-Mandanten `probe-verband`
  (`src/test/resources/seed/probe-verband/konfiguration.json`, ohne Art von `urinet`), Req 9.4. Test-Seeds unter
  `src/test/resources/seed/` prüft `KonfigurationSeedTest` gegen Schema und Prüfungen, aber nicht gegen eine
  Stammdaten-Tabelle.
- Isolation: `MandantenIsolationIT` mit den Wegen nach Req 9.3.
- Migration: Test mit einem Schema im Stand vor Spec 05 (Arten von `urinet` und ein Dokument mit Sperre) → nach der
  Migration gleiche Arten, Frist und Sperre, Version 1 (Req 9.1, 9.2).

## Folgeänderungen
Die Folgeänderungen bündelt Aufgabe 17.1 in einem eigenen PR. Ausnahmen, die wegen der Tests im selben PR wie der
Code kommen: Fehlercodes und A11 (siehe oben) sowie die Stammdaten-Tabelle (Aufgabe 17.7).
- **Spec 01 requirements.md:** 1.5 und 9.2 (`konfiguration.json` statt `dokumentarten.json`, Test für jeden Seed),
  2.6 und 2.17 (Umfang A11, Bezeichnung bleibt), 7.1, 7.2, 7.5, 7.9, 7.12 (Konfiguration statt fester Codes und
  Muster), 8.9 und 11.3 (`selbst_ausgestellt`), 9.1, 9.3, 9.7 (neue Attribute und Fristbezüge), Requirement 11 und
  12 (Generator und Drive-Import übergeben `klassifikationen` mit Code), 14.1 und 14.2 (Klassifikationen).
- **Spec 01 design.md:** Datenmodell `dokumentart`, `mandant.eigene_arten` (nicht mehr gelesen), Endpunkt-Tabelle
  (`If-Match` für `/dokumentarten` und `PATCH /mandant/einstellungen`, neue Endpunkte), Abschnitt «KI (`ki`)» (Texte
  und Nachbearbeitung aus der Konfiguration, Tabelle oben), Error Handling (neue Codes), Protokollaktionen
  (`KONFIGURATION_AENDERN` neben `REGEL_AENDERN`). Im selben PR wie Aufgabe 17.7: Absatz «Stammdaten der
  Dokumentarten» (massgeblich `seed/urinet/konfiguration.json`; `KonfigurationSeedTest` ersetzt `DokumentartSeedTest`,
  `StammdatenTabelle` liest die neuen Spalten) und die Stammdaten-Tabelle um die Spalten `selbst_ausgestellt`,
  `belegnummer_muster`, `fremdbeleg_art`, `ohne_zahlungsdaten_art`, `ki_vorschlag`, `ki_felder`, `reihenfolge`,
  Fristfeld und Klassifikationen; `zusatz_schema` und `ki_hinweise` stehen wie heute `zusatz_schema` nur im Seed.
  Spec 01, Aufgabe 3.2 wird ohne diese Spec umgesetzt; das Umstellen übernimmt Aufgabe 18.1 mit dem Snapshot-Test,
  damit 3.2 nicht wartet.
- **Spec 02:** 3.1 und 3.6 (`vollstaendigkeit` aus dem Lückenbericht), 4.2 und 5.2 (`vollstaendigkeit` bietet das
  Archiv nur Rollen an, die A3 und A4 uneingeschränkt sehen, Spec 05, Req 5.4; für andere Rollen fehlt das Werkzeug
  in der Liste, statt gefilterte Lücken zu liefern, die falsche Lücken zeigen könnten), 3.3, 3.4 und Requirement 7
  (über `Funktionen.finanzVerfuegbar()`), design.md `vollstaendigkeit` (Regeln statt fester Muster).
- **Spec 03:** 1.1 (`seed/vhf-gsk/konfiguration.json`), 1.2 (Fachgebiet als Klassifikation `fachgebiet`, mehrfach,
  `oeffentlich_filter`; die Reihe ist die Dokumentart, das Zusatzfeld `reihe` entfällt; Ausgabe-Nr. und Jahr bleiben
  Zusatzfelder mit Regeln `EINDEUTIG` und `FOLGE`; `zugriff` und `veroeffentlicht` als reservierte Felder), 1.3 (über
  Req 8.8), 3.2 (Ausgabe-Nr. und Jahr als `ki_felder`, Fachgebiet als Klassifikation mit KI-Vorschlag; erst nach
  Abnahme nach Req 8.4 und 8.9), 4.1 (Reihe aus dem Ordnernamen = Dokumentart, Fachgebiet als Klassifikation),
  4.2 (Lückenbericht aus `FOLGE` je Reihe und Jahr, serverseitig im Importlauf), 5.1 und 7.2 (Filter nach
  Klassifikationen mit `oeffentlich_filter`, Req 8.6), 8.2 (der Vollexport enthält je Dokument die Klassifikationen
  mit Code und Name und die Konfigurationsversion sowie die aktuelle Konfigurationsdatei und alle Versionen);
  design.md: Stammdaten-Tabelle für `vhf-gsk` analog Spec 01, öffentlicher Filter unverändert; Aufgaben 10.2
  (Seed), 10.4 (Felder je Art aus Aufgabe 18.4), 11.2 und 11.5 (Filter nach Klassifikationen), 12.2 (Vollexport),
  12.3 (`MandantenIsolationIT`).
- **Spec 04:** 1.1 und design.md «Seed-Teil» (die Ergänzung von `WISSENSQUELLE` für `vhf-gsk` kommt aus der
  Konfiguration von `vhf-gsk`, nicht aus dem mandantenunabhängigen Seed-Teil; der Seed-Teil legt die Art an, wenn der
  Code fehlt), 1.5 (fest `ki_vorschlag = nein`, Req 2.6), Aufgabe 13.2.
- **Steering:** structure.md «Belegnummern» (Werte von `urinet`, gepflegt in der Konfiguration; Ausgangsrechnung
  als `^(?<jahr>\d{4})-(?<nr>\d{3})$`), Modul
  `konfiguration/`, `seed/<code>/konfiguration.json`, `features/konfiguration/`; tech.md Spec-Tags `@spec-01` bis
  `@spec-05`; product.md ohne Änderung.
- **docs/umsetzungsplan.md:** Zeile für Spec 05 (Wellen 17–18, nach Gate Phase 1, spätestens vor Welle 10;
  im PR dieser Spec nachgetragen).

## Open Points
- [offen: Entscheid Fabian] Weg, die KI-Vorschläge für die Stichprobe vor der Freigabe zu erzeugen und zu bewerten
  («Probelauf»): Solange `ki_freigegeben = false` gilt, erzeugt der Worker keinen Auftrag `KI_VORSCHLAG` (Req 8.4).
- Rechte der Geschäftsstelle für A11 bleiben der Entscheid aus Spec 03, 3.5.
