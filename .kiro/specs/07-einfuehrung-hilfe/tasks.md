# Implementation Plan — 07 Einführung und Hilfe

Voraussetzungen: Spec 07 auf `main` gemergt (die Vorschläge aus design.md «Entscheide» gelten damit). Die Spec braucht nur
das bestehende Frontend (App-Rahmen mit Seitenleiste und Kopfzeile, `navigation.ts`, `rechte.ts`, `BrandingService`,
Anmeldung, PWA); sie hängt nicht von Gate Phase 1 ab und kann parallel zu den übrigen Wellen laufen. Wellen, Rollen und
Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`, Branch
`feature/07-<aufgabe>-<stichwort>`. Die Wellen 22 und 23 folgen auf die Nummern von Spec 06. Steht ein Wert noch auf
«[offen: …]», wird er nicht geraten; die Aufgabe setzt bis zum Entscheid die Fassung ohne den offenen Teil um (bei 2.4:
gleiche Texte für alle Mandanten). Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`. Jede Aufgabe liefert
für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` und `@spec-07` unter `features/hilfe/` (Browser-Szenarien
zusätzlich mit `@browser`; ausser bei Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`, bei Oberfläche
mit Bildschirmfotos bei 375 und 1280 px in hell und dunkel, axe ohne neuen Befund und `npm run pruefe:gestaltung`. Jede
Aufgabe ist höchstens etwa drei Stunden gross. Aufgaben mit **[Fabian]** erledigt Fabian, nicht der Agent. Aufgaben, die
`app.html`, `start.ts` oder Seiten anderer Specs ändern (22.3, 23.2, 23.4), prüfen vor dem Start, ob ein offener PR
dieselben Dateien ändert (`CLAUDE.md`, «Keine Überschneidung»). Die Texte der Anleitung (22.5 bis 22.7) beschreiben nur,
was auf `main` umgesetzt ist (Requirement 6.4).

