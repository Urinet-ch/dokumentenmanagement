# Design Document — 07 Einführung und Hilfe

## Overview
Zwei Teile, beide nur im Frontend:
1. **Einführung** auf der Startseite: Ein neuer Benutzer sieht über den Kacheln eine Karte mit höchstens sechs Schritten,
   die sich nach seinen Rechten richten. Ob er die Einführung schon kennt, merkt sich der Browser (Einführungsvermerk).
2. **Benutzeranleitung**: Markdown-Dateien im Repository, die mit der App gebaut, vom Service Worker vorab geladen und im
   Browser dargestellt werden. Der Knopf «Hilfe» in der Kopfzeile öffnet die **Hilfe-Leiste** mit dem Abschnitt zur
   aktuellen Seite, ohne die Seite zu verlassen; die Seite `/hilfe` zeigt die ganze Anleitung der Rolle mit
   Inhaltsverzeichnis und Sprungmarken.

Massgebend bleibt alles Bestehende: Rechtematrix (Spec 01, 2.6; Spec 04, 12.1; im Frontend `rechte.ts`), Menü und
Zuordnung der aktiven Seite (`navigation.ts`, `MENUE`, `aktiverPfad`), Branding (`BrandingService`), Anmeldung
(`AnmeldungService`, Tokens nur im Arbeitsspeicher), Gestaltung (`docs/oberflaeche/README.md`), PWA (`ngsw-config.json`).
Das Backend ändert sich nicht; neue Feature-Dateien liegen unter `backend/src/test/resources/features/hilfe/`, weil dort
alle Szenarien liegen (tech.md).

## Architektur
```mermaid
flowchart LR
  subgraph Repo
    MD[frontend/public/anleitung/*.md]
    KAT[hilfe-katalog.ts\nAnker, Datei, Bedingung, Pfade]
    TX[texte.de-CH.ts\nKnöpfe, Schritte]
  end
  MD -->|ng build, Asset| SW[(Service Worker\nAsset-Gruppe anleitung,\nprefetch)]
  SW --> LADER[AnleitungLader\nfetch /anleitung/x.md]
  LADER --> PARSER[anleitungMarkdown\nTeilmenge → Textteil-Baum]
  PARSER --> TEXT[app-anleitung-text\nnur Interpolation]
  KAT --> FILTER{darf(rolle, aktion)\nrechte.ts}
  FILTER --> LEISTE[Hilfe-Leiste\nmat-sidenav end]
  FILTER --> SEITE[/hilfe]
  FILTER --> EINF[Einführung\nStartseite]
  TEXT --> LEISTE
  TEXT --> SEITE
  EINF <-->|Vermerk, SHA-256| LS[(localStorage\narchiv.einfuehrung)]
  BR[BrandingService] --> EINF
  BR --> SEITE
```
Kein Pfeil führt zum Server ausser dem Laden der statischen Dateien, die der Service Worker mit der App holt.

## Entscheide
Alle Punkte sind vorgeschlagen am 21.11.2026 und gelten als entschieden mit dem Merge dieses PR.

| Nr. | Frage | Vorschlag | Verworfene Alternativen (Begründung unten oder in «Abgelehnte Optionen») | Requirement |
| --- | --- | --- | --- | --- |
| E1 | Wer ist «neu», wo wird das gemerkt? | Neu ist, wer in diesem Browser für Mandant und `sub` keinen Vermerk hat; der Vermerk liegt im `localStorage`, Kennung als SHA-256, nichts geht an den Server | Keycloak-Attribut, Tabelle im Mandantenschema, Ableitung aus dem Benutzerverzeichnis (Abschnitt «Einführungsvermerk») | 1 |
| E2 | Form der Einführung | Karte zwischen Seitenkopf und Kacheln, höchstens sechs Schritte, Schritte nach Rechten | modaler Dialog, Tour mit Markierung einzelner Elemente, eigene Seite `/willkommen` | 2, 3.1 |
| E3 | Wo steht der Menüpunkt «Hilfe»? | Kopfzeile, neben dem Farbschema-Knopf, für jeden Besucher | Eintrag in der Seitenleiste (fehlt auf dem Handy hinter dem Menüknopf und ganz, wenn das Menü leer ist) | 4.1 |
| E4 | Kontextbezogene Hilfe | Hilfe-Leiste über der Seite, ohne Navigation; die Seite `/hilfe` für die ganze Anleitung | jede Seite navigiert zu `/hilfe#…` (Aufnahmen in «Erfassen», Chatverlauf und offene Formulare gingen verloren, weil sie nur im Arbeitsspeicher liegen) | 4.2 |
| E5 | Quelle der Texte | Markdown unter `frontend/public/anleitung/`, eine Datei je Abschnitt, Hilfe-Katalog in TypeScript | Texte in `texte.de-CH.ts`; Markdown unter `docs/`; Wissensbasis (Spec 04); Endpunkt im Backend | 7.1, 7.2 |
| E6 | Darstellung | eigene, kleine Markdown-Teilmenge im Frontend → Baum aus `Textteil` wie die Wissensseite, nur Interpolation | Bibliothek `marked` plus `wissenText()`; Darstellung durch den Renderer des Backends (`WissensMarkdown`) | 7.3, 7.4 |
| E7 | Offline | eigene Asset-Gruppe `anleitung` mit `installMode: prefetch` | `lazy` (fehlt offline, wenn nie geöffnet); `dataGroups` für einen Endpunkt | 8 |
| E8 | Pflege | Ergänzung der Definition of Done in `tech.md` (in diesem PR) | eigene Prüfung in der CI, die bei Änderungen unter `frontend/src/app/` eine Änderung der Anleitung verlangt (zu viele Fehlalarme) | 7.5 |
| E9 | Bilder | keine Bildschirmfotos und keine Videos in der ersten Fassung | Bildschirmfotos (veralten mit jeder Gestaltungsänderung, Gefahr echter Daten, Grösse offline) | Introduction |
| E10 | Anleitungen unter `docs/` | Bedienung wandert in die App, `docs/` behält Betreiber-Wege, Notweg, Kontaktstellen | beide parallel pflegen | 7.7 |
| E11 | Einordnung | Wellen 22 und 23, nach dem Merge parallel zu den übrigen Wellen | nach Gate Phase 1 | Introduction |
| E12 | Bestehende Benutzer | Fabian, Joel und der Treuhänder sehen die Einführung nach der Einführung dieser Spec einmal je Browser (kein Vermerk); sie können sie überspringen | Vermerk für alle bestehenden Benutzer vorbelegen (bräuchte Serverdaten) | 1.1 |

