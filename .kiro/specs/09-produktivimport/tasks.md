# Implementation Plan — 09 Produktivimport

Voraussetzungen: Spec 09 auf `main` gemergt; Spec 01 bis Welle 4, Spec 02 Wellen 6 und 7, Spec 05 Wellen 17 und 18
umgesetzt (Bestandsaufnahme `docs/spikes/09-bestandsaufnahme.md`). Die Wellen 27 bis 30 folgen auf die Nummern
von Spec 06 (Welle 26). Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je
Aufgabe) nach `CLAUDE.md`, Branch `feature/09-<aufgabe>-<stichwort>`. Rollen: **[Backend]**, **[Frontend]**,
**[Infra]**, **[QA]**, **[Fabian]** (manuell, nicht vom Agenten). Steht ein Wert noch auf «[offen: …]», wird er nicht
geraten; die Aufgabe wartet auf den Entscheid. Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`.

Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` und `@spec-09` unter
`features/produktivimport/` (ausser bei Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`. Führt eine
Aufgabe einen Fehlercode, einen Auftragstyp, ein Protokoll-Ereignis oder einen Konfigurationsschlüssel ein, trägt sie
das im selben PR in Spec 01, design.md bzw. im Inventar von Spec 06, design.md nach, sonst werden
`FehlercodeSpecTest` bzw. `EinstellungsInventarTest` rot. Alle Testdaten sind synthetisch; echte Belege erst nach
Aufgabe 30.1.

Kein Agent greift auf die VPS, das Infra-Repo der VPS, Secrets oder `.github/workflows/` zu und deployt nicht;
solche Schritte sind Aufgaben **[Fabian]**, vorbereitet als Patch oder Anleitung im Repo.

Zeitrahmen (Richtwert, gebunden an Entscheid #731): Welle 27 vom 01.12. bis 04.12.2026, Welle 28 vom 07.12. bis
15.12.2026, Welle 29 vom 15.12. bis 23.12.2026, synthetischer Probelauf (29.10) vom 28.12. bis 31.12.2026; Welle 30
(Stichtag, Richtwert 01.01.2027, Bestand bis 31.01.2027) beginnt erst nach Aufgabe 30.1.
**Geändert 28.11.2026 (entschieden von Fabian am 28.11.2026, Variante B):** Stichtag 01.01.2027 und Bestand bis
31.01.2027 sind entschieden (E7). Weil der Wechsel auf Stufe B (Spec 01, 31.7) nach dem synthetischen Probelauf auf
Stufe A liegt und Test-Buckets erst 1 Tag nach dem letzten Upload gelöscht werden können, ist 29.10 bis 28.12.2026
abgeschlossen (Richtwert 24.12. bis 28.12.2026, bisher «vom 28.12. bis 31.12.2026»); Spec 01, Welle 31 läuft parallel
zu den Wellen 27 bis 29.

## Welle 27 — Entscheide, Grundlagen und Spike
- [x] 27.1 Entscheide und Bestandsaufnahme [Fabian]
  - Erledigt 28.11.2026: Entscheide E8 bis E13 von Fabian, datiert in design.md, «Open Points» (nachgetragen mit
    Aufgabe 27.2); die Bestandsaufnahme ist mit dem Merge von #745 bestätigt. E6 folgt nach 27.4; E7 ist mit #755
    entschieden.
  - Bestandsaufnahme `docs/spikes/09-bestandsaufnahme.md` prüfen und bestätigen.
  - Entscheide E8, E9, E10 (Weg nach aussen), E11 (Budget Stufe A), E12 (Dateinamen-Regeln), E13 (verworfene
    Seiten im Stapel-Original); E6 vorläufig, endgültig nach 27.4. Ergebnisse datiert in design.md, «Open Points».
  - Werte aus der privaten Liste (Platzhalter in design.md) bereithalten, nicht ins Repo schreiben.
  - _Anforderungen: 0.1_

- [x] 27.2 Folgeänderungen und Rechte [Backend]
  - Folgeänderungen nach design.md, «Folgeänderungen», Spalte «27.1», als ein PR (Steering, Spec 01, 02, 05, 06 als
    ausgewiesene Ergänzungen mit Datum des Entscheids, nie stillschweigend).
  - Enum `Aktion` um A25 bis A29, `rechtematrix.json`, `RechtematrixSpecTest` liest Spec 09 als vierte Quelle;
    Ordner `features/produktivimport/` mit erster Feature-Datei `@spec-09`.
  - _Anforderungen: 15.1_
  - _Abhängig von: 27.1_

- [x] 27.3 Prototyp Dienst `sftp` für den Spike [Backend]
  - Maven-Modul `deploy/infomaniak/sftp/` (Java 25, Apache MINA SSHD) mit Grundfunktion nach design.md, Abschnitt 1:
    nur Schlüssel, nur SFTP, virtuelle Wurzel, Unterordner, Log als JSON; Liste älterer Verfahren konfigurierbar,
    Standard leer.
  - Dockerfile und Anleitung zum lokalen Start auf dem Mac im Heimnetz (Port nur im Heimnetz), Testschlüssel nur
    lokal erzeugt; keine Änderung an `deploy/infomaniak/compose.yml` und am Workflow.
  - JUnit mit MINA-Client: Upload, Passwort abgelehnt, Shell abgelehnt.
  - _Anforderungen: 1.2, 1.3_

- [ ] 27.4 Spike SFTP mit dem Scanner [Fabian]
  - Prototyp aus 27.3 lokal starten; am Gerät Firmware prüfen, Schlüsselpaar erzeugen, Profil «Archiv Stapel» mit
    Host-Schlüssel anlegen, Testscan mit Testseiten senden (keine echten Belege).
  - Bei Fehlschlag Verfahren schrittweise erweitern (nur im Prototyp) und das Log auswerten.
  - Ergebnis in `docs/spikes/09-scanner-sftp.md` (ohne `<scanner>`, Adressen und Schlüssel): Verfahren, Dateiname beim
    Schreiben, nötige SFTP-Operationen, Dauer für 50 Blatt; Entscheid E6 endgültig, betroffene Aufgaben anpassen.
  - _Anforderungen: 0.2, 0.3, 0.4, 0.5_
  - _Abhängig von: 27.3_

- [x] 27.5 Trennblatt-Generator [Backend] [Frontend]
  - `TrennblattService` (ZXing `QRCodeWriter`, PDFBox A4 doppelseitig, eingebettete Schrift), `POST /api/v1/trennblaetter`.
  - Seite `/trennblaetter` (Arten aus den aktiven Dokumentarten, Anzahl, Deckblatt-Titel bis 60 Zeichen), Gestaltung
    nach `docs/oberflaeche/README.md`, Anleitung und Hilfe-Katalog nachgeführt.
  - Unit-Test: QR-Inhalte inklusive URL-Kodierung von Umlauten; Szenario für `TRENNBLATT_UNGUELTIG`.
  - _Anforderungen: 6.1, 6.2, 6.3, 6.5_

- [ ] 27.6 Quellenregister, Quellläufe und Prüfsummen-Abfrage [Backend]
  - Changesets `quelle`, `quelle_lauf`, Spalte `dokument.quelle_lauf_id`, `dokument_quelle_ck` mit `GMAIL` und
    `ORDNER`; Seed der allgemeinen Quellen für `urinet` (ohne Zugang, ohne Geschäftsangaben).
  - Endpunkte nach design.md, Abschnitt 5, inklusive `POST /api/v1/dokumente/pruefsummen`; Formularfelder
    `quelle` und `quelllauf` am Upload; Scope `import` für Prüfsummen und Quellläufe (Ausnahme im
    `EndpunktWaechterTest`), Rechte A27 und A28 je Rolle.
  - Zähler serverseitig, Trockenlauf ohne Anlegen; Mandantentrennung mit zwei Mandanten.
  - _Anforderungen: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.9, 15.2_
  - _Abhängig von: 27.2_

## Welle 28 — Scan-Eingang und Stapelverarbeitung
- [ ] 28.1 Datenmodell Stapel [Backend]
  - Changesets `stapel`, `stapel_seite`, Spalten `dokument.stapel_id`, `stapel_von`, `stapel_bis`; Auftragstypen.
  - _Anforderungen: 5.1, 5.2_

- [ ] 28.2 Dienst `sftp` vollständig [Backend]
  - Ausbau des Prototyps nach dem Spike: Rechte nach 1.4 im Accessor, Sperre je Quelladresse, Sitzungs- und
    Grössengrenzen, ältere Verfahren nur nach Spike-Liste mit Begründung im README des Moduls, Secret-Dateien
    `archiv_sftp_hostkey` und `archiv_sftp_schluessel`.
  - Sicherheitstests mit MINA-Client: Passwort, Keyboard-Interactive, Shell, Exec, Weiterleitung, Löschen, `mkdir`,
    Links, `..`, Lesen fremder Dateien abgelehnt.
  - _Anforderungen: 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 1.10_
  - _Abhängig von: 27.4_

- [ ] 28.3 Compose, Härtung und Budget [Infra]
  - Dienst `sftp` und Volume `archiv_eingang` in `deploy/infomaniak/compose.yml` (Limits nach E11), Einbindung des
    Volumes in `api`; `DeployHaertungTest`, `tools/pruefe_compose_konsistenz.py` und
    `.github/scripts/pruefe-speicherbudget.py` (Skript, kein Workflow) für den neuen Dienst.
  - Vorschlag für den Deploy-Workflow (Image bauen, Secret-Dateien schreiben) als Patch unter `docs/betrieb/`.
  - _Anforderungen: 1.8, 1.9_
  - _Abhängig von: 27.1, 28.2_

- [ ] 28.4 Weg nach aussen, Workflow und Secrets [Fabian]
  - Infra-Repo der VPS nach E10 (TCP-Eingang, Compose-Policy, ufw, Cloud-Firewall, Freigabe `<heim-adresse>`).
  - Patch aus 28.3 in `.github/workflows/infomaniak-deploy.yml` übernehmen; GitHub-Secrets für Host-Schlüssel und
    autorisierte Schlüssel anlegen; Deploy nur über den Workflow.
  - _Anforderungen: 1.6_
  - _Abhängig von: 28.3_

- [ ] 28.5 Scan-Eingang im Worker [Backend]
  - `ScanEingangTakt`, `StapelEmpfang.annehmen` (Prüfungen, SHA-256 zuerst, Ablage unter `eingang/stapel/`, Löschen
    lokal, Auftrag `STAPEL_ANALYSE`), Zuordnung Verzeichnis → Mandant aus der Konfiguration, Benachrichtigung bei
    Fehlern.
  - Integrationstest mit dem Dienst aus 28.2 und MINA-Client; zwei Mandanten.
  - _Anforderungen: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_
  - _Abhängig von: 28.1, 28.2_

- [ ] 28.6 Scanner einrichten und Trennblätter drucken [Fabian]
  - Feste Adresse im Heimnetz, Zeitserver, Passwort der Weboberfläche, «Hintergrundfarbe entfernen» aus.
  - Profile «Archiv Stapel» (`/stapel`, beidseitig lange Kante, Farbe 300 dpi, PDF mehrseitig), «Archiv Einzeln»
    (`/einzeln`), «Archiv Glas» (`/glas`, Vorlagenglas, einseitig); Dateiname ohne Inhaltsbezug (0.3).
  - Trennblätter je Art aus 27.5 auf farbiges Papier drucken.
  - _Anforderungen: 2.4, 6.3_
  - _Abhängig von: 27.5, 28.4_

- [ ] 28.7 Seitenanalyse und Leerseiten [Backend]
  - `SeitenAnalyse` im Auftrag `STAPEL_ANALYSE`: Rastern mit 150 dpi seitenweise, QR mit ZXing (normal und
    invertiert, Swiss QR bleibt Inhalt), Leerseiten mit Rand 5 % und Schwelle des Mandanten (Einstellung Spec 06,
    Klasse (a)), Ergebnis in `stapel_seite`.
  - _Anforderungen: 3.1, 4.1, 4.2_
  - _Abhängig von: 28.5_

- [ ] 28.8 StapelTrenner und Erzeugung der Dokumente [Backend]
  - `StapelTrenner` als reine Funktion mit mindestens 15 tabellengetriebenen Fällen (design.md, Abschnitt 3).
  - `StapelDokumentErzeuger`: `importPage` verlustfrei, Erfassung über `DokumentErfassen.imKontextErfassen` mit
    Quelle `SCAN`, ohne Dateinamen, Rückrollen bei Abbruch, Grenze 50 MB je Auszug.
  - Rundlauftest mit Trennblättern aus 27.5 (synthetischer Stapel, gedrehte Seiten, Swiss-QR-Seite).
  - _Anforderungen: 2.5, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 4.3, 4.4, 4.5, 6.4_
  - _Abhängig von: 27.5, 28.7_

- [ ] 28.9 Lebenszyklus Stapel-Original [Backend]
  - `StapelAbschluss`: Kopie nach `archiv/`, Prüfsummenvergleich, Sperre nach 5.4, Manifest und Prüflauf; bei «alle
    verworfen» Löschen mit Protokoll.
  - Test mit MinIO (Object Lock): archiviertes Stapel-Original nicht löschbar, Manifest enthält es.
  - _Anforderungen: 5.3, 5.4, 5.5, 5.6, 5.8_
  - _Abhängig von: 28.8_

- [ ] 28.10 Stapel in der Web-App [Backend] [Frontend]
  - `StapelResource` nach design.md (A25, A26); Seite «Stapel» mit Liste, Protokoll, Seitenvorschau, Aktionen «neu
    analysieren» und «als ein Dokument übernehmen»; Hinweis auf angehaltene Stapel im Eingangskorb; im Dokument
    Link «aus Stapel …, Seiten x bis y» nur mit A25. Anleitung und Hilfe-Katalog nachgeführt.
  - _Anforderungen: 3.9, 5.7, 15.2_
  - _Abhängig von: 28.8_

- [ ] 28.11 Rückfall Scan per E-Mail [Backend]
  - Nur wenn E6 den E-Mail-Eingang wählt oder als zweiten Weg will; sonst im PR von 27.4 als entfallen vermerkt.
  - Spalte `integration_mail.scanner_absender`, `MailErfassen` gibt Anhänge dieser Absender an `StapelEmpfang`.
  - _Anforderungen: 2.10_
  - _Abhängig von: 28.5_

- [ ]* 28.12 Scanqualität [Backend] [Frontend]
  - Effektive Auflösung, Zeichen je Seite, `dokument_seite_vermerk`, Anzeige im Eingangskorb.
  - _Anforderungen: 13.1, 13.2, 13.3_

## Welle 29 — Quellen, Dubletten und Vollständigkeit
- [ ] 29.1 Quellen in Werkzeugen und Web-App [Backend] [Frontend]
  - Drive-Import (`tools/drive_import`) und `archiv_client.py` ordnen Uploads einem Quelllauf zu; Trockenlauf mit
    Prüfsummen-Abfrage; Schlussbericht als Zähler des Laufs.
  - Seite «Quellen» mit Status, Zählern und Läufen (A27), Pflege nur mit A28; Anleitung nachgeführt.
  - _Anforderungen: 10.7, 10.8_
  - _Abhängig von: 27.6_

- [ ] 29.2 Mac-Eingang [Backend]
  - Unterbefehl `eingang` im Drive-Import-Werkzeug: `einrichten`, `starten`, `status`; launchd-Plist; Token aus der
    Token-Datei; Zuordnung Unterordner → Quelle.
  - Tests mit Temp-Ordner und WireMock (`NEU`, `DUBLETTE`, `ABGELEHNT`, 4xx, 429, 5xx, Netzfehler); Token nie im Log.
  - README des Werkzeugs um die Installation in fünf Schritten ergänzt.
  - _Anforderungen: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_
  - _Abhängig von: 29.1_

- [ ] 29.3 Dateinamen-Regeln, Aliasse und Nachlauf [Backend]
  - Abschnitt `dateinamenRegeln` der Konfiguration (Schema, Prüfung, Seed-Beispiele mit `<generator-praefix>` erst mit
    Wert von Fabian), Tabelle `belegnummer_alias` (A11), `DateinamenErkennung` im Erfassungsweg für Scope `import`,
    `DateinamenAbgleich` nach der OCR.
  - `archiv_client.py nachlauf` mit `--trocken` und `--quelllauf`; Zuordnung der JSON-Felder aus `rechnung_gen.py`
    (Format von Fabian bestätigen lassen, nicht raten).
  - _Anforderungen: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_
  - _Abhängig von: 27.1, 29.1_

- [ ] 29.4 Gmail-Import [Backend]
  - Quelle `GMAIL_LABEL`, Geheimnis über `Verschluesselung`, Betriebsart «nur lesend» von `ImapPostfach`, Auftrag
    `QUELLLAUF_GMAIL`, Spalte `mail_eingang.herkunft`.
  - GreenMail-Tests: Flags unverändert, zweiter Lauf ohne neue Dokumente, gemeinsame Message-IDs mit dem
    E-Mail-Eingang, Fehlermeldung bei falschem Passwort ohne Passwort im Log.
  - _Anforderungen: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_
  - _Abhängig von: 27.6_

- [ ] 29.5 Fachliche Dubletten [Backend]
  - Changesets `dublette`, `dokument_beziehung`, Spalten `hauptfassung`, `text_simhash`; `DublettenPruefung` mit
    drei Stufen und Rang der Quellen; Entscheid-Endpunkt mit allen Aktionen (A7, A9); 409 `DUBLETTE_OFFEN` beim
    Bestätigen; Sichtbarkeit nach 11.4.
  - _Anforderungen: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.9, 11.10_
  - _Abhängig von: 29.3_

- [ ] 29.6 Dubletten im Eingangskorb [Frontend]
  - Hinweis «Dublette von …» mit Stufe, beide Dokumente nebeneinander, Vorschlag nach Rang, Aktionen mit Grund; im
    Dokument die Liste der Fassungen und Anhänge. Anleitung nachgeführt.
  - _Anforderungen: 11.4, 11.6_
  - _Abhängig von: 29.5_

- [ ] 29.7 Hauptfassung in Auswertungen [Backend]
  - `kennzahl`, `offene_posten`, `fristen`, Lückenbericht, Regel `EINDEUTIG`, Zähler der Suche und Jahresexport
    (`belegliste.csv` nur Hauptfassungen, Beilagen unter `beilagen/`) filtern `hauptfassung = true`.
  - Test: derselbe Beleg aus drei Quellen zählt einmal.
  - _Anforderungen: 11.8_
  - _Abhängig von: 29.5_

- [ ] 29.8 Vollständigkeitsbericht [Backend] [Frontend]
  - `VollstaendigkeitBericht` auf dem Lückenbericht plus Prüfungen Quellen, Eingangskorb und Stapel;
    `luecke_begruendung` (A29); Endpunkte; PDF-Fassung; Einbindung in Jahresexport und Chat-Werkzeug
    `vollstaendigkeit`; neue Regeln `FOLGE` in der Konfiguration `urinet` erst mit den Startwerten von Fabian (K11).
  - Seite «Vollständigkeit» mit Geschäftsjahr, Lücken, Begründen und Zurückziehen; Anleitung nachgeführt.
  - Test mit synthetischen Lücken und Begründungen.
  - _Anforderungen: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 15.2_
  - _Abhängig von: 27.6, 29.7_

- [ ] 29.9 Verfahrensbeschreibung ergänzen [Fabian] [Backend]
  - Abschnitte Scanprofile, Trennblätter, Stapelprotokoll und Seitenbilanz, Leerseiten, fachliche Dubletten, Papier,
    verworfene Seiten im Stapel-Original (E13); Anhang zum Scan-Eingang; Längengrenze nach Spec 01, 16.6.
  - _Anforderungen: 14.1, 14.2, 14.3_
  - _Abhängig von: 28.9, 29.5_

- [ ] 29.10 Synthetischer Probelauf [Fabian] [QA]
  - Probestapel nach 16.1 (synthetische Seiten, Trennblätter aus 27.5) in einem Testmandanten scannen; erwartete
    Dokumente und Seiten mit dem Stapelprotokoll vergleichen; Speichermessung nach 2.9.
  - Ergebnis in `docs/abnahme/09-probelauf.md`. **Gate Probelauf:** alle Dokumente korrekt, Bilanz stimmt.
  - _Anforderungen: 2.9, 16.1_
  - _Abhängig von: 28.6, 28.10, 29.1_

## Welle 30 — Stichtag und Produktivimport (Fabian, erst nach Spec 01, Welle 31)
- [ ] 30.1 Gate Echtbelege [Fabian]
  - Geändert 28.11.2026 (entschieden von Fabian am 28.11.2026, Variante B; bisher «Entscheid #731 (Variante A, B
    oder C) liegt vor; die dann geltenden Voraussetzungen sind erfüllt (z. B. Spec 01, 5.11 Stufe B, 5.4
    Produktivbetrieb, 5.5 Sicherung, oder die Änderung von 16.15 und 16.17 durch den Entscheid)»): Das Archiv läuft
    auf Stufe B auf der bestehenden VPS (Spec 01, 16.17); die Gates aus Spec 01, Welle 31 sind mit Betriebsnachweis
    erfüllt und das Go/No-go (Spec 01, 31.8) ist «Go».
  - Probelauf mit wenigen echten Belegen auf Stufe B, Richtwert 31.12.2026; Messung nach 3.10.
  - Ergebnis in `docs/abnahme/09-echtbelege.md` (ohne Inhalte und Nummern echter Belege).
  - _Anforderungen: 3.10, 16.2_
  - _Abhängig von: 29.10, Spec 01: 31.8_

- [ ] 30.2 Umstellung zum Stichtag [Fabian]
  - Generatoren auf Archiv-Versand, Mac-Eingang einrichten, Weiterleitung an `<archiv-postfach>`, Scanprofile auf den
    Mandanten `urinet`.
  - _Anforderungen: 16.5_
  - _Abhängig von: 30.1_

- [ ] 30.3 Import des digitalen Bestands 2026 [Fabian] [QA]
  - Reihenfolge nach 16.3; je Quelle Trockenlauf, dann echter Lauf; Aliasse `<alias-startwerte>` vorher anlegen.
  - Eingangskorb und Dubletten abarbeiten; Quelle danach `ABGESCHLOSSEN` bzw. `LAUFEND`.
  - _Anforderungen: 16.3, 16.4_
  - _Abhängig von: 30.2, Spec 01: 31.9 (Restore-Probe auf Stufe B, ergänzt 28.11.2026)_

- [ ] 30.4 Papier 2026 scannen [Fabian]
  - Ordner für Ordner mit Deckblatt und Trennblättern; digital vorhandene Belege nur scannen, wenn unterschrieben
    oder mit Notiz; angehaltene Stapel sofort klären.
  - _Anforderungen: 16.3_
  - _Abhängig von: 30.3_

- [ ] 30.5 Abschluss [Fabian] [QA]
  - Vollständigkeitsbericht 2026: Lücken schliessen oder begründen (`<erwartete-luecken>`); Eingangskorb leer;
    Prüflauf ohne Abweichung; Jahresexport 2026 erstellen und an den Treuhänder; Spec 01, Aufgabe 5.8 im selben PR
    nachweisen.
  - Ergebnis in `docs/abnahme/09-abschluss.md`. **Gate Abschluss:** 16.6 erfüllt; danach Entscheid E5 (Papier).
  - _Anforderungen: 12.3, 16.6, 16.7_
  - _Abhängig von: 30.4_
