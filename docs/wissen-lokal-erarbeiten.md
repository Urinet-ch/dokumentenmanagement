# Wissen lokal erarbeiten und ins Archiv übertragen

> **Entwurf, nicht umgesetzt.** Diese Anleitung beschreibt, wie es nach den Aufgaben 15.6 (Import) und 15.7
> (Prüfwerkzeug `wissen_paket`) von Spec 04 funktionieren soll (Requirement 19, entschieden von Fabian am 18.11.2026).
> Bis dahin gibt es weder den Upload noch das Werkzeug. Platzhalter stehen in `<spitzen Klammern>`.

Das Archiv nimmt Wissen von aussen nur als **Entwurf** an. Wirksam wird jede Änderung erst, wenn du sie im Archiv
einzeln ansiehst und annimmst. Das Archiv ruft dabei keine KI auf.

## 1. Welches Modell darf welche Quelle sehen?

| Quelle | Lokales Modell (läuft auf dem MacBook) | Cloud-Modell (Copilot, Claude) |
| --- | --- | --- |
| `NORMAL`, ohne Personendaten | ja | ja, auf deine Verantwortung |
| `NORMAL`, mit Personendaten | nein | nein |
| `INTERN`, ohne Personendaten | ja | **nein** |
| `INTERN` mit Personendaten, `PERSONAL` | nein | nein |

Das Archiv kann das ausserhalb nicht erzwingen. Du bestätigst die Regel mit den Angaben `erzeugt_mit` und `kontext_stufe`
im Paket; das Archiv hält sie fest und lehnt Widersprüche ab (z. B. `cloud` mit `INTERN`). Im Zweifel: lokales Modell.

## 2. Vorbereitung (einmalig)
1. Die Wissensquelle (PDF) muss im produktiven Archiv als Dokument der Art `WISSENSQUELLE` bestätigt sein. Das Paket
   verweist auf sie über die SHA-256 der Originaldatei, nicht über eine Archiv-ID.
2. Die Prüfsumme bestimmst du lokal (`shasum -a 256 <datei.pdf>`) oder mit `wissen_paket validate --quellen <ordner>`.
3. Prüfwerkzeug bauen: `mvn -B -ntp -f tools/wissen_paket/pom.xml verify` (ergibt `wissen-paket-<version>.jar`).

## 3. Weg A: lokale Archiv-Instanz (für neue Wissensgebiete)
1. Archiv im Profil `lokal` starten (ADR-012, LM Studio mit geladenem Modell, nur `127.0.0.1`).
2. Dieselben PDFs wie im produktiven Archiv hochladen und als `WISSENSQUELLE` bestätigen; der Ingest läuft mit LM Studio.
3. Entwürfe lokal prüfen und annehmen, dann unter Wissen den **OKF-Export** auslösen (Recht A19).
4. Das ZIP ist bereits ein Wissenspaket (`paket.yaml`, `sha256` je Quelle).

## 4. Weg B: beliebige Werkzeuge (auch für bestehende Seiten)
1. Arbeitsordner anlegen. Für Änderungen an vorhandenen Seiten zuerst im produktiven Archiv einen OKF-Export machen und
   entpacken; dann tragen die Seiten die `basis_version`.
2. Claude Code, Copilot CLI oder ein lokales Modell schreiben `<pfad>.md` nach den Seitenregeln (Frontmatter, jede Aussage
   mit Fussnote, Quellen mit `sha256`, `seite` und wörtlichem `auszug`). Beachte Abschnitt 1: Cloud-Modelle nur mit `NORMAL`.
3. Prüfen: `java -jar wissen-paket-<version>.jar validate <ordner> --quellen <ordner-mit-pdfs>`
4. Bauen: `java -jar wissen-paket-<version>.jar bauen <ordner> -o <paket.zip> --mandant urinet --erzeugt-von <modell>
   --erzeugt-mit lokal|cloud --kontext-stufe NORMAL|INTERN`

## 5. Was das Prüfwerkzeug meldet
Nur Befund-Codes und Pfade, nie Inhalte; Rückgabewert 0 = kein sperrender Befund. Es arbeitet offline und braucht keine Zugangsdaten.
- Sperrend: `FELD_FEHLT`, `PFAD_UNGUELTIG`, `AUSSAGE_OHNE_BELEG`, `FUSSNOTE_OHNE_QUELLE`, `LINK_UNGUELTIG` (HTML, externer Link,
  Bild), `AUSZUG_FEHLT`, `ZU_GROSS`, `PAKET_UNGUELTIG` (ZIP-Grenzen, unzulässige Dateien, YAML).
- Hinweise: `QUELLE_NICHT_GEPRUEFT` (Zuordnung geht nur im Archiv), `AUSZUG_LOKAL_NICHT_GEFUNDEN` (nicht massgeblich: das Archiv prüft
  gegen den Text der PDF/A-Fassung), `FELD_IGNORIERT`.
- Das Archiv prüft jedes Paket beim Import erneut. Das Werkzeug spart nur den Umweg.

## 6. Hochladen und freigeben
1. In der App unter **Wissen > Entwürfe > Import** (`/wissen/entwuerfe/import`, nur Inhaber bzw. Geschäftsstelle) das ZIP hochladen.
2. Prüfbericht lesen: Zahl der Seiten `NEU`, `AENDERN`, unverändert; Befunde je Seite.
   - `QUELLE_UNBEKANNT`: Quelle zuerst hochladen und als `WISSENSQUELLE` bestätigen, dann «Prüfung wiederholen» (kein zweiter Import).
     Beachte: Die bestätigte Wissensquelle löst automatisch einen Ingest aus (offener Punkt 19-a).
   - `AUSZUG_NICHT_GEFUNDEN`: Zitat steht nicht auf der Seite; vermutlich erfunden. Seite korrigieren oder Änderung verwerfen.
   - `QUELLE_PERSONAL`: Personalunterlagen gehören nie in die Wissensbasis.
3. Derselbe ZIP lässt sich nicht zweimal importieren (Meldung mit Verweis auf den Entwurf). Ein geändertes Paket ist ein neues.
4. Entwurf öffnen, **jede Änderung einzeln** mit Diff ansehen und annehmen, ändern oder verwerfen; ein «alle annehmen» gibt es nicht.
   Veraltete Änderungen (`VERALTET`): Seite im Archiv wurde inzwischen geändert; Paket neu erarbeiten.
5. Die Vertraulichkeit leitet das Archiv aus den Quellen ab. Ein Paket kann sie nur erhöhen, nie senken.