## Einführungsvermerk (Requirement 1)
```json
{
  "v": 1,
  "eintraege": [
    { "k": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "version": 1, "art": "ABGESCHLOSSEN", "am": "2026-11-21" }
  ]
}
```
- Schlüssel `archiv.einfuehrung`; `k` ist der SHA-256 (hex) von `<mandant>:<sub>` über `crypto.subtle.digest` (nur in
  sicherem Kontext; ohne `crypto.subtle` gilt der Fall «Speicher nicht verfügbar», Requirement 1.5). `am` nur als Datum.
- Höchstens 20 Einträge, ältester zuerst entfernt (geteilte Geräte, Requirement 1.6). Jeder Lese- und Schreibzugriff in
  `try/catch` wie beim Farbschema (`farbschema.ts`).
- `version` ist die Version der Einführung (`EINFUEHRUNG_VERSION = 1`). Eine höhere Version zeigt die Einführung **nicht**
  erneut an (Hinweise «Was ist neu» sind nicht Teil der Spec); die Zahl hält nur fest, welche Fassung jemand gesehen hat.
- Der Dienst `EinfuehrungVermerk` (root) hält zusätzlich die Vermerke dieser Sitzung im Arbeitsspeicher; der aktuelle
  Schritt liegt im Dienst `Einfuehrung` und überlebt die Navigation innerhalb der App, nicht das Neuladen (Requirement 3.6).

**Vor- und Nachteile des Vermerks im Browser (E1):**

| Vorteile | Nachteile |
| --- | --- |
| kein Endpunkt, keine Tabelle, keine Migration, kein Protokoll; Spec 06 (keine Kopie von Personendaten) bleibt unberührt | erscheint erneut auf einem neuen Gerät, in einem anderen Browser, nach dem Löschen der Website-Daten und in jedem privaten Fenster |
| wirkt offline und ohne Verzögerung | keine Übersicht, wer die Einführung gesehen hat (gewollt: kein Tracking) |
| ein geteiltes Gerät (Büro-PC) trennt die Benutzer über die Kennung | auf einem geteilten Gerät sieht man am Speicher, dass *ein* Benutzer den Browser genutzt hat (nur als Hash; ohne `sub` nicht zuzuordnen) |
| derselbe Mechanismus wie das Farbschema (tech.md) | bestehende Benutzer sehen die Einführung einmal je Browser (E12) |

**Alternative serverseitig (für später, falls das Wiedererscheinen stört):**
- (a) Benutzerattribut in Keycloak (z. B. `archiv_einfuehrung`), geschrieben über das Dienstkonto `archiv-verwaltung`
  (Spec 06, ADR-015) hinter einem neuen Endpunkt `PUT /api/v1/ich/einfuehrung`. Nachteile: Admin-API-Aufruf je Benutzer,
  Änderung am User Profile des Realms (Spec 06, 3.17), das Dienstkonto schreibt dann auch ohne Verwaltungsrecht des
  Akteurs; braucht eine Ergänzung von Spec 06, 5.2 und dem Bedrohungsmodell.
- (b) Tabelle im Mandantenschema (`sub`, Version, Art, Zeit) mit Endpunkt: neues Changeset, Daten zu Personen unter der
  Aufbewahrung und dem Datenschutz der Spec 06 («Löschen und Datenschutz»).
- (c) «Neu» aus dem Benutzerverzeichnis ableiten (Anlass `ANLAGE` nach Spec 06, 6.3, z. B. in den letzten 30 Tagen): ohne
  Schreibzugriff, erfasst aber weder «übersprungen» noch Benutzer aus der Übernahme.
Empfehlung: Browser jetzt; (a) erst prüfen, wenn Spec 06 umgesetzt ist und Fabian das Wiedererscheinen als störend meldet.

## Schritte der Einführung (Requirement 2)
Texte in `texte.de-CH.ts` (`einfuehrung.schritte`); `{name}` = Name aus dem Branding, `{rolle}` = Bezeichnung der Rolle
aus `texte.kopf.rollen`. Jeder Schritt höchstens drei Sätze und ein Link «In der Anleitung lesen», der die Hilfe-Leiste
mit dem genannten Abschnitt öffnet. Die Bedingung prüft `AnmeldungService.darf` wie das Menü.