## Welle 22 — Benutzeranleitung und Hilfe
- [x] 22.1 Hilfe-Katalog, Markdown-Teilmenge und Inhaltsprüfung [Frontend]
  - Ordner `frontend/src/app/hilfe/` mit `hilfe-katalog.ts` (Einträge `erste-schritte` und `probleme`; die übrigen
    bringen 22.5 bis 22.7 mit ihren Dateien), `anleitung-markdown.ts` (Teilmenge nach design.md, Ergebnis `Textteil`),
    `anleitung-text.ts` (Darstellung nach dem Muster von `wissen-seite.html`, nur Interpolation), `AnleitungLader` mit
    Fehlerfall und «Erneut versuchen»; Dateien `frontend/public/anleitung/erste-schritte.md` und `probleme.md`.
  - Vitest `hilfe-katalog.spec.ts`, `anleitung-markdown.spec.ts`, `anleitung-inhalt.spec.ts` (Sprache, Links, Muster von
    Personendaten, Summe der Grössen); Texte der Oberfläche in `texte.de-CH.ts` (`hilfe`).
  - Erledigt (PR #411; design.md «Umsetzung (Aufgabe 22.1, 21.11.2026)»): Katalog mit `erste-schritte` und `probleme`,
    `anleitung-markdown.ts`, `anleitung-text.ts` (Eingaben `versatz`, `leiste`, Ausgaben `abschnitt`, `navigiert`),
    `anleitung-lader.ts`, `app-pfade.ts`, Symbol `info`; Szenario `@req-7.1` in `features/hilfe/anleitung-pwa.feature`,
    7.2 bis 7.4, 9.3, 9.5 und 9.7 per Vitest. Lader, Hinweis und «Erneut versuchen» nach 7.6 sind umgesetzt und mit
    `anleitung-text.spec.ts` geprüft; weil erst 22.2 die Anleitung auf einer Seite zeigt, ist 7.6 mit seinem
    Browser-Szenario zu 22.2 verschoben.
  - _Anforderungen: 7.1, 7.2, 7.3, 7.4, 9.3, 9.5, 9.7_

- [ ] 22.2 Seite `/hilfe` mit Inhaltsverzeichnis und Sprungmarken [Frontend]
  - Route `hilfe` (`loadComponent`, ohne Rechte-Wächter, Titel wie die übrigen Routen), Seitenkopf mit Name aus dem
    Branding, Inhaltsverzeichnis als `nav`, Filter nach Rolle und abgemeldet, Hinweis nach 6.3, Satz zur eigenen Rolle,
    Sprung zum Anker im Scroll-Bereich `mat-sidenav-content` mit Fokus, unbekannter Anker ohne Meldung; einspaltig bis
    etwa 1100 px, darüber mit Inhaltsverzeichnis links; Platz für den Knopf «Einführung erneut zeigen» (Funktion mit 23.3).
  - Prototyp `docs/oberflaeche/prototyp.html` um die Seite ergänzt, mit erfundenen und gekennzeichneten Beispieldaten.
  - Szenarien je Rolle (Inhaltsverzeichnis, verborgene Abschnitte, Sprungmarke, Zurück-Knopf); Szenario zu 7.6 (eine
    Datei nicht ladbar: Hinweis mit «Erneut versuchen» beim Abschnitt, die übrigen bleiben; Teile aus 22.1).
  - _Anforderungen: 5.1, 5.2, 5.3, 5.4, 6.2, 6.3, 6.5, 7.6_
  - _Abhängig von: 22.1_

- [ ] 22.3 Knopf «Hilfe» und Hilfe-Leiste [Frontend]
  - Symbol `hilfe` am Ende von `SYMBOLE`; Knopf in der Kopfzeile von `app.html` für jeden Besucher; zweiter `mat-sidenav`
    (`position="end"`, `mode="over"`) mit `@defer`, Dienst `HilfeLeiste`, Kontext über den Katalog und `aktiverPfad`,
    Inhaltsverzeichnis ohne Kontext, Links in der Leiste (Abschnitt wechselt den Inhalt, App-Pfad navigiert und schliesst),
    «Ganze Anleitung öffnen», Fokus, Escape, Fokusrückgabe.
  - Szenario: Auf «Erfassen» mit einer Aufnahme die Hilfe öffnen und schliessen, die Aufnahme ist noch da; im Chat bleibt
    der Verlauf.
  - _Anforderungen: 4.1, 4.2, 4.3, 4.4, 4.5_
  - _Abhängig von: 22.2_

- [ ] 22.4 Anleitung offline [Frontend]
  - Asset-Gruppe `anleitung` (`prefetch`) in `ngsw-config.json`; Prüfung, dass Quinoa `/anleitung/*.md` ausliefert und die
    SPA-Weiterleitung sie nicht abfängt; Szenarien mit Service Worker und `setOffline` (Leiste und Seite vollständig,
    abgemeldet nur die allgemeinen Abschnitte und `erfassen-offline`); neue Version tauscht App und Anleitung zusammen.
  - _Anforderungen: 8.1, 8.2, 8.3_
  - _Abhängig von: 22.3_

- [ ] 22.5 Texte: Erste Schritte, Erfassen, Eingangskorb, Dokumentansicht [Frontend]
  - Dateien und Katalogeinträge `erste-schritte` (fertig), `erfassen` mit `erfassen-handy`, `erfassen-dateien`,
    `erfassen-offline` (Hauptfall, ausführlich), `eingangskorb` mit `eingangskorb-bestaetigen` und `eingangskorb-eigene`,
    `dokument` mit `dokument-aendern`; Namen der Menüpunkte und Knöpfe aus `texte.de-CH.ts`; Review durch Fabian.
  - _Anforderungen: 6.1, 6.4, 6.6, 6.7_
  - _Abhängig von: 22.1_

- [ ] 22.6 Texte: Suche, Chat, Kontakte, Wissen [Frontend]
  - Dateien und Katalogeinträge `suche`, `chat` (ohne die Beispielfragen der Chat-Abnahme), `kontakte`, `wissen` mit
    `wissen-entwuerfe`; nur umgesetzte Funktionen.
  - _Anforderungen: 6.1, 6.4, 6.6_
  - _Abhängig von: 22.1_

- [ ] 22.7 Texte: Importe, Jahresexport, Verwaltung, Probleme; Anleitungen unter `docs/` [Frontend]
  - Dateien und Katalogeinträge `importe`, `jahresexport`, `verwaltung` mit `verwaltung-dokumentarten`,
    `verwaltung-protokoll`, `verwaltung-api-tokens`; `probleme` vervollständigt (neutrale Ansprechperson).
  - `docs/anleitung-geschaeftsstelle.md` auf Betreiber-Wege, Kontaktstelle und Verweis auf `/hilfe` gekürzt; Verweis in
    `docs/anleitung-verwaltung.md` auf die App (der Inhalt folgt mit Spec 06: 21.5).
  - Prüfen, dass die Pflegeregel in `tech.md` (Definition of Done) für alle Abschnitte greift; Katalog vollständig nach
    Requirement 6.1.
  - _Anforderungen: 6.1, 6.4, 6.6, 7.5, 7.7_
  - _Abhängig von: 22.1_

## Welle 23 — Einführung und Abschluss
- [ ] 23.1 Einführungsvermerk [Frontend]
  - Dienst `EinfuehrungVermerk`: Schlüssel `archiv.einfuehrung`, Kennung SHA-256 von `<mandant>:<sub>`, Version, Art,
    Datum, höchstens 20 Einträge, Arbeitsspeicher als Rückfall (gesperrter Speicher, beschädigter Eintrag, ohne
    `crypto.subtle`), nie an den Server; Vitest `einfuehrung-vermerk.spec.ts`.
  - _Anforderungen: 1.3, 1.4, 1.5, 1.6_

- [ ] 23.2 Einführung auf der Startseite: Anzeige und Schritte nach Rechten [Frontend]
  - Karte zwischen Seitenkopf und Kacheln in `start.ts` für neue Benutzer, Schritte nach der Tabelle in Requirement 2.1
    mit `darf`, Texte in `texte.de-CH.ts` (Entwürfe aus design.md), Name und Logo aus dem Branding, Schritt 5 mit den
    Beschreibungen aus `texte.start.beschreibung`; bekannte Benutzer und Benutzer ohne Rolle wie bisher; keine Anfrage an
    die API.
  - Prototyp `docs/oberflaeche/prototyp.html` um die Einführung ergänzt.
  - Vitest `einfuehrung-schritte.spec.ts` (vier Rollen); Szenariogrundriss je Rolle.
  - _Anforderungen: 1.1, 1.2, 1.7, 2.1, 2.2, 2.3, 2.4, 3.1, 3.8_
  - _Abhängig von: 23.1, 22.3_

- [ ] 23.3 Einführung: Bedienung und erneuter Aufruf [Frontend]
  - «Schritt n von m», «Zurück», «Weiter», «Fertig», «Überspringen», Statusmeldungen, Fokus auf die Überschrift des
    Schritts und nach dem Schliessen auf den Seitenkopf, Schritt bleibt bis zum Neuladen, `prefers-reduced-motion`;
    «In der Anleitung lesen» öffnet die Hilfe-Leiste; «Einführung erneut zeigen» auf `/hilfe` öffnet `/?einfuehrung`.
  - _Anforderungen: 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 5.5_
  - _Abhängig von: 23.2_

- [ ] 23.4 Links «So funktioniert es» an Orten mit Rückfragen [Frontend]
  - Erfassen (Warteschlange, fehlerhafter Upload), Anmeldekarte der Startseite ohne Netz und Meldung ohne Mandant oder
    Rolle öffnen die Hilfe-Leiste mit dem Unterabschnitt; Verhalten und `data-test` der Seiten sonst unverändert.
  - _Anforderungen: 4.6_
  - _Abhängig von: 22.3, 22.5_

- [ ] 23.5 Barrierefreiheit, Datenschutz und Abdeckung [QA]
  - Szenarien mit axe für Einführung, Leiste und Seite bei 375 und 1280 px in hell und dunkel; Aufzeichnung der Anfragen
    (nur eigener Ursprung, keine zusätzlichen `/api/`-Aufrufe); Nachweis, dass die Spec-Prüfungen `@spec-07` erfassen
    (grüner Lauf von `ReqAbdeckungTest` und `SzenarienShardTest` mit den Feature-Dateien unter `features/hilfe/`); Liste
    der Änderungen unter `backend/` (nur Feature-Dateien).
  - _Anforderungen: 9.2, 9.4, 9.6, 10.1, 10.2_
  - _Abhängig von: 22.4, 23.3, 23.4_

- [ ] 23.6 Abnahme Einführung und Hilfe [Fabian]
  - `docs/abnahme/hilfe.md` nur mit Nummern und Ergebnissen: Szenarien und Tests grün; manuell: Einführung als neuer
    Benutzer je Rolle auf Stufe A (Testbenutzer), Handy bei 375 px mit iOS und Android, Anleitung offline am Handy,
    Stichprobe mit Screenreader, Bildschirmfotos hell und dunkel, Lesetest durch eine Person, die das Archiv nicht kennt;
    Entscheid zum offenen Punkt 2.4 nachgetragen oder als offen vermerkt.
  - _Anforderungen: 9.1, 10.3_
  - _Abhängig von: 22.5, 22.6, 22.7, 23.5_
