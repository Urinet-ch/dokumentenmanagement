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

**Umsetzung (Aufgabe 23.1):**
- Zwei Dateien unter `frontend/src/app/hilfe/`: `einfuehrung-vermerk-speicher.ts` (Format, Kennung, Lesen, Ablegen,
  Datum; ohne Angular und ohne Importe, damit die Browser-Szenarien das Modul unverändert in Chromium laden können) und
  `einfuehrung-vermerk.ts` (Dienst `EinfuehrungVermerk`, root, liest Benutzer und Mandant aus `AnmeldungService.benutzer`).
- Schnittstelle für die Aufgaben 23.2 und 23.3: `bekannt: Signal<boolean | null>` – `false` heisst neuer Benutzer
  (Einführung zeigen), `true` Vermerk besteht, `null` kein angemeldeter Benutzer mit Mandant, `sub` und Rolle oder die
  Kennung ist noch nicht berechnet (`crypto.subtle.digest` ist asynchron; dann nichts zeigen, damit die Karte nicht
  aufblitzt). `ablegen(art: 'ABGESCHLOSSEN' | 'UEBERSPRUNGEN'): Promise<void>` setzt `bekannt` sofort auf `true`, ersetzt
  einen bestehenden Vermerk (Req 3.5) und tut ohne Benutzer mit Mandant und Rolle nichts. Ob `/?einfuehrung` die
  Einführung trotz Vermerk zeigt, entscheidet die Startseite (23.3), nicht der Dienst.
- Benutzerwechsel und Abmeldung: `bekannt` folgt dem Signal des angemeldeten Benutzers; nach der Abmeldung ist es `null`,
  der Vermerk bleibt im Browser. Ein Ergebnis für einen inzwischen abgemeldeten oder gewechselten Benutzer wird verworfen.
- Format: Ein Wert mit anderem `v` als 1, ohne Liste oder ohne gültiges JSON gilt als unlesbar (leer) und wird beim
  nächsten Schreiben ersetzt; einzelne unlesbare Einträge (Kennung nicht 64 Zeichen hex, Version keine ganze Zahl ≥ 1,
  unbekannte Art, Datum nicht `JJJJ-MM-TT`) fallen weg, zusätzliche Felder werden nicht übernommen. Jede Version ≥ 1 gilt
  als Vermerk. «Ältester» ist der vorderste Eintrag der Liste (Reihenfolge des Schreibens, nicht `am`, das nur den Tag
  kennt); ein ersetzter Vermerk rückt als neuester ans Ende. `am` ist das Kalenderdatum in Europe/Zurich.
- Arbeitsspeicher: Der Dienst merkt sich jeden Vermerk dieser Sitzung unter `<mandant>:<sub>` (nur im Arbeitsspeicher,
  nie gespeichert), nicht nur bei einem Fehler des Speichers. Ohne `crypto.subtle` liest und schreibt er den
  `localStorage` nicht.
- Nachweis: Vitest `einfuehrung-vermerk.spec.ts` (Hash, 20 Einträge, Ersetzen, Benutzerwechsel, Abmeldung, gesperrter,
  voller und beschädigter Speicher, ohne `crypto.subtle`, keine Anfrage); Browser-Szenarien
  `features/hilfe/einfuehrung-vermerk.feature` (Req 1.3–1.5) prüfen das Modul im echten Browser auf dem Ursprung der App.
  Abschliessen und Überspringen über die Karte belegen die Szenarien zu Req 3.3 und 3.4 (Aufgabe 23.3).

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
| `chat-bewerten` | Antworten bewerten | `chat` | A2 | – | – |
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
  `@defer (when hilfeOffen())`. Dienst `HilfeLeiste` (root) mit `oeffnen(anker, ausloeser)`, `schliessen()`, `anker` als Signal; der
  Knopf in der Kopfzeile ruft `oeffnen()` mit dem Kontext, die Links «So funktioniert es» (Requirement 4.6) und «In der
  Anleitung lesen» (Einführung) mit festem Anker.
- **Kontext:** `hilfeKontext(url, rolle, katalog)` nimmt aus dem Katalog die Einträge mit `pfade`, wählt mit `aktiverPfad` den längsten
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
  dem Build (`quarkus.quinoa.build-dir`); für eine fehlende Datei kann Quinoa die SPA als HTML zurückgeben. Der Lader
  erkennt HTML als Fehler. Aufgabe 22.4 prüft die Auslieferung der vorhandenen Dateien mit dem Service Worker.
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