| Nr. | Titel | Entwurf des Textes | Bedingung | Abschnitt |
| --- | --- | --- | --- | --- |
| 1 | Willkommen im {name} | «Hier legen Sie Dokumente einmal ab und finden sie danach in Sekunden wieder, immer mit Quelle. Sie sind als ‹{rolle}› angemeldet; die App zeigt Ihnen nur die Bereiche, die Ihre Rolle nutzen darf.» | immer | `erste-schritte` |
| 2 | Dokumente erfassen | «Unter ‹Erfassen› fotografieren Sie Papier mit dem Handy, auch mehrseitig, oder laden Dateien hoch. Ohne Netz wartet die Aufnahme auf dem Gerät und wird hochgeladen, sobald wieder eine Verbindung besteht.» | A1 | `erfassen` |
| 3 (mit A7) | Prüfen und bestätigen | «Neue Dokumente kommen in den ‹Eingangskorb›. Das Archiv schlägt Art und Angaben vor; Sie prüfen den Vorschlag, korrigieren ihn wo nötig und bestätigen.» | A5 und A7 | `eingangskorb` |
| 3 (ohne A7) | Ihre erfassten Dokumente | «Im ‹Eingangskorb› sehen Sie die Dokumente, die Sie erfasst haben, mit dem Vorschlag des Archivs. Bestätigt werden sie von einer Person mit diesem Recht.» | A5 ohne A7 | `eingangskorb` |
| 4 | Finden | «Mit der ‹Suche› finden Sie Dokumente über Wörter, Belegnummern oder Partner. Im ‹Chat› fragen Sie in eigenen Worten; jede Antwort nennt ihre Quelle.» Mit A15 zusätzlich: «Unter ‹Wissen› finden Sie geprüftes Wissen aus Ihren Unterlagen.» | A2 | `suche` |
| 5 | Bestand und Abschluss | Liste der erlaubten Menüpunkte unter Kontakte, Importe, Jahresexport und Verwaltung, je mit der Beschreibung der Kachel aus `texte.start.beschreibung` (eine Quelle für beide) | A6, A12, `EXPORT`, A11, A13 oder A14 | der erste erlaubte |
| 6 | Hilfe finden | «Über ‹Hilfe› oben rechts öffnen Sie jederzeit die Anleitung zur Seite, auf der Sie gerade sind. Dort können Sie auch diese Einführung erneut ansehen.» | immer | `erste-schritte` |

Die Texte sind Entwürfe; der endgültige Wortlaut entsteht in Aufgabe 23.2 und folgt Requirement 9.3. Aussagen über das
Verhalten (z. B. «bestätigen») müssen der umgesetzten App entsprechen (Requirement 6.4).

## Hilfe-Katalog (Requirements 5, 6)
`frontend/src/app/hilfe/hilfe-katalog.ts`, eine Liste in Lesereihenfolge. Felder: `anker`, `titel` (aus `texte.de-CH.ts`),
`datei` (`/anleitung/<anker>.md`), `eltern` (Anker oder leer), `bedingung` (`{ eine: Aktion[]; ohne?: Aktion[] }` oder leer
= allgemein), `pfade` (Präfixe der App für die Kontext-Zuordnung), `abgemeldet` (auch ohne Anmeldung sichtbar).

| Anker | Titel | Eltern | Bedingung | Pfade | abgemeldet |
| --- | --- | --- | --- | --- | --- |
| `erste-schritte` | Erste Schritte | – | allgemein | `/` | ja |
| `erfassen` | Erfassen | – | A1 | `/erfassen` | – |
| `erfassen-handy` | Mit dem Handy fotografieren | `erfassen` | A1 | – | – |
| `erfassen-dateien` | Dateien hochladen | `erfassen` | A1 | – | – |
| `erfassen-offline` | Ohne Netz erfassen | `erfassen` | A1 | – | ja |
| `eingangskorb` | Eingangskorb | – | A5 | `/eingangskorb` | – |
| `eingangskorb-bestaetigen` | Prüfen, korrigieren und bestätigen | `eingangskorb` | A7 | – | – |
| `eingangskorb-eigene` | Ihre erfassten Dokumente verfolgen | `eingangskorb` | A5 ohne A7 | – | – |
| `suche` | Suche | – | A2 | `/suche` | – |
| `chat` | Chat | – | A2 | `/chat` | – |
| `dokument` | Dokumentansicht | – | A2 | `/dokument` | – |
| `dokument-aendern` | Angaben ändern | `dokument` | A6 | – | – |
| `kontakte` | Kontakte | – | A6 | `/kontakte` | – |
| `wissen` | Wissen | – | A15 | `/wissen` | – |
| `wissen-entwuerfe` | Wissensentwürfe | `wissen` | A17 | `/wissen/entwuerfe` | – |
| `importe` | Importe | – | A12 | `/importe` | – |
| `jahresexport` | Jahresexport | – | `EXPORT` | `/export` | – |
| `verwaltung` | Verwaltung | – | A11, A13 oder A14 | – | – |
| `verwaltung-dokumentarten` | Dokumentarten | `verwaltung` | A11 | `/verwaltung/dokumentarten` | – |
| `verwaltung-protokoll` | Zugriffsprotokoll | `verwaltung` | A13 | `/verwaltung/protokoll` | – |
| `verwaltung-api-tokens` | API-Tokens | `verwaltung` | A14 | `/verwaltung/api-tokens` | – |
| `probleme` | Wenn etwas nicht klappt | – | allgemein | `/nicht-gefunden` | ja |

- Ein Unterabschnitt erscheint nur, wenn auch sein Abschnitt erscheint. Ein Abschnitt mit Unterabschnitten hat eine
  eigene, kurze Datei (Einleitung).
- Die Bedingungen entsprechen den Aktionen der Menüpunkte in `MENUE` (z. B. Kontakte A6, Wissensentwürfe A17), damit
  Menü, Einführung und Anleitung dasselbe zeigen; `hilfe-katalog.spec.ts` vergleicht beide für jeden Pfad, den beide
  kennen.
- Neue Seiten bringen ihren Eintrag mit (Pflegeregel). Für Spec 06 sind das voraussichtlich `verwaltung-einstellungen`
  (A20, A19 oder A11) und `verwaltung-benutzer` (A21); das Frontend kennt A20 bis A23 erst mit Spec 06, Aufgabe 20.5 bzw.
  20.6, darum stehen sie hier nicht.
