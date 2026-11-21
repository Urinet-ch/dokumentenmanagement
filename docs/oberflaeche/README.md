# Gestaltung der Oberfläche

Verbindlich für **jede neue Seite und jede Änderung an einer Seite** der PWA, egal wer sie baut, Claude Code oder GitHub Copilot.
Grundlage ist der Prototyp [`prototyp.html`](prototyp.html) (Entscheid Fabian 20.11.2026). Seit dem 21.11.2026 gilt er für alle neuen Seiten und Änderungen (Entscheid Fabian). Die Regeln in Kurzform stehen in `.kiro/steering/tech.md` unter «Konventionen Frontend».

## Grundsätze
1. **Der Prototyp bestimmt das Aussehen, nicht den Funktionsumfang.** Gestaltet wird nur, was die Seite und die API wirklich liefern. Zahlen, Felder, Knöpfe oder Texte aus dem Prototyp, die es nicht gibt, kommen nicht in die App. Das gilt auch für Beispieldaten.
2. **Beim Umgestalten bleiben Verhalten, Rechte, `data-test`-Attribute, ARIA-Rollen und -Namen unverändert.** Die Browser-Szenarien greifen darauf zu.
3. **Baue aus den Bausteinen unten**, nicht mit eigener Optik. Eine Seite ohne Vorbild im Prototyp setzt sich aus diesen Bausteinen zusammen. Die Referenzseiten zeigen, wie das aussieht.

## Farben
Farben kommen nur aus den Tokens in `frontend/src/styles.scss`. Hell und dunkel laufen über `light-dark()`. Feste Farbwerte in Komponenten sind verboten; das prüft die CI.

| Token | Verwendung |
| --- | --- |
| `--flaeche` | Seitenhintergrund |
| `--karte`, `--karte-2` | Karten; abgesetzte Flächen innerhalb von Karten (Filterleiste, Kachel, Zeile beim Überfahren) |
| `--linie`, `--linie-stark` | Trennlinien und Rahmen; Rahmen beim Überfahren |
| `--text`, `--text-2`, `--text-gedaempft` | Haupttext; Nebentext; Beschriftungen, Hinweise, Metadaten |
| `--akzent-text`, `--akzent-weich` | Schrift und Symbole in Akzentfarbe (Vorzeile, aktiver Menüpunkt); weiche Akzentfläche |
| `--urinet-gelb` | Flächen der Hauptaktion, Zähler, Marke, Fokusrahmen. **Nie als Schriftfarbe** auf hellem Grund |
| `--urinet-schwarz` | Text auf Gelb, in beiden Farbschemata |
| `--urinet-papier` | Hintergrund von Dokumentvorschauen |
| `--ok`, `--warnung`, `--gefahr` (je mit `-weich`) | Zustand: erledigt, prüfen, Fehler oder gefährliche Aktion |
| `--schatten`, `--radius`, `--radius-klein` | Kartenschatten, Radius von Karten (16 px) und Elementen (10 px) |

Mischfarben nur mit `color-mix()` aus Tokens. Ein neues Token gehört nach `styles.scss`, mit Werten für hell und dunkel, und der PR begründet es. Eine Ausnahme ist nur mit dem Vermerk `gestaltung-ausnahme: <Grund>` auf derselben oder der vorherigen Zeile erlaubt, z. B. die schwarze Kamerafläche.

## Schrift
- Inter, lokal eingebunden, kein CDN. Zahlen in Tabellen und Beträgen mit `font-variant-numeric: tabular-nums`.
- Seitentitel: `h1` im Seitenkopf. Kartentitel 15 px, Gewicht 600. Gruppen- und Spaltenbeschriftungen 11 px in Versalien mit Abstand `0.06–0.09em`.
- Monospace (`ui-monospace, Menlo, monospace`) für Belegnummern, IDs, Pfade, Prüfsummen, Scopes und Aktionscodes.

