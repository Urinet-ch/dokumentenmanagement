# Barrierefreiheit und Gestaltung – Prüfbericht

## Ergebnis (25.11.2026)

Der vollständige Routenabgleich umfasst 31 registrierte Seitenrouten aus `app.routes.ts` und `chat.routes.ts`; für dynamische Routen wurden konkrete Testpfade verwendet. Insgesamt wurden 33 Seitenzustände bei 375 und 1280 px in hellem und dunklem Farbschema geprüft: 132 Screenshots, alle mit axe ohne Befund. Die Screenshots wurden in allen vier Varianten selbst angesehen; es blieb kein weiterer visueller Befund offen.

Geprüft wurden `/`, `/anmeldung`, `/nicht-gefunden`, `/hilfe`, `/erfassen`, `/eingangskorb`, `/suche`, `/wissensbasis/einstellungen`, `/wissen`, `/wissen/log`, `/wissen/ueberblick`, `/wissen/entwuerfe`, `/wissen/entwuerfe/import`, `/wissen/entwuerfe/meine`, `/wissen/entwuerfe/meine/:id`, `/wissen/entwuerfe/:id`, `/wissen/:ordner/:name`, `/dokument/:id`, `/kontakte`, `/kontakte/:id`, `/importe`, `/importe/:id`, `/verwaltung/einstellungen`, `/verwaltung/benutzer`, `/verwaltung/benutzer/:id`, `/verwaltung/dokumentarten`, `/verwaltung/protokoll`, `/verwaltung/api-tokens`, `/chat`, `/chat/auswertung` und `/export`.

Die Screenshots und das maschinenlesbare Prüfergebnis liegen ausserhalb des Repositories im Session-Artefakt `a11y-rest-2026-11` und enthalten ausschliesslich synthetische Testdaten.

Die direkten Aufrufe von `/wissen/ueberblick` und `/wissen/entwuerfe/meine` zeigten erwartungsgemäss den Zustand ohne Anmeldung. Die zugehörigen Seitenkomponenten wurden zusätzlich in angemeldeten Szenarien auf den Wissensseiten- und Analysevorschlagsrouten geprüft.

## Umgesetzte Korrekturen

- Seitenleiste, Suche, Tabellen und Tabellen-Scrollbereiche sind eindeutig benannt beziehungsweise per Tastatur erreichbar.
- Der PDF-Link nennt seinen sichtbaren Inhalt; die Beschriftung «Zuschnitt» ist sichtbar und der bisherige ARIA-Name bleibt unverändert.
- Der Kontrast der gedämpften Texte erfüllt in beiden Farbschemata WCAG AA.
- Die Anmeldungs-Rückrufseite hat nun eine sichtbare Hauptüberschrift.

Bestehende `data-test`-Attribute und die in Szenarien verwendeten ARIA-Rollen und -Namen blieben erhalten.

## Prüfungen

- `npm run pruefe:gestaltung`: 9/9 Prüfungen bestanden.
- Produktionsbuild: bestanden. Die Warnungen zu Initial-Bundle (738.29 kB gegenüber 500 kB) und `app.scss` (4.41 kB gegenüber 4 kB) bleiben.
- Frontend-Testlauf: 104 Dateien und 1'228 Tests bestanden.
- Gezielte Wiederholungen der drei im vollständigen Browserlauf betroffenen Szenarien bestanden: Budgetänderung (4 Szenarien, 72 Schritte), Chatquelle (1 Szenario, 20 Schritte) und abgewiesenes Paket (1 Szenario, 13 Schritte).

Der vollständige Browserlauf vor dem Merge von `origin/main` endete mit 333/336 Szenarien; fünf bekannte Offline-Flake-Szenarien waren ausgeschlossen. Bei den drei übrigen Fehlern bestanden die Anwendungsszenarien in den gezielten Wiederholungen; ein Lauf des temporären Audit-Hooks überschritt zusätzlich sein Zeitlimit von 240 Sekunden. Der Hook wurde danach entfernt. Die vollständige Browser-Suite wurde ohne diesen Hook nicht erneut ausgeführt; ihr Gesamtergebnis bleibt deshalb 333/336 und ist kein vollständig grüner Suite-Nachweis.

## Nachprüfung geänderter Ansichten (26.11.2026)

- **Hilfe-Leiste und Verzeichnis:** `hilfe-qualitaet.feature` bestand mit 23 Szenarien und 320 Schritten. Die aktualisierte Breite, das Verzeichnis und die Anleitung wurden bei 375 und 1280 px in beiden Farbschemata geprüft; axe meldete keine Befunde und es gab keinen waagrechten Bildlauf.
- **Einführung für Treuhänder:** Alle fünf Schritte wurden bei 375 und 1280 px in hellem und dunklem Farbschema mit der Tastatur durchlaufen. «Weiter» und «Fertig» waren per Enter bedienbar; nach jedem Schrittwechsel lag der Fokus auf der Schrittüberschrift. axe meldete in keinem Schritt einen Befund, waagrechter Bildlauf trat nicht auf.
- **Hilfe per Tastatur:** In allen vier Ansichten schloss Escape die Hilfe-Leiste und stellte den Fokus auf «Hilfe» zurück. Tab und Enter bedienten den Schliessen-Knopf, das Verzeichnis und den ersten Verzeichnis-Link; der gewählte Abschnitt wurde angezeigt. axe blieb auch bei geöffnetem und ausgeklapptem Verzeichnis ohne Befund.
- **Treuhänder-Hinweis im Eingangskorb:** Das Szenario `@req-2.11 @req-2.21` in `navigation-pwa.feature` prüft den Hinweistext, axe und waagrechten Bildlauf bei 375 und 1280 px in beiden Farbschemata. Die vier Screenshots wurden visuell geprüft; der Hinweis ist statischer Text und fügt kein fokussierbares Bedienelement hinzu. Die Prüfung lief auf dem PR-Kopf von #663; dessen CI war grün.

Die Screenshots der Einführung und Hilfe-Leiste sowie die vier Eingangskorb-Screenshots liegen als synthetische Nachweise ausserhalb des Repositories im Session-Artefakt `a11y-follow-up` beziehungsweise im PR #663. Es wurden keine Korrekturen an `data-test`-Attributen oder ARIA-Namen vorgenommen; neue Befunde blieben keine offen.
