# Barrierefreiheit und Gestaltung – Prüfbericht

## Ergebnis

In den geprüften Ansichten wurden keine neuen Gestaltungs- oder Barrierefreiheitsbefunde festgestellt. Es wurden keine UI-Dateien geändert. Die Prüfung deckt noch nicht alle PWA-Routen in allen vier Kombinationen aus 375/1280 px und hellem/dunklem Farbschema ab; darum ist sie kein vollständiger Nachweis für «alle Seiten».

## Automatische Prüfungen

- `npm run pruefe:gestaltung`: alle 9 Prüfungen bestanden.
- Frontend-Tests: 103 Dateien und 1'222 Tests bestanden.
- Browser-Suite: 340 von 341 Szenarien bestanden. Das Offline-Erfassungsszenario lief beim Warten auf `networkidle` in einen Timeout; der gezielte Wiederholungslauf bestand mit 21 Schritten. Die vorhandenen axe-Szenarien der Suite bestanden.
- Direkte axe-Prüfungen der Startseite und der Hilfe-Seite: keine WCAG-2.1-A/AA-Befunde.

Die vorhandenen axe-Szenarien prüfen unter anderem Hilfe und Einführung, Einstellungen und Benutzerverwaltung, Wissensbasis und Entwürfe, Import, Chat, Chat-Auswertung und Bewertungsdialoge. Das ist keine vollständige routeweise Prüfung.

## Manuelle Sichtprüfung

Selbst angesehen wurden Screenshots der Start-/Anmeldeseite, Einstellungen, Wissenseinstellungen, Wissensansichten samt Freigabedialog sowie Chat und Gesprächsansicht bei 375 und 1280 px, jeweils hell und dunkel. Die Hilfe-Seite wurde zusätzlich bei 375 px im hellen Farbschema angesehen. Dabei fiel kein bestätigter Gestaltungsfehler auf.

Beim Tastaturtest war «Zum Inhalt springen» der erste Tab-Stopp; der Fokusrahmen war sichtbar.

## Offen

Die vollständige Route-für-Route-Prüfung aller PWA-Seiten bei beiden Breiten und Farbschemata, einschliesslich Screenshots und axe, ist noch ausstehend. Die ungeprüften Kombinationen dürfen nicht als bestanden gewertet werden. Bis dahin bleibt die Aussage auf die oben genannten Ansichten und bestehenden automatisierten Szenarien beschränkt.
