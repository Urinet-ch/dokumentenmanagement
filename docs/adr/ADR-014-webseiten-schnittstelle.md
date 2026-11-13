# ADR-014: Webseiten-Schnittstelle als versionierter Vertrag mit getrennten Schlüsseln
**Status:** Vorgeschlagen – Freigabe per Merge mit der Erweiterung von Spec 03 (Requirements 10–15), nicht Teil von 0.6.

**Datum:** 13.11.2026

## Kontext
Fabian will das Archiv über eine API in eine andere Webseite einbinden, zuerst in die Webseite von VHF/GSK (Auftrag
13.11.2026). Spec 03 sah bisher eine öffentliche Schnittstelle `/public/v1` nur zum Lesen vor, angebunden über einen
Site-Key mit Scope `wordpress` und ein WordPress-Plugin. Kräfte und Randbedingungen:
- **Eine Oberfläche für die Geschäftsstelle:** Die Geschäftsstelle soll alles auf der Verbandsseite erledigen können,
  auch das Erfassen neuer Dokumente im Archiv.
- **Geschützte Dokumente:** Drei Stufen (öffentlich, Mitglied, Vorstand); geschützte Dokumente nur über die Webseite,
  nie über eine direkte Datei-URL.
- **Weitere Webseiten:** Eine Partnerseite oder ein anderer Verband soll dieselbe Schnittstelle nutzen können, ohne
  dass das Archiv WordPress voraussetzt.
- **Mandanten und Rechte (ADR-002, ADR-009):** Mandant nur aus der Anmeldung; Rechtematrix A1–A14; 404 vor 403.
- **API-Tokens (Spec 01, 2.5):** Argon2id ohne Cache, an den Mandanten gebunden, widerrufbar, kein Ablauf
  (entschieden von Fabian am 10.11.2026).
- **Plattform (ADR-010):** Die API hat in Stufe A wenig Speicher; Dateien dürfen nie ganz im Heap liegen.
- **KI nur für Vorschläge (ADR-006):** Die Zugriffsstufe setzt nur ein Mensch.

## Entscheid
Entschieden von Fabian am 13.11.2026 (E-A bis E-D); Einzelheiten, die mit «vorgeschlagen 13.11.2026» markiert sind,
gelten mit dem Merge des Spec-PR.
1. **Neutraler Vertrag (E-B).** `/public/v<n>` ist ein versionierter OpenAPI-Vertrag für jede Webseite
   (`docs/schnittstelle/public-v<n>.yaml`). Innerhalb einer Hauptversion nur additive Änderungen; alles andere nur mit
   neuer Hauptversion, die alte läuft mindestens 6 Monate parallel und wird über die Header `Deprecation`, `Sunset` und
   `Link` abgekündigt. Das WordPress-Plugin ist ein Nutzer dieses Vertrags.
2. **Nur serverseitig.** Kein CORS, kein Schlüssel im Browser; die Webseite ist ein vertrauenswürdiger Server.
3. **Getrennte Webseiten-Schlüssel.** API-Tokens nach Spec 01, 2.5 mit den Scopes `webseite-lesen` (jede
   Seitenansicht) und `webseite-erfassung` (nur Verwaltung), je Mandant und Webseite, mit höchster Zugriffsstufe,
   Rotation mit Überlappung und optionaler Absender-Einschränkung; Anlegen nur per Betreiber-Kommando.
4. **Die Webseite meldet die Stufe, der Schlüssel begrenzt sie (E-C).** `oeffentlich` < `mitglieder` < `vorstand`; das
   Archiv liefert alles bis zur gemeldeten Stufe, nie über der Stufe des Schlüssels.
5. **Erfassung über die Webseite (E-A).** Hochladen (nur Bytes), Status, Liste, Datei ansehen, Bestätigen, Ändern mit
   `If-Match`, Veröffentlichen/Zurückziehen und Wiederholen über dieselben Services wie die Web-App; der
   Erfassungs-Schlüssel handelt höchstens mit A1 bis A10 der Rolle `geschaeftsstelle`, genauer mit einer festen Liste
   ohne Verwerfen (A9), weil Verwerfen Dateien löscht; jede Aktion steht mit Schlüssel-ID und gemeldetem Bearbeiter im
   Protokoll. Kein Löschen über die Schnittstelle.
