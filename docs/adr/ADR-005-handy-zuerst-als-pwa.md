# ADR-005: Handy zuerst als PWA, Capacitor später

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026). Dort wird auch Konzept-Entscheid E3 bestätigt.

**Datum:** 08.11.2026

## Kontext
Die Handy-Erfassung ist der Hauptfall (tech.md, Konventionen Frontend): Post und Quittungen sollen mit
dem Handy fotografiert und sofort erfasst sein (Req 4, Konzept D2). Die Specs gehen von einer PWA aus,
die Bestätigung steht als Konzept-Entscheid E3 noch [offen] (umsetzungsplan.md, Abschnitt 6).
Anforderungen und Randbedingungen:
- Mehrseitige Aufnahme mit der Rückkamera, Kantenerkennung, Zuschnitt, Entzerrung und Vorschau (Req 4.2, 4.3).
- Rohaufnahme als Original, Zuschnitt als Arbeitsfassung (Req 4.7, P13); die PDF/A entsteht im Worker
  aus der Arbeitsfassung (Req 5.10).
- Offline-Warteschlange mit Zähler, automatischem Upload, Dublette gilt als übertragen (Req 4.4–4.6,
  4.8, P8).
- Eingangskorb auf 375 px ohne horizontales Scrollen, Bestätigen in höchstens zwei Tipps (Req 8.4).
- iOS (Safari) und Android (Chrome), installierbar ([offen: Mindestversionen iOS und Android – Entscheid
  vor Welle 3]); Nachweis auf echten Geräten (Req 4.1).
- Ein Frontend-Stack (Angular), Frontend-Aufgaben teils durch Joel (umsetzungsplan.md, Abschnitt 2);
  Auslieferung über Quinoa im API-Artefakt und Deploy nur über GitHub Actions (ADR-010).

## Entscheid
Die Handy-Erfassung ist Teil der einen Angular-PWA (`@angular/pwa`), die auch Upload, Eingangskorb,
Suche, Import und Verwaltung abdeckt (design.md, Frontend):
- Kamera über `getUserMedia` (Rückkamera), Rückfall `<input type="file" accept="image/*" capture="environment">`.
- Kanten und Entzerrung mit jscanify (OpenCV.js) als Web Worker, erst bei Bedarf geladen; ohne erkannte
  Kanten ist die Arbeitsfassung das ganze Bild.
- Original: unbearbeitete Aufnahme (beim Rückfall die Datei der Kamera, sonst das volle Videobild als
  JPEG 95 %); Arbeitsfassung: JPEG 85 %, lange Kante 2'480 px.
- Offline-Warteschlange in IndexedDB (`idb`); Senden beim `online`-Event, beim Öffnen der App und über
  Background Sync, wo verfügbar. Fehlerregeln wie design.md (3 Fehlversuche bei Netz/5xx, 4xx sofort
  fehlerhaft, 401 neu anmelden, Eintrag bleibt).
- Anmeldung per OIDC mit PKCE gegen Keycloak; die Oberfläche blendet Aktionen ohne Recht aus, die API
  prüft trotzdem (ADR-009).

Eine native Hülle mit Capacitor folgt später (design.md, Overview); den Zeitpunkt legt Spec 01 nicht fest. (D8 gestrichen, entschieden von Fabian am 09.11.2026: keine native App, nur Web-App; requirements.md, Rückverfolgung)

## Betrachtete Alternativen
1. **Native Apps (Swift, Kotlin)** – abgelehnt: zwei zusätzliche Codebasen neben Angular, Verteilung über
   App Stores, kein Wiederverwenden von Eingangskorb und Suche; zu viel Aufwand für das Zeitbudget.
2. **Capacitor-Hülle von Anfang an** – zurückgestellt: brächte native Kamera- und Hintergrundfunktionen,
   aber App-Store-Konten, Signierung, Review und eine zweite Build-Kette. Weil Capacitor eine Web-App
   einpackt, bleibt der PWA-Code für diesen Schritt nutzbar. (D8 gestrichen, entschieden von Fabian am 09.11.2026: keine native App, nur Web-App; requirements.md, Rückverfolgung)
3. **Fotos-App oder fremde Scanner-App, danach Upload im Browser** – abgelehnt: kein Original nach P13
   (Rohaufnahme), keine Offline-Warteschlange mit Zähler (Req 4.4), Medienbruch beim Erfassen; ein Weg über
   E-Mail gehört erst zu Spec 02.

## Folgen
- **Positiv:** Eine Codebasis und ein Deploy für Handy und Desktop; Updates kommen ohne Store über den Service Worker.
- **Positiv:** Gleiche Rechte, Fehlercodes und Tests wie im übrigen Frontend; Playwright im 375-px-Viewport (Aufgaben 3.6, 3.7).
- **Negativ:** Browser-Funktionen unterscheiden sich je Plattform. Background Sync gibt es nicht überall (iOS Safari);
  deshalb sendet die App auch beim Öffnen und beim `online`-Event.
- **Negativ:** Lokaler Browser-Speicher kann geräumt werden; Einträge werden erst nach 202 gelöscht und der Zähler
  «wartet auf Upload» macht Wartendes sichtbar (Req 4.4, 4.5).
- **Umsetzung:** OpenCV.js ist mehrere MB gross; Laden im Web Worker nur auf der Seite «Erfassen».
- **Tests:** Echte Geräte lassen sich nicht in der CI testen: manueller Nachweis durch Fabian (Req 4.1, Aufgabe 3.7)
  nach den Mindestversionen aus Aufgabe 2.8.
- **Betrieb:** Kamera und Service Worker brauchen HTTPS; gegeben über Traefik unter `archiv.apps.urinet.ch` (ADR-010).

## Verweise
- Konzept-Entscheid E3 (umsetzungsplan.md, Abschnitt 6); Aufgabe 0.6
- Req 3.4, 4.1–4.8, 5.10, 8.4, 8.6; P8, P13
- Aufgaben 2.7, 2.8, 3.6, 3.7
- ADR-001, ADR-009, ADR-010
