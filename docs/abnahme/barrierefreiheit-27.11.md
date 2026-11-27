# Nachprüfung Barrierefreiheit – Wissen, Benutzer und Hilfe

Stand 27.11.2026. Nur synthetische Testdaten; keine Dokumentinhalte oder Personendaten.

## Automatische Nachweise

| Oberfläche | Varianten | Ergebnis |
| --- | --- | --- |
| Wissensentwurf: Abschnittsfehler und Wiederholung | 375/1280 px, hell/dunkel | 4/4 Szenarien bestanden; `role="status"`, Fokus nach der Wiederholung, axe und kein horizontaler Überlauf |
| Rechte-Übersicht | 375/1280 px, hell/dunkel | 8/8 Szenarien bestanden; Tabellenstruktur, axe und kein horizontaler Überlauf |
| Hilfe-Leiste und Anleitung | 375/1280 px, hell/dunkel, vier Rollen; zusätzlich ohne Anmeldung | 23 Szenarien bestanden; axe und kein horizontaler Überlauf, Inhaltsverzeichnis offen und eingeklappt geprüft |

- Gesamt: **35 von 35 Browser-Szenarien, 516 von 516 Schritten bestanden**.
- Wissensentwürfe: **11 von 11 Angular-Tests bestanden**; Produktionsbuild und `npm run pruefe:gestaltung` bestanden.
- Bildschirmansichten bei 375 und 1280 px in beiden Farbschemata geprüft; keine visuellen Abweichungen festgestellt.
- Nicht Teil dieser Nachprüfung: Hilfe-Offline-Szenario zum Updatehinweis; es benötigt den produktiven Service Worker und war nicht Teil der drei angeforderten Oberflächen.

## Ergebnis

Bestanden. Keine offenen Barrierefreiheitsbefunde aus den geprüften Ansichten.