## Umsetzung (Aufgabe 22.5, 21.11.2026)
Texte zu Erste Schritte, Erfassen, Eingangskorb und Dokumentansicht; beschrieben ist nur, was auf `main` umgesetzt ist
(Requirement 6.4), mit dem Wortlaut der Knöpfe und Meldungen aus `texte.de-CH.ts`.
- **Katalog:** Einträge `erfassen`, `erfassen-handy`, `erfassen-dateien`, `erfassen-offline` (`abgemeldet`),
  `eingangskorb`, `eingangskorb-bestaetigen` (A7), `eingangskorb-eigene` (A5 ohne A7), `dokument`, `dokument-aendern`
  (A6) wie in der Tabelle «Hilfe-Katalog», in zwei Blöcken: Erfassen und Eingangskorb nach `erste-schritte`,
  Dokumentansicht zwischen `chat` und `kontakte` aus 22.6 (nach dem Merge von #423 eingeordnet). Titel in `TEXTE.hilfe.abschnitte`.
- **Prüfung** (`anleitung-abschnitte.spec.ts`): (1) Der Katalog folgt der Tabelle «Hilfe-Katalog» (Reihenfolge, Titel,
  Abschnitt, Bedingung, Pfade, `abgemeldet`); die Tabelle steht im Test, damit auch 22.6 und 22.7 gegen sie laufen.
  (2) Ein Link auf einen Abschnitt (`#anker`) zeigt nur auf Einträge, die jede Rolle und der Besucher ohne Anmeldung
  sieht, wenn sie den verweisenden Eintrag sehen (Requirement 6.2, 6.3; Unterabschnitt nur mit seinem Abschnitt).
  Darum verweisen `eingangskorb.md` nicht auf seine beiden Unterabschnitte, `dokument.md` nicht auf `dokument-aendern`
  und `erste-schritte.md` sowie `erfassen-offline.md` nur auf allgemeine Abschnitte. (3) Jeder Name in Guillemets in
  den Dateien der Aufgabe steht wörtlich in `texte.de-CH.ts` (Requirement 6.6, 9.3); Ausnahme sind die Menüs der
  Browser für «Startbildschirm» in `erfassen-handy.md`.
- **Befunde aus dem Code**, die die Texte bestimmen: Die Kamera öffnet erst mit «Kamera öffnen» (Requirement 4.2 sagt
  «beim Wählen von Erfassen»; die Anleitung beschreibt den Knopf). Neue Aufnahmen kommen immer ans Ende, Seiten lassen
  sich nicht umsortieren. Hochgeladen wird nur bei offener App (kein Background Sync). Aufnahmen ohne Anmeldung übernimmt
  die nächste Anmeldung mit A1 auf dem Gerät (`WarteschlangeService.zuordnen`). Der Entwurf im Eingangskorb entsteht nur,
  wenn die Sitzung beim Bestätigen abläuft (`sessionStorage`). «Metadaten bearbeiten» gibt es in den Zuständen
  `EINGANGSKORB`, `FEHLER` und `ARCHIVIERT`. Die Anmeldeseite bietet kein Zurücksetzen des Passworts
  (`resetPasswordAllowed: false`).
- **Für 22.7:** Die Umsetzung von 22.1 nennt Verweise auf `#erfassen-offline` in `erste-schritte.md` und
  `probleme.md`. Nach (2) geht das nicht: Ohne Anmeldung sehen alle drei Einträge, angemeldet sieht der Treuhänder aber
  `erste-schritte` und `probleme`, nicht `erfassen-offline` (A1). `erste-schritte.md` nennt darum nur den Link «Ohne
  Verbindung erfassen» der Startseite; den Weg zu `#erfassen-offline` bringen die Links «So funktioniert es»
  (Requirement 4.6, Aufgabe 23.4). `probleme.md` bleibt bei 22.7.
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

## Umsetzung (Aufgabe 22.3, 21.11.2026)
- **Kopfzeile:** «Hilfe» steht neben dem Farbschema-Knopf, für alle Besucher und mit zugänglichem Namen. Unter 600 px
  bleibt nur das Symbol sichtbar. Das neue Symbol `hilfe` wurde am Ende von `SYMBOLE` ergänzt.
- **Leiste:** zweiter `mat-sidenav` am Ende des bestehenden Rahmens, als Overlay mit maximal 420 px Breite ab 960 px und
  voller Breite auf schmalen Geräten. Inhalt wird erst beim ersten Öffnen mit `@defer` geladen. Der Dialog erhält Fokus
  auf der Überschrift, Escape und die Material-Fokusfalle bleiben aktiv; beim Schliessen kehrt der Fokus zum Auslöser
  zurück. Jede App-Navigation schliesst die Leiste.
- **Kontext und Sichtbarkeit:** `hilfeKontext` nutzt `aktiverPfad`, wählt den längsten zugeordneten Pfad und gibt einen
  Anker nur zurück, wenn er in `sichtbareAnker(sichtbareAbschnitte(...))` liegt. Ohne passenden oder sichtbaren Kontext
  zeigt die Leiste das gefilterte Inhaltsverzeichnis. Rollen werden nur bei angemeldetem Benutzer mit Mandant und Rolle
  gesetzt. Im `/hilfe`-Kontext gibt es absichtlich keinen zugeordneten Pfad; beim Anzeigen von Anleitungstext in der
  Leiste entfallen dessen Überschriften-IDs, damit keine doppelten IDs mit der ganzen Anleitung entstehen.
- **Navigation:** Abschnittslinks in der Leiste wechseln den Inhalt ohne Navigation. Links zu App-Seiten schliessen die
  Leiste; «Ganze Anleitung öffnen» führt zu `/hilfe#<anker>`. Die Seite darunter wird nicht neu erzeugt und ihre
  ungespeicherten Eingaben bleiben erhalten.
- **Abweichungen vom Entwurf:** Der Dienst heisst `HilfeLeiste` mit `oeffnen(anker, ausloeser)` (der Auslöser gibt den
  Fokus beim Schliessen zurück), `offen()` statt `hilfeOffen()` und der gemeinsamen `rolle()` für Kontext und
  Inhaltsverzeichnis. Der Kontext heisst `hilfeKontext(url, rolle, katalog)`. Der Knopf trägt den festen zugänglichen
  Namen «Hilfe» (nicht «Hilfe zu <Abschnitt>»); der Abschnitt zeigt sich in der geöffneten Leiste.
- **Pflege und Nachweis:** `erste-schritte.md` und `probleme.md` beschreiben den Kopfzeilenknopf. `hilfe-kontext.spec.ts`
  prüft längste Pfade und verborgene Abschnitte, `hilfe-leiste.spec.ts` den Dienst (Fokusrückgabe, Rolle); Browser-Szenarien zu 4.1–4.5 (inkl. Leistenbreite und angemeldet ohne Rolle) liegen in
  `features/hilfe/hilfe-leiste.feature`.

## Umsetzung (Aufgabe 22.6, 21.11.2026)
Abschnitte `suche`, `chat`, `kontakte`, `wissen` und `wissen-entwuerfe` mit Dateien unter `frontend/public/anleitung/`,
Einträgen im Katalog (Bedingungen wie im Menü: A2, A2, A6, A15, A17) und Titeln in `TEXTE.hilfe.abschnitte`. Beschrieben
ist nur, was die Seiten auf `main` zeigen (Requirement 6.4), mit den Namen aus `texte.de-CH.ts`.
- **Reihenfolge:** Die Einträge stehen vor `probleme` in der Reihenfolge von Requirement 6.1; `dokument` (Aufgabe 22.5)
  gehört zwischen `chat` und `kontakte`.
- **Pfade, abweichend von der Tabelle «Hilfe-Katalog»:** `wissen` trägt zusätzlich `/wissen/entwuerfe/meine` und
  `/wissensbasis`. Sonst ordnete die Hilfe-Leiste «Meine Analysevorschläge» (A18) dem Unterabschnitt `wissen-entwuerfe`
  (A17) zu, und `mitarbeiter` sähe nur das Inhaltsverzeichnis (Requirement 4.3); «Einstellungen und Export» (A19) hätte
  keinen Kontext. Beide Seiten beschreibt `wissen.md`. Den Gleichlauf mit `MENUE` berührt das nicht, weil das Menü diese
  Pfade nicht kennt; `hilfe-katalog.spec.ts` prüft die Zuordnung.
- **Rechte innerhalb eines Abschnitts:** Für A12 (Kontakte pflegen), A18 und A19 gibt es keinen Unterabschnitt. Die Texte
  nennen solche Teile bedingt («Steht unter einer Antwort der Knopf …», «Sehen Sie den Knopf …») oder, bei den Kontakten,
  wie die Einleitung der Seite («kann nur der Inhaber»), ohne eigene Liste der Rechte (Requirement 6.5, 6.6).
- **Chat:** keine Beispielfragen aus `product.md` (Requirement 9.5), sondern erfundene mit «Muster AG». Weil
  `kennzahl` und `offene_posten` von den Finanzfunktionen des Mandanten und `vollstaendigkeit` von der Rolle abhängen
  (Spec 02, 3.1, 4.2), nennt der Text diese Auswertungen mit «je nach Ihrer Rolle und den Funktionen Ihres Archivs»; die
  Texte bleiben für alle Mandanten gleich (Requirement 2.4).
- **Nicht beschrieben, weil die Oberfläche es nicht anbietet:** Ingest erneut starten (A16), Seite von Hand anlegen
  (Spec 04, 6.9), Lint starten und «Quelle prüfen» bestätigen (Spec 04, 15.2), Löschen der Gespräche nach 12 Monaten.
- **Verweise** nur auf Anker, die der Katalog auf `main` schon kennt (`#suche`, `#chat`, `#wissen`); einen Verweis auf
  `#dokument` kann 22.5 oder ein späterer PR ergänzen.
- **Befund:** Die Meldung `TEXTE.chat.zuViele` duzt («Du hast in dieser Stunde …»), die übrige App siezt
  (Requirement 9.3). Die Anleitung umschreibt die Meldung, statt sie zu zitieren; die Meldung selbst ändert 22.6 nicht.
- **Nachweis:** `hilfe-katalog.spec.ts` (Reihenfolge, Bedingungen und Eltern; Sichtbarkeit je Rolle nach Requirement 2.2
  und Spec 04, 12.1; Zuordnung der Wissenspfade), `anleitung-inhalt.spec.ts` für die neuen Dateien und
  `features/hilfe/anleitung-suche-wissen.feature` (`@req-6.1`: jede Datei kommt mit dem Prod-Build, wie 22.5); 6.4 und
  6.6 per Review (Nachweis: Dokument).

## Umsetzung (Aufgabe 22.7, 21.11.2026)
- **Katalog:** Block «Bestand und Verwaltung» vor `probleme`: `importe` (A12, `/importe`, deckt auch `/importe/:id`),
  `jahresexport` (`EXPORT`, `/export`), `verwaltung` (A11, A13 oder A14, ohne Pfad, kurze Einleitung) mit
  `verwaltung-dokumentarten` (A11), `verwaltung-protokoll` (A13), `verwaltung-api-tokens` (A14), Bedingungen und Pfade wie
  in `MENUE`; Titel in `TEXTE.hilfe.abschnitte`. `hilfe-katalog.spec.ts` prüft zusätzlich die Reihenfolge der vorhandenen
  Abschnitte nach Requirement 6.1 (Teilfolge, weil 22.5 und 22.6 ihre Abschnitte selbst bringen), dass ein Unterabschnitt
  direkt im Block seines Abschnitts steht und `<eltern>-` im Anker trägt, sowie die Einträge dieses Blocks. Vollständig
  nach 6.1 ist der Katalog, wenn 22.5, 22.6 und 22.7 auf `main` liegen; wer zuletzt merged, kann die Teilfolge zur
  Gleichheit verschärfen.
- **Texte nach dem Code, nicht nach dem Prototyp:** Die Abschnitte nennen Menüpunkte, Knöpfe und Meldungen aus
  `texte.de-CH.ts` und beschreiben nur, was die Seiten tun. Befunde dabei: Die Seite «API-Tokens» stellt keine Tokens aus
  (nur Ablaufdatum und Widerruf); die Anleitung verweist für neue Tokens auf die Unterlagen des Betriebs (README,
  «API-Token für Skripte»). Der Jahresexport meldet sich per E-Mail an den Empfänger der Benachrichtigungen des Mandanten
  (`archiv.benachrichtigung.empfaenger.<mandant>`), nicht an die anfordernde Person, und die Liste zeigt die 20 neuesten
  Exporte des Mandanten; die Anleitung sagt das so, obwohl die Seite nach dem Anfordern «Sie erhalten eine E-Mail …»
  meldet. Verweise innerhalb der Anleitung gehen auf Seiten der App (`/kontakte`, `/verwaltung/protokoll`,
  `/verwaltung/api-tokens`), nicht auf Anker anderer Aufgaben, damit die Dateien ohne 22.5 und 22.6 gültig sind.
- **`probleme`:** ergänzt um Meldungen, die mehrere Bereiche teilen (`httpFehler`: keine Verbindung, nicht erreichbar,
  fehlendes Recht, Daten inzwischen geändert, zu viele Anfragen) und den Hinweis zur nicht ladbaren Anleitung (7.6);
  Ansprechperson neutral. Knopf «Hilfe» (22.3) und Verweise auf `#erfassen-offline` (22.5) fehlen weiter, weil beides noch
  nicht auf `main` liegt (6.4); die Aufgabe, die zuletzt merged, ergänzt sie.
- **`docs/` nach E10:** `docs/anleitung-geschaeftsstelle.md` behält Zugang und Kontaktstelle, die Angaben der
  Dokumentarten des Mandanten `vhf-gsk` und die Regeln für die Webseite (Zugriffsstufe, Veröffentlichen, Zurückziehen),
  weil die App-Texte für alle Mandanten gleich sind und ein Satz zur Webseite nach dem offenen Punkt 2.4 nicht in die App
  kommt; die Bedienung (Anmelden, Erfassen, Eingangskorb, Suche, allgemeine Meldungen) verweist auf `/hilfe#…`.
  `docs/anleitung-verwaltung.md` bleibt Entwurf von Spec 06 mit einem Verweis auf `/hilfe#verwaltung`.
  Betreiber-Anleitungen (`docs/wiederherstellung.md`, `docs/notfall/`, `docs/verfahren/`, README) bleiben unverändert.
- **Pflegeregel (7.5):** `tech.md` (Definition of Done) nennt die Regel seit dem Merge von Spec 07 «für jeden Bereich,
  sobald sein Abschnitt auf `main` liegt, nach Spec 07, Aufgabe 22.7 für alle»; mit dieser Aufgabe greift sie für
  Importe, Jahresexport und Verwaltung, für die übrigen Bereiche mit 22.5 und 22.6.
- **Nachweis:** 6.1 mit `features/hilfe/anleitung-bestand-verwaltung.feature` (`@req-6.1`, `@req-7.1`, Dateien aus dem
  Prod-Build) und `hilfe-katalog.spec.ts` (Reihenfolge); 6.4, 6.6, 7.5 und 7.7 Nachweis: Dokument (Review des PR).

## Umsetzung (Aufgabe 23.2, 21.11.2026)
- `EinfuehrungVermerk.bekannt` steuert die Karte; solange die Kennung berechnet wird oder Rolle und Mandant fehlen,
  erscheint sie nicht. Die Kacheln bleiben darunter bedienbar. Name und optionales Logo kommen aus dem Branding.
- `einfuehrungSchritte` erstellt die Schritte aus denselben Rechten wie das Menü; der fünfte Schritt verwendet die
  Beschreibungen der vorhandenen Kacheln. Vitest prüft Reihenfolge und erlaubte Bereiche aller vier Rollen.
- Die Karte zeigt vorerst den ersten Schritt. Wechsel, Abschluss, Überspringen und erneuter Aufruf folgen in Aufgabe
  23.3. `einfuehrung-start.feature` prüft die Karte im Prod-Build und die unveränderte Schrittberechnung im Browser
  für alle Rollen; die Bedienung und Anzeige der weiteren Schritte prüft 23.3.

## Umsetzung (Aufgabe 22.4, 21.11.2026)
- Die eigene Asset-Gruppe `anleitung` lädt alle Dateien vorab und gehört zum gehashten Manifest der App. Der bestehende
  Aktualisierungshinweis aktiviert die neue Version; der Browsertest prüft alte und neue App und Anleitung vor und nach
  der Aktivierung mit zwei synthetischen Versionen und einem lokalen Test-Proxy.
- Auf `/erfassen` zeigt die Hilfe-Leiste ohne Anmeldung und ohne Netz `erfassen-offline`; sonst bleiben die bestehenden
  Kontext- und Rechteprüfungen unverändert. Die Anleitung beschreibt den Knopf «Hilfe» an dieser Stelle.
- Nachweise: `hilfe-kontext.spec.ts` und `hilfe-offline.feature` (`@req-8.1` bis `@req-8.3`), jeweils mit echtem
  Service Worker und vorab geladener Anleitung; der angemeldete Offline-Test prüft alle sichtbaren Abschnittstexte.

## Open Points
- [offen: Satz zur Webseite im Mandanten `vhf-gsk` (Requirement 2.4) – ob und wie Einführung und Anleitung erklären,
  welche bestätigten Dokumente auf der WordPress-Seite erscheinen und ab wann (Spec 03); Wortlaut von Fabian mit der
  Geschäftsstelle. Bis zum Entscheid sind die Texte für alle Mandanten gleich; ein Zusatz käme über die Konfiguration des
  Mandanten (Spec 05), nie über eine Abfrage der Kennung im Code.]
- Alle übrigen Punkte sind Vorschläge (Abschnitt «Entscheide») und gelten mit dem Merge dieses PR.

## Umsetzung (Aufgabe 23.3, 22.11.2026)
- Die Karte zeigt Fortschritt und Schritte, einschliesslich der für die Rolle erlaubten Bereiche. Der aktuelle Schritt
  und ein erzwungener Wiederaufruf bleiben im Dienst nur im Arbeitsspeicher und sind an Mandant und Benutzer gebunden;
  ein Neuladen setzt den Schritt zurück. Abschluss und Überspringen ersetzen den lokalen Vermerk.
- «In der Anleitung lesen» öffnet die Hilfe-Leiste am Anker des Schritts, ohne Navigation. Auf `/hilfe` führt
  «Einführung erneut zeigen» für angemeldete Benutzer mit Rolle und Mandant zu `/?einfuehrung=`; der Router behandelt
  den leeren Parameter wie `/?einfuehrung`. Die Karte bleibt auch für Benutzer mit bestehendem Vermerk sichtbar.
- Der Wiederaufruf-Parameter wird je Benutzer nur einmal pro Seitenladung ausgewertet; ein neuer Klick in der Anleitung
  startet ausdrücklich erneut. Dadurch setzt die Rückkehr über den Browserverlauf den Schritt nicht zurück. Nach einem
  Neuladen und der dann erforderlichen erneuten Anmeldung bleibt der Wiederaufruf als Anmeldeziel erhalten.
- Die Schrittüberschrift erhält beim Wechsel den Fokus, nach Abschluss oder Überspringen die Seitenüberschrift;
  eine sichtbare Statusmeldung meldet das Ergebnis. Die Karte verwendet keine Übergangsanimation.
- Nachweise: `einfuehrung-start.feature` für 3.2–3.7 und 5.5, `einfuehrung-vermerk.spec.ts` für den flüchtigen
  Schrittstand und die Trennung nach Benutzer, `hilfe-seite.spec.ts` für den rollenabhängigen Wiederaufruf.

## Umsetzung (Aufgabe 23.4, 22.11.2026)
- Die Links bei Warteschlange, fehlgeschlagenen Uploads und auf der Offline-Anmeldekarte öffnen `erfassen-offline`,
  der Link bei fehlendem Mandant oder fehlender Rolle öffnet `probleme`. Die vorhandenen Katalogeinträge bleiben
  unverändert: Beide sind bereits für die jeweiligen Besucher sichtbar. Die Hilfe-Leiste erhält den auslösenden Link
  für die Fokusrückgabe; der normale Klick navigiert nicht.
- Fehler bei Datei-Uploads umfassen sowohl die ganze Anfrage als auch abgelehnte Einzelergebnisse. Die Anleitung
  unterscheidet diese Fälle vom automatischen Wiederholen der Warteschlange.
- Nachweise: `hilfe-kontextlinks.feature` (`@req-4.6`) für alle vier Stellen sowie fehlenden Mandanten und fehlende
  Rolle, einschliesslich Fokusrückgabe und erhaltener Aufnahme beziehungsweise Dateiauswahl; Angular-Tests für die
  Zustände der Komponenten.

## Umsetzung (Aufgabe 23.5, 22.11.2026)
- `hilfe-qualitaet.feature` prüft Einführungsschritte, ganze Anleitung und jeden sichtbaren Abschnitt der Hilfe-Leiste
  mit axe auf dem ganzen Dokument, für alle vier Rollen und ohne Anmeldung, bei 375 und 1280 px in hell und dunkel.
  Sie prüft auch den waagrechten Bildlauf und zeichnet nach der Anmeldung beziehungsweise dem Laden der Startseite
  zusätzliche Anfragen auf: keine an einen anderen Ursprung und keine an `/api/`. Die Anmeldung selbst ist nicht Teil
  dieser Messung. Fremde Anfragen werden im Test blockiert. Der tatsächlich abgelegte Einführungsvermerk wird auf seine
  vier Felder und die gehashte Kennung geprüft.
- Die Hilfe-Kopfzeile ist kein zweites `banner` mehr. Bei geöffnetem Dialog sind Navigation, Seiteninhalt und
  Sprunglink mit `inert` für Tastatur und Screenreader gesperrt; nach dem Schliessen sind sie wieder erreichbar. So
  stehen die beiden Inhaltsverzeichnisse nicht gleichzeitig als aktive Landmarks zur Verfügung. Angular Material erhält
  `autoFocus="dialog"`, damit sein automatisches Wiederherstellen des Fokus nicht mit `inert` kollidiert. `closedStart`
  schliesst den Zustand idempotent; nach dem nächsten Render fokussiert der Service den gemerkten Auslöser, sofern er noch
  verbunden und nicht `inert` ist, andernfalls die Seitenüberschrift. Eine Generationsnummer verwirft veraltete
  Fokusrückgaben, falls währenddessen erneut geöffnet wird. Der Unit-Test prüft die Reihenfolge «inert entfernt, dann
  Fokus» und den Fallback bei entferntem Auslöser. Das Zehn-Zyklen-Browserszenario wartet nach Escape per Polling auf
  Auslöserfokus und vollständiges Ende der Schliessanimation; der Lauf und drei direkte Wiederholungen sind grün. Die
  Seitenleiste ist ein `aside`, damit die Marke auch auf breiten Bildschirmen innerhalb einer Landmark liegt.
- Überschriften ohne IDs verwenden `[attr.id]`: Die vorherige Property-Bindung schrieb bei `null` die Zeichenfolge
  `null` als ID. `anleitung-text.spec.ts` prüft, dass die Attribute in der Leiste tatsächlich fehlen.
- Nachweis 10.1 und 10.2: `ReqAbdeckungTest` und `SzenarienShardTest` prüfen das Repository; für 9.6 enthält der
  PR unter `backend/` ausschliesslich eine Feature-Datei. Oberfläche und Bedienung der Anleitung ändern sich nicht,
  darum brauchen Anleitung und Katalog keine Textänderung.
- Abschluss Aufgabe 23.5 (22.11.2026): `npx ng test --watch=false` (84 Dateien, 1'058 Tests), `npx ng build`,
  `npm run pruefe:gestaltung` und die 20 Spec-Abdeckungs-/Shard-Tests sind grün. Alle 100 Spec-07-Browserszenarien
  sowie das Zehn-Zyklen-Szenario dreimal nacheinander sind grün. Der Build meldet weiterhin das bestehende
  Initial-Bundle-Budget (691.47 KiB statt 500 KiB).
- Nachtrag Zweitreview (22.11.2026): Auch der Updatehinweis ausserhalb des Drawer-Containers erhält bei geöffneter Hilfe
  `inert`. Das Browser-Szenario löst mit zwei synthetischen PWA-Versionen einen echten Updatezustand aus und prüft
  Maus, Fokus und Zugänglichkeitsbaum vor und nach dem Schliessen.

## Umsetzung (Spec 06, Aufgabe 20.6, 23.11.2026)
- Die Anleitung «Verwaltung» beschreibt neu die Benutzerübersicht. Abweichend von der Tabelle «Hilfe-Katalog»
  ordnet der Katalog den Pfad `/verwaltung/benutzer` samt Detailseite diesem Abschnitt zu; seine bestehende
  Sichtbarkeitsbedingung bleibt unverändert.