- Inhalt des Abschnitts `probleme` (Entwurf): Anmeldung abgelaufen, «Nicht gefunden», Upload fehlerhaft, keine Rolle
  zugeordnet, Seite veraltet («Neue Version verfügbar»); Ansprechperson neutral «die Person, die Ihren Zugang
  eingerichtet hat», weil `vhf-gsk` keinen Inhaber hat (Spec 06, Open Points, entschieden 20.11.2026).

## Markdown-Teilmenge und Darstellung (Requirement 7)
**Befund Wissensseite:** Das Frontend hat heute keinen Markdown-Renderer. Die Wissensbasis wandelt Markdown im Backend mit
commonmark-java um (`wissen/domain/WissensMarkdown.java`, Abhängigkeit `org.commonmark` 0.32.0 in `backend/pom.xml`) und
liefert `inhaltHtml`. Die Wissensseite liest dieses HTML mit `DOMParser` in einen Baum aus erlaubten Elementen
(`wissen/wissen-text.ts`, Typ `Textteil`, Positivliste der Elemente, Links nur auf Wissensseiten) und stellt ihn mit einem
rekursiven `ng-template` nur über Interpolation dar (`wissen-seite.html`, `textbaum`). Wiederverwendbar sind also der Typ
`Textteil` und das Muster der Darstellung, nicht der Renderer selbst: Er läuft auf dem Server, braucht Anmeldung und Netz.

**Gewählt (E6):** `hilfe/anleitung-markdown.ts` setzt eine kleine Teilmenge direkt in `Textteil` um; die Komponente
`hilfe/anleitung-text.ts` stellt den Baum nach dem Muster von `wissen-seite.html` dar. Die Wissensseite bleibt unverändert;
`Textteil` wird aus `wissen/wissen-text.ts` importiert (eine Verschiebung nach `gemeinsam/` ist eine Auslegung der
Aufgabe 22.1).

| Markdown | Teil (`art`) | Darstellung |
| --- | --- | --- |
| `## `, `### `, `#### ` | `h2`, `h3`, `h4` | Überschrift mit `id` aus dem Anker (`#` der Stufe 1 ist der Titel aus dem Katalog und im Text nicht erlaubt) |
| Absatz (Zeilen bis Leerzeile) | `p` | Absatz |
| `- ` bzw. `1. ` (eine Ebene) | `ul` bzw. `ol` mit `li` | Liste |
| `**…**`, `*…*`, `` `…` `` | `strong`, `em`, `code` | wie im Text |
| `> **Hinweis:** …` bzw. `> **Achtung:** …` | `hinweis` (`info` bzw. `warnung`) | Baustein `.hinweis` mit Symbol |
| `[Text](/pfad)` | `link` mit `pfad` | `routerLink`, schliesst die Leiste |
| `[Text](#anker)` | `abschnitt` mit `anker` | wechselt den Inhalt der Leiste bzw. springt auf `/hilfe` |
| `{name}`, `{rolle}` | `text` | Name aus dem Branding bzw. Bezeichnung der Rolle |
| alles andere (HTML, Bilder, Tabellen, Codeblöcke, Fussnoten, andere Links) | `text` | wörtlich als Text; `anleitung-inhalt.spec.ts` meldet es als Fehler |

Der Parser arbeitet zeilenweise ohne reguläre Ausdrücke mit Rückverfolgung (kein ReDoS bei langen Zeilen) und begrenzt
eine Datei auf 32 KiB; darüber gilt der Fehlerfall 7.6.

## Hilfe-Leiste und Seite `/hilfe` (Requirements 4, 5)
- **Leiste:** zweiter `mat-sidenav` mit `position="end"` und `mode="over"` im bestehenden `mat-sidenav-container` von
  `app.html` (Fokusfalle, Escape und Abdunklung bringt Angular Material mit), `role="dialog"`, Name «Hilfe». Inhalt in
  `@defer (when hilfeOffen())`. Dienst `HilfeLeiste` (root) mit `oeffnen(anker?)`, `schliessen()`, `anker` als Signal; der
  Knopf in der Kopfzeile ruft `oeffnen()` mit dem Kontext, die Links «So funktioniert es» (Requirement 4.6) und «In der
  Anleitung lesen» (Einführung) mit festem Anker.
- **Kontext:** `kontext(url, rolle)` nimmt aus dem Katalog die Einträge mit `pfade`, wählt mit `aktiverPfad` den längsten
  passenden Pfad und gibt den Anker nur zurück, wenn der Eintrag für die Rolle sichtbar ist (Requirement 4.3).
- **Seite:** Route `hilfe` mit `loadComponent`, ohne `canActivate`, Titel über `titel(TEXTE.hilfe.titel)` wie die anderen
  Routen. Inhaltsverzeichnis als `nav` mit Links `routerLink="/hilfe" [fragment]="anker"`. Weil der Inhalt in
  `mat-sidenav-content` scrollt und nicht im Fenster, hilft `anchorScrolling` des Routers nicht: Die Seite hört auf
  `ActivatedRoute.fragment`, ruft `scrollIntoView({ block: 'start' })` auf der Überschrift auf und setzt den Fokus mit
  `focus({ preventScroll: true })` (Überschriften mit `tabindex="-1"`). Unbekannter oder verborgener Anker: Anfang der
  Seite, keine Meldung (Requirement 5.2).
- **Layout:** einspaltig bis etwa 1100 px, darüber Raster mit Inhaltsverzeichnis links (`position: sticky`); Abschnitte als
  `.karte`, Unterabschnitte als `h3` in der Karte ihres Abschnitts.
- **Abgemeldet:** dieselbe Leiste und Seite; der Filter lässt nur Einträge mit `abgemeldet: ja` durch und zeigt den
  Hinweis aus Requirement 6.3.