## Bausteine
| Baustein | Aussehen | Vorbild |
| --- | --- | --- |
| **Seitenkopf** | `<div class="seitenkopf">` (global) mit Vorzeile `.vorzeile` (Bereich oder Zweck), `h1`, Untertitel `p`; Aktionen rechts daneben, auf dem Handy darunter | jede Seite |
| **Karte** | `.karte` (global): Fläche `--karte`, Rahmen `--linie`, Radius 16 px, Schatten. Für zusammengehörige Inhalte; nicht jede Zeile ist eine Karte | Dokumentansicht |
| **Liste mit Suche** | Karte mit Suchfeld (Lupe), Segment für den Status, Knopf «Filter»; Filterleiste auf `--karte-2` darunter | `kontakte/kontakte.*` |
| **Tabelle** | Kopf in Versalien `--text-gedaempft`, Zeilen mit Trennlinie und Hover `--karte-2`, Zahlen rechtsbündig; eigener Container mit `overflow-x: auto`; auf dem Handy Zeilen statt Spalten | `verwaltung/protokoll/*`, `kontakte/*` |
| **Pille** | kleines Etikett mit Radius 99 px: neutral (`--karte-2`), Zustand (`--ok`, `--warnung`, `--gefahr` auf `-weich`), Akzent; Text oder Symbol nötig, nie nur Farbe | `eingangskorb/*`, `verwaltung/api-tokens/*` |
| **Hinweis** | Box auf `--warnung-weich` bzw. `--gefahr-weich` mit Symbol und fettem Anfang; Fehler mit `role="alert"` | `eingangskorb/eingangskorb-karte.*` |
| **Kachel** | Kennzahl oder Vorschlagsfeld auf Fläche mit Beschriftung oben; unsichere oder auffällige Werte mit Rahmen in Zustandsfarbe **und** Text | Eingangskorb, Import-Lauf |
| **Knöpfe** | Hauptaktion `mat-flat-button` (gelb, Text schwarz); Nebenaktion umrandet; gefährliche Aktion als dezenter Textknopf in `--gefahr`, immer mit Rückfrage; Symbol links, wo es hilft | Eingangskorb |
| **Symbole** | `<app-symbol name="…">` aus `gemeinsam/symbol.ts` (nur Pfade, 24×24, Strich 1.7). Neue Symbole am Ende von `SYMBOLE` anhängen; keine Icon-Schrift, kein CDN | Seitenleiste |
| **Zeitleiste, Diff, Schlüssel-Wert-Liste** | Versionen als Punkte auf einer Linie; Diff in Mono mit «+»/«−» und Durchstreichen; `dl` zweispaltig, auf dem Handy untereinander | Dokumentansicht, `wissen/wissen-entwuerfe.*` |
| **Leer, Laden, Fehler** | Leerzustand als Karte mit Symbol und einem Satz, was hier erscheint; Laden mit `mat-progress-bar`; Fehler als Hinweis mit «Erneut versuchen» | Eingangskorb, Wissen |

Seitenleiste, Kopfzeile, Farbschema-Knopf und Kopfzeilen-Suche gehören dem App-Rahmen (`app.*`). Seiten ändern ihn nicht. Ein neuer Menüpunkt kommt mit Gruppe und Symbol in `gemeinsam/navigation.ts`.

## Layout und Barrierefreiheit
- **Mobile first:** Jede Seite ist bei 375 px ohne horizontales Scrollen bedienbar. Breite Inhalte scrollen in einem eigenen Container. Ab 960 px steht die Seitenleiste fest; zweispaltige Seiten ab etwa 1100–1180 px.
- **WCAG 2.1 AA:** Kontrast in beiden Farbschemata, sichtbarer Fokus (gelber Rahmen), Zustände nie nur über Farbe, Überschriften in sinnvoller Reihenfolge.
- **Stylesheets klein halten:** je Komponente unter 4 kB, Fehlergrenze 8 kB. Wiederkehrende Muster kommen von einer Referenzseite und werden nicht neu erfunden.

## Prüfung
- **Automatisch:** `npm run pruefe:gestaltung` in `frontend/`, läuft in der CI im Job «Frontend». Die Prüfung meldet feste Farbwerte, die früheren Farbnamen `--urinet-dunkel/-hell/-karte/-gedaempft/-linie/-fehler/-erfolg` und Seiten mit `h1` ohne Seitenkopf.
- **Im PR jeder Frontend-Aufgabe:** Bildschirmfotos bei 375 und 1280 px, hell und dunkel, selbst angesehen. Dazu axe ohne neuen Befund. Unter «Bewusst nicht übernommen» steht, was aus dem Prototyp fehlt und warum.
- **Neue Seite ohne Vorbild:** Gibt es für die Seite im Prototyp nichts Vergleichbares, wird sie aus den Bausteinen zusammengesetzt. Bei Bedarf wird der Prototyp im selben PR um die Ansicht ergänzt, mit erfundenen Beispieldaten und klar als Beispiel gekennzeichnet.