6. **Dateien gemischt (E-D).** Öffentliche Dokumente per vorsignierter URL (≤ 5 Minuten), geschützte als Stream über
   den Webseitenserver, in Blöcken und mit begrenzter Zahl gleichzeitiger Streams.
7. **Das Archiv ruft nie hinaus.** Kein Abholen von URLs (kein SSRF) und keine Webhooks; die Webseite fragt einen
   Änderungs-Feed mit Cursor ab.

## Betrachtete Alternativen
- **Nur lesen, Erfassung nur in der Web-App:** einfacher, aber die Geschäftsstelle bräuchte zwei Oberflächen.
- **Erfassung nur über die Webseite:** verworfen; die Web-App bleibt für Altbestand, Handy-Erfassung und als
  Rückfallebene (E-A «beides»).
- **Browser-Einbettung (JavaScript-Widget oder iframe):** bräuchte CORS oder einen öffentlichen Schlüssel im Browser und
  könnte geschützte Stufen nicht sicher bedienen.
- **WordPress-spezifische Schnittstelle (Scope `wordpress`):** bindet das Archiv an ein Redaktionssystem; jede weitere
  Webseite bräuchte eigene Endpunkte.
- **Ein Schlüssel für Lesen und Erfassen:** ein Schlüssel, der bei jeder Seitenansicht mitgeht, dürfte dann auch
  schreiben; verworfen zugunsten geringster Rechte.
- **Vorsignierte URLs auch für geschützte Dokumente:** Wer die URL weitergibt, umgeht die Anmeldung der Webseite für
  bis zu 5 Minuten; verworfen für `mitglieder` und `vorstand`.
- **Webhooks an die Webseite:** ausgehende Aufrufe, Wiederholungen und Signaturen im Archiv; der Feed ist einfacher und
  verträgt Ausfälle der Webseite.
- **Versionierung über Header statt Pfad:** schwerer zu beobachten und zu drosseln; der Pfad ist eindeutig.

## Folgen
- Neue Scopes und Spalten in `public.api_client` (neues Changeset); Spec 01, 2.5 und die Scope-Bindung werden
  ergänzt; `EndpunktWaechterTest` prüft `@WebseitenZugang`.
- Das reservierte Feld `zugriff` hat drei Werte (Spec 05, 2.7; Spec 04, Wissensquelle).
- Der Webseitenserver ist Teil der Vertrauenskette: Er meldet Stufe und Bearbeiter, die das Archiv nicht prüfen kann.
  Ein Erfassungs-Schlüssel sieht, was die Geschäftsstelle sieht; er gehört nur auf den Webseitenserver und wird
  rotiert. Verlust eines Lese-Schlüssels gibt höchstens dessen Stufe preis. Damit Erfassungsaktionen revisionsfähig
  bleiben, meldet die Webseite ihre Bearbeiter mit Namen; das Archiv führt sie im Benutzerverzeichnis (Spec 01, 10.10).
- Abweichung von «kein Ablauf» (Spec 01, 2.5, Entscheid vom 10.11.2026): Webseiten-Schlüssel erhalten nach einer
  Rotation ein `gueltig_bis` (Ende der Überlappung); für `generator` und `import` bleibt es beim Entscheid.
- Abweichung von «kein Cache» (Spec 01, 2.5): Webseiten-Schlüssel haben einen kurzlebigen Prüf-Cache, damit fremde
  Anfragen mit falschen Schlüsseln die Plätze der Argon2id-Prüfung nicht so belegen können, dass die Webseite
  ausgesperrt ist; Widerruf und Ablauf wirken trotzdem sofort, weil die Zeile des Schlüssels bei jeder Anfrage gelesen
  wird.
- Zwei OpenAPI-Beschreibungen: die der App (Angular-Client) und der Vertrag der Webseiten-Schnittstelle, mit eigener
  Prüfung in der CI.

## Verweise
- `.kiro/specs/03-zweiter-mandant/requirements.md`, Requirements 3, 5, 7, 9–15
- `.kiro/specs/03-zweiter-mandant/design.md`, Overview und Abschnitte «Webseiten-Schnittstelle»
- `.kiro/specs/01-archiv-kern/requirements.md`, 2.5, 2.6, 3.4, 3.13, 9.9, 10.2, 10.10
- `docs/umsetzungsplan.md`, Abschnitt 6 (Entscheide E-A bis E-D)
- ADR-002, ADR-006, ADR-009, ADR-010