## Offline und Aktualisierung (Requirement 8)
Ergänzung von `frontend/ngsw-config.json` um eine Asset-Gruppe:
```json
{ "name": "anleitung", "installMode": "prefetch", "updateMode": "prefetch", "resources": { "files": ["/anleitung/**"] } }
```
- Der Service Worker führt die Dateien mit Hash im Manifest der App-Version; eine neue Version tauscht App und Anleitung
  gemeinsam (Requirement 8.2), angezeigt mit dem bestehenden Hinweis «Neue Version verfügbar» (`app.html`).
- Die Navigationsregel `!/**/*.*` lässt `/anleitung/*.md` nicht auf `index.html` fallen; Quinoa liefert die Datei aus
  dem Build (`quarkus.quinoa.build-dir`), die SPA-Weiterleitung greift nur für Pfade ohne Datei. Aufgabe 22.4 prüft den
  `Content-Type` (das Frontend liest mit `response.text()`, der Typ ist dafür gleichgültig) und dass `nosniff` nicht stört.
- Weil alle Dateien beim Installieren geladen werden, erfährt der Server nicht, welcher Abschnitt gelesen wird
  (Requirement 9.4).

## Fehlerbehandlung
| Fall | Verhalten | Requirement |
| --- | --- | --- |
| Datei der Anleitung nicht ladbar (kein Netz, kein Cache, HTTP-Fehler, grösser als 32 KiB) | `.hinweis gefahr` mit Text nach 7.6 und «Erneut versuchen» beim Abschnitt; andere Abschnitte bleiben | 7.6 |
| unbekanntes Markdown-Konstrukt | erscheint als Text; der Inhaltstest wird rot | 7.3 |
| Link auf fremdes Ziel | erscheint als Text | 7.4 |
| unbekannter oder verborgener Anker | Seite von oben bzw. Inhaltsverzeichnis in der Leiste, ohne Meldung | 4.3, 5.2 |
| `localStorage` gesperrt, voll oder beschädigt; `crypto.subtle` fehlt | Vermerk nur im Arbeitsspeicher; beschädigter Eintrag wird beim nächsten Schreiben ersetzt; keine Meldung | 1.5 |
| Branding noch nicht geladen oder fehlerhaft | Standardname wie heute (`BrandingService`) | 2.3 |
| kein Mandant oder keine Rolle | keine Einführung; Anleitung wie abgemeldet | 1.7, 6.3 |

Neue Fehlercodes der API gibt es nicht.

## Barrierefreiheit (Requirements 3.7, 4.5, 9.2)
- Einführung: `section` mit `aria-labelledby` auf die Überschrift des Schritts; «Schritt n von m» als Text; Knöpfe als
  `button`; Fokuswechsel nach jedem Schritt auf die Überschrift (`tabindex="-1"`); Abschluss und Überspringen melden über
  einen `role="status"`-Bereich; keine Bewegung ohne `prefers-reduced-motion: no-preference`.
- Kopfzeile: Knopf mit `aria-expanded`, `aria-controls` auf die Leiste und Namen «Hilfe» bzw. «Hilfe zu <Abschnitt>».
- Leiste: Dialog mit Namen, Fokusfalle, Escape, Fokusrückgabe; Überschrift `h2`.
- Seite: eine `h1` im Seitenkopf, Abschnitte `h2`, Unterabschnitte `h3`; Inhaltsverzeichnis mit `aria-current` für den
  zuletzt angesprungenen Abschnitt.

## Datenschutz und Sicherheit
- Keine Daten an Dritte: alle Dateien vom eigenen Ursprung, Schrift lokal (tech.md), keine Einbettungen, keine Zählung.
  Die Szenarien zu 9.4 zeichnen alle Anfragen auf und verlangen: nur eigener Ursprung, keine zusätzlichen `/api/`-Aufrufe.
- Die Dateien unter `/anleitung/` sind wie das JavaScript der App **ohne Anmeldung abrufbar**. Darum enthält die
  Anleitung nichts Vertrauliches: keine Adressen, Namen, Belegdaten, Zugänge, internen Hostnamen oder Betreiber-Wege
  (Requirement 9.5); das sind dieselben Angaben, die heute schon im Bündel der App stehen (Menü, Texte).
- Kein `innerHTML`, kein `bypassSecurityTrust…`, Links nur auf App-Pfade und Anker (Requirements 7.3, 7.4); damit bringt
  auch eine fehlerhafte oder manipulierte Datei kein Skript und keinen fremden Link in die App.
- Der Einführungsvermerk enthält keine Personendaten im Klartext (Requirement 1.3).
- Rechte: Die Oberfläche blendet nur aus. Wer einen verborgenen Abschnitt per Adresse lädt (`/anleitung/importe.md`),
  liest nur eine Beschreibung; jede Aktion prüft weiterhin die API (Spec 01, 2.6).

## Testing Strategy
- **Vitest** (Frontend, `ng test`): `hilfe-katalog.spec.ts` (Dateien, Anker, Bedingungen, Routen, Gleichlauf mit `MENUE`),
  `anleitung-markdown.spec.ts` (jede Zeile der Teilmenge, fremde Links, HTML als Text, Grenze 32 KiB, lange Zeilen ohne
  Zeitüberschreitung), `anleitung-inhalt.spec.ts` (alle Dateien: Teilmenge ohne Fehler, kein «ß», keine Anführungszeichen
  „…“ und "…", keine Muster von E-Mail, Telefon, IBAN, keine externen Links, Summe höchstens 200 KiB),
  `einfuehrung-vermerk.spec.ts` (Hash, 20 Einträge, Fehlerfälle), `einfuehrung-schritte.spec.ts` (vier Rollen × sechs
  Schritte gegen die Tabelle in Requirement 2.2), `hilfe-kontext.spec.ts` (Zuordnung und Sichtbarkeit).
- **Browser-Szenarien** (`@spec-07 @browser`, Cucumber.js mit Playwright gegen den Prod-Build und die Attrappen aus
  `frontend/e2e/hilfen/`): `einfuehrung.feature`, `hilfe-leiste.feature`, `hilfe-seite.feature`, `hilfe-offline.feature`
  (Service Worker wie in `erfassen.mjs`, dann `setOffline`), `hilfe-barrierefreiheit.feature` (axe wie in
  `wissen-import.mjs`, 375 und 1280 px, hell und dunkel), `hilfe-datenschutz.feature` (Aufzeichnung der Anfragen).
  Szenariogrundrisse mit den vier Rollen; `geschaeftsstelle` im Mandanten `vhf-gsk`.
- **Prüfung des Spec-Tags (Auftrag):** Kein Code begrenzt den Tag auf `@spec-01` bis `@spec-06`. `ReqAbdeckung` liest
  `@spec-(\d{2})` und ordnet über die Nummer am Anfang des Ordnernamens (`07-…`) zu; `SzenarienShards` trennt nur
  `@spec-01` (Shard 1) vom Rest (Shard 2), und `SzenarienShardTest` prüft den Fall `@spec-07` schon; `frontend/cucumber.mjs`
  filtert nur `@browser`. Nur der Text in `tech.md` nannte `@spec-01` bis `@spec-06`; er ist in diesem PR auf `@spec-07`
  erweitert. Aufgabe 23.5 belegt das mit dem ersten grünen Lauf, der Szenarien mit `@spec-07` enthält (Requirement 10.2).
- **Abnahme:** `docs/abnahme/hilfe.md` (Aufgabe 23.6).

## Folgeänderungen
| Wo | Änderung | Wann |
| --- | --- | --- |
| `.kiro/steering/tech.md`, «Tests und Qualität» | Spec-Tag `@spec-01` bis `@spec-07`; Definition of Done um die Pflegeregel ergänzt (Requirement 7.5) | in diesem PR |
| `.kiro/steering/tech.md`, «Konventionen Frontend» | Ausnahme: Anleitung als Markdown unter `frontend/public/anleitung/`, nicht in `texte.de-CH.ts` | in diesem PR |
| `.kiro/steering/structure.md` | Baum: Specs 06 und 07, `frontend/src/app/hilfe/`, `frontend/public/anleitung/`, `features/hilfe/` | in diesem PR |
| `docs/umsetzungsplan.md` | Zeile Spec 07, Wellen 22 und 23 | in diesem PR |
| `frontend/ngsw-config.json` | Asset-Gruppe `anleitung` | Aufgabe 22.4 |
| `frontend/src/app/gemeinsam/symbol.ts` | Symbol `hilfe` am Ende von `SYMBOLE` | Aufgabe 22.3 |
| `docs/oberflaeche/prototyp.html` | Ansichten Hilfe-Leiste, Seite `/hilfe` und Einführung mit erfundenen, gekennzeichneten Beispieldaten (Leitfaden, «Neue Seite ohne Vorbild») | Aufgaben 22.2, 22.3, 23.2 (nicht in diesem PR: eine HTML-Datei erzwingt den vollen CI-Lauf) |
| `docs/anleitung-geschaeftsstelle.md`, `docs/anleitung-verwaltung.md` | Bedienung in die App, Verweis auf `/hilfe` (Requirement 7.7) | Aufgabe 22.7 bzw. Spec 06, Aufgabe 21.5 |
| Spec 06 | keine Textänderung; die Pflegeregel in `tech.md` gilt auch für die Aufgaben 20.5 bis 20.7 und 21.5 (Abschnitte `verwaltung-einstellungen`, `verwaltung-benutzer`) | – |
| `.kiro/steering/product.md` | keine: die Tabelle «Phasen und Specs» führt nur die Phasen 0 bis 4, nicht die Specs 05 bis 07 | – |

## Abgelehnte Optionen
- **Tour mit Markierung von Elementen (Coachmarks) oder modaler Dialog:** bricht bei Gestaltungsänderungen, verdeckt auf
  375 px die Seite, schwer barrierefrei (Fokus, Vorlesen); abgelehnt.
- **Eigene Seite `/willkommen` mit Umleitung nach der Anmeldung:** stört das Ziel nach der Anmeldung (`ziel`, z. B. ein
  geteilter Link auf ein Dokument); abgelehnt.
- **Texte der Anleitung in `texte.de-CH.ts`:** lange Prosa in TypeScript, schlecht zu lesen und zu prüfen; abgelehnt
  (nur kurze Texte und die Schritte der Einführung stehen dort).
- **Anleitung unter `docs/`:** würde als «nur Spec» gelten und keinen Build auslösen; kein Build-Schritt darf `.md` aus
  `docs/` übernehmen (`NurSpecTest`); abgelehnt.
- **Anleitung als Wissensseiten (Spec 04):** braucht Wissensquellen, Freigabe und Rechte A15 bis A19, liegt je Mandant in
  der Datenbank und ist nicht offline; abgelehnt.
- **Endpunkt im Backend mit `WissensMarkdown`:** gleicher Renderer wie die Wissensseite, aber nur mit Netz und Anmeldung,
  offline nur nach einem ersten Abruf (`dataGroups`), Backend-Arbeit für statischen Text; abgelehnt.
- **Bibliothek `marked` (npm) plus `wissenText()`:** volles CommonMark, aber neue Abhängigkeit (Bündel, Dependabot,
  `docs/abhaengigkeiten.md`) und ein HTML-Umweg für eine kleine Teilmenge; abgelehnt, solange die Teilmenge genügt.
- **Markdown zur Bauzeit umwandeln (Skript im Build):** braucht denselben Parser plus einen Build-Schritt; abgelehnt.
- **Externer Hilfedienst, Chat-Widget oder Videos bei Dritten:** Daten an Dritte (Regel 6); abgelehnt.
- **Antworten der KI auf Bedienfragen:** nicht Teil der Spec; später möglich als Wissensquelle der Anleitung, dann mit
  eigener Spec.
- **Menüpunkt in der Seitenleiste:** siehe E3; abgelehnt.

## Umsetzung (Aufgabe 22.1, 21.11.2026)
Stand von PR #411; Schnittstellen für die Aufgaben 22.2 bis 22.7.
- **Katalog** (`hilfe/hilfe-katalog.ts`): `HILFE_KATALOG` mit `erste-schritte` und `probleme` (beide allgemein,
  `abgemeldet`), Typen `HilfeEintrag` und `Bedingung`, `bedingungErfuellt(bedingung, rolle)` (über `darf`, ohne
  Bedingung immer erfüllt; den Filter nach Rolle, Eltern und abgemeldet baut 22.2) und `hilfeEintrag(anker)`. Titel
  stehen in `TEXTE.hilfe.abschnitte` je Anker. `rechte.ts` exportiert dazu `AKTIONEN` (bekannte Aktionen für den Test).
- **Pfade** (`hilfe/app-pfade.ts`): `istAppPfad(pfad, routes)` verlangt genau eine Route (Ziel eines Links),
  `istAppPfadPraefix(pfad, routes)` eine Route oder ihren Anfang (`pfade` im Katalog, z. B. `/dokument`). `**` zählt nicht,
  Parameter passen auf jedes Segment, Segmente nur aus Kleinbuchstaben, Ziffern und Bindestrich: `//host`, `/a?b`,
  `/a#b` und `/a/` sind keine Pfade. Zur Laufzeit gilt `Router.config`, in den Tests `routes` aus `app.routes.ts`.
- **Teilmenge** (`hilfe/anleitung-markdown.ts`): `anleitungMarkdown(quelle, { istAppPfad, praefix? })` ergibt
  `{ teile, befunde }`. `Anleitungsteil` erweitert `Textteil` aus `wissen/wissen-text.ts` (nicht verschoben) um
  `anker` (Ziel von `abschnitt`, id einer Überschrift), `variante` (`info`, `warnung`) und `platzhalter`. Abweichend
  von der Tabelle oben werden `{name}` und `{rolle}` zu `platzhalter` statt `text`: Ersetzt wird erst beim Darstellen,
  damit ein Name aus dem Branding nie als Markdown gelesen wird. `befunde` nennt je Konstrukt ausserhalb der Teilmenge
  Zeile und Art (z. B. «Zeile 3: fremdes Ziel»); die Darstellung zeigt es trotzdem als Text, der Inhaltstest verlangt
  eine leere Liste. Überschriften erhalten mit `praefix` die id `<anker>-<titel>` (Umlaute als ae, oe, ue; Zähler bei
  Doppelten). Die Suche nach Schlusszeichen merkt sich ihr Ergebnis, die Arbeit bleibt linear (Test mit 32 KiB offener
  Klammern). Über 32 KiB (UTF-8) wirft der Parser `AnleitungZuGross`.
- **Lader** (`hilfe/anleitung-lader.ts`, root): `zustand(eintrag)` liefert ein Signal `laedt` | `fertig` | `fehler` und
  lädt jede Datei einmal je Sitzung über `HttpClient` (der Anmelde-Interceptor hängt nur an `/api/` ein Token an);
  `erneut(eintrag)` lädt nach einem Fehler neu. Fehler sind HTTP-Fehler, kein Netz ohne Cache, mehr als 32 KiB und eine
  Antwort mit `Content-Type: text/html` (Rückfallseite der SPA statt der Datei).
- **Darstellung** (`hilfe/anleitung-text.ts`, `app-anleitung-text`): Eingaben `eintrag`, `versatz` (Stufen, um die
  Überschriften der Datei tiefer stehen; auf `/hilfe` z. B. 1 unter dem `h2` des Abschnitts, 2 im Unterabschnitt) und
  `leiste` (Links auf Abschnitte navigieren nicht, `href` bleibt `/hilfe#<anker>`); Ausgaben `abschnitt` (Anker) und
  `navigiert` (Link auf eine Seite der App gewählt, die Leiste schliesst). Den Titel setzt die Seite bzw. die Leiste.
  Laden mit `mat-progress-bar`, Fehler als `.hinweis gefahr` mit `role="alert"` (`data-test="anleitung-fehler"`) und
  dem Knopf «Erneut versuchen». Der Hinweis-Block nutzt das neue Symbol `info` (am Ende von `SYMBOLE`, vor `hilfe` aus
  22.3). Zeigen Leiste und Seite denselben Abschnitt zugleich, entstehen doppelte ids der Überschriften; 22.3 sollte in
  der Leiste ohne `praefix` laden oder die Leiste auf `/hilfe` schliessen.
- **Inhaltsprüfung** (`anleitung-inhalt.spec.ts`): zusätzlich zur Tabelle der Teststrategie verbietet sie `http:`,
  `https:`, `mailto:`, `javascript:`, `data:` und `www.` auch als blossen Text sowie `{rolle}` in Abschnitten mit
  `abgemeldet` (ohne Anmeldung gibt es keine Rolle). Die Vitests lesen die Dateien ohne `@types/node` über
  `process.getBuiltinModule('node:fs')`, Arbeitsverzeichnis `frontend/`.
- **Nachweis:** 7.1 mit `features/hilfe/anleitung-pwa.feature` (Datei aus dem Prod-Build, nicht die Rückfallseite);
  7.6 ist umgesetzt und per Vitest geprüft (`anleitung-text.spec.ts`), sein Browser-Szenario braucht eine Seite und
  kommt mit 22.2 (tasks.md: 7.6 von 22.1 zu 22.2 verschoben).
- **Texte:** `erste-schritte.md` (Anmelden mit Code der Authenticator-App, Seitenleiste und Menü, Rolle in der
  Kopfzeile, Farbschema, Abmelden) und `probleme.md` (Sitzung abgelaufen, offline, Upload nicht hochgeladen, Seite nicht
  gefunden, kein Mandant oder keine Rolle, neue Version) als erste Fassung nur mit dem, was auf `main` umgesetzt ist;
  22.5 bzw. 22.7 vervollständigen sie (z. B. Knopf «Hilfe» und Verweise auf `#erfassen-offline`, sobald es sie gibt).

## Umsetzung (Aufgabe 22.2, 21.11.2026)
Stand des PR zu 22.2; Schnittstellen für 22.3, 23.3 und 23.4.
- **Filter** (`hilfe/hilfe-sichtbar.ts`): `sichtbareAbschnitte(katalog, rolle)` ergibt die Abschnitte mit ihren
  Unterabschnitten in Katalogreihenfolge, `sichtbareAnker(abschnitte)` alle Anker daraus. `rolle` ist nur gesetzt, wenn
  jemand mit Mandant und Rolle angemeldet ist (wie `AnmeldungService.darf`); sonst zählt nur `abgemeldet`. Mit Rolle
  erscheint ein Unterabschnitt nur mit seinem Abschnitt. Ohne Rolle steht ein Unterabschnitt mit `abgemeldet`, dessen
  Abschnitt verborgen ist (`erfassen-offline` ohne `erfassen`), als eigener Abschnitt an seiner Stelle: als `h2` mit
  eigener Karte und auf oberster Ebene im Inhaltsverzeichnis. Das Token `HILFE_EINTRAEGE` (Standard `HILFE_KATALOG`)
  macht den Katalog in Tests ersetzbar; 22.3 sollte Kontext und Sichtbarkeit über dieselben Funktionen bestimmen
  (Req 4.3: «nicht sichtbar» heisst nicht in `sichtbareAnker`).
- **Seite** (`hilfe/hilfe-seite.*`, Route `hilfe` mit `loadComponent`, ohne Wächter, Titel `titel(TEXTE.hilfe.titel)`):
  Seitenkopf (Vorzeile Name aus dem Branding, «Hilfe», fester Untertitel), darunter ein `.hinweis info` mit dem Satz
  zur Rolle (6.5) bzw. dem Hinweis nach 6.3, dann das Raster aus Inhaltsverzeichnis (`nav` mit sichtbarer Überschrift
  «Inhalt der Anleitung» über `aria-labelledby`) und Abschnitten als `.karte` (`section`, `h2` mit id = Anker,
  Unterabschnitte als `h3` in der Karte, alle Titel mit `tabindex="-1"`). Ab 1100 px zwei Spalten, das
  Inhaltsverzeichnis klebt (`position: sticky`, `top: 5rem` unter der Kopfzeile). Der Platz für «Einführung erneut
  zeigen» (23.3) ist rechts im Seitenkopf (Kommentar im Template), noch ohne Knopf.
- **Sprung:** Die Seite hört auf `ActivatedRoute.fragment` und springt erst, wenn keine Datei der sichtbaren Abschnitte
  mehr lädt (sonst verschöbe nachgeladener Text das Ziel); ein Fehler nach 7.6 zählt als fertig. Sprung mit
  `scrollIntoView({ block: 'start' })` und `focus({ preventScroll: true })`, `scroll-margin-top` hält die Überschrift
  unter der klebenden Kopfzeile frei. Unbekannter oder verborgener Anker: `mat-sidenav-content` nach oben, keine
  Meldung, kein `aria-current`. Ein Klick auf den Anker, der schon in der Adresse steht, navigiert im Router nicht; die
  Seite springt dann selbst. `aria-current="location"` markiert den zuletzt angesprungenen Eintrag.
- **Ids:** Überschriften der Dateien tragen `<anker>-<titel>`; ein Titel wie «Offline» in `erfassen.md` ergäbe die id
  des Unterabschnitts `erfassen-offline`. `hilfe-seite.spec.ts` prüft darum alle Dateien gegen die Anker des Katalogs.
- **Browser-Szenarien** (`features/hilfe/hilfe-seite-pwa.feature`, Schritte `e2e/schritte/hilfe-seite.mjs`): Weil die
  Anmeldung nur im Arbeitsspeicher liegt, öffnet der Schritt «ich in der App «…» aufrufe» eine Adresse mit
  `history.pushState` und `popstate` wie ein Link der App (auch für 22.3 und 23.4 nutzbar). Die Szenarien je Rolle
  prüfen Anfang und Ende des Inhaltsverzeichnisses und die Anker, die die Rolle nach der Tabelle «Hilfe-Katalog» nie
  sieht; sie bleiben gültig, während 22.5 bis 22.7 den Katalog füllen. Den Filter über den ganzen Katalog prüft
  `hilfe-sichtbar.spec.ts` gegen dieselbe Tabelle.
- **Prototyp:** Ansicht `#hilfe` in `docs/oberflaeche/prototyp.html` mit erfundenen, als «Beispiel» gekennzeichneten
  Texten; erreichbar nur über die Adresse, bis 22.3 den Knopf in der Kopfzeile bringt.

## Open Points
- [offen: Satz zur Webseite im Mandanten `vhf-gsk` (Requirement 2.4) – ob und wie Einführung und Anleitung erklären,
  welche bestätigten Dokumente auf der WordPress-Seite erscheinen und ab wann (Spec 03); Wortlaut von Fabian mit der
  Geschäftsstelle. Bis zum Entscheid sind die Texte für alle Mandanten gleich; ein Zusatz käme über die Konfiguration des
  Mandanten (Spec 05), nie über eine Abfrage der Kennung im Code.]
- Alle übrigen Punkte sind Vorschläge (Abschnitt «Entscheide») und gelten mit dem Merge dieses PR.
