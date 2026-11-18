# Implementation Plan — 03 Zweiter Mandant VHF/GSK

Voraussetzungen: Gate Phase 2, Zuschlag VHF/GSK, Konzept-Entscheid E4, Spec 03 auf `main` gemergt. Ausnahme: Die
mandantenneutralen Vorstufen 11.6 (Vertrag und Versionierung `/public/v1`) und 11.7 (Webseiten-Schlüssel) werden vor
Gate Phase 2 und vor dem Zuschlag entwickelt (entschieden von Fabian am 14.11.2026, «Neutrale Vorstufen jetzt»), ohne
VHF-spezifisches: keine Domain VHF/GSK, kein Seed `vhf-gsk`, keine Dateiauslieferung (11.8), kein Feed (11.9), keine
Erfassung (11.10). Erweitert am 16.11.2026 (entschieden von Fabian, Priorität Webseiten-Anbindung inkl. Chat): auch die
Backend-Aufgaben 11.1 (Zugriffsstufen), 11.2 (Endpunkte `/public/v1`), 11.3 (öffentlicher Chatbot), 11.8 (Dateiauslieferung)
und 11.9 (Änderungs-Feed) werden vorgezogen, nur mandantenneutral und mit Urinet bzw. Testmandanten, ohne Seed `vhf-gsk`,
ohne Domain VHF/GSK und ohne Erfassung (11.10); das WordPress-Plugin (11.4, 11.5) entsteht in einem eigenen Repository
(Entscheid 16.11.2026) und wartet auf den Vertrag. Alle übrigen Aufgaben (Welle 10, 11.10–11.13, Welle 12) bleiben an
Gate Phase 2, Zuschlag und E4 gebunden.
Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` (ausser bei Nachweis-Vermerk)
und erfüllt die Definition of Done aus `tech.md`.

## Welle 10 — Mandant, Branding, Erfassung

- [ ] 10.1 Auftragsbearbeitungsvertrag und Domain [Fabian]
  - Vorlage `docs/vertrag/avv-vorlage.md` erstellen (Unterauftragnehmer nach Requirement 8.4), mit VHF/GSK abstimmen und unterzeichnen
  - Teilumsetzung (PR #260): Vorlage als ausdrücklich nicht rechtlich geprüften Entwurf erstellt; rechtliche Prüfung, Abstimmung mit VHF/GSK und Unterzeichnung bleiben [Fabian]. Der Haken für Aufgabe 10.1 bleibt offen. Offener Punkt A2 (Aufbewahrung Chat-Fragen: Standard 90 Tage gegenüber den VHF/GSK zugesagten 30 Tagen) ist in der Vorlage und im Beiblatt benannt.
  - Entscheide vor Welle 10 einholen: Domain VHF/GSK, `gesetzliche_pflicht` und Standard-Vertraulichkeit je Art, Rechte A11 bis A14 der Geschäftsstelle
  - Domain für das Archiv festlegen ([offen: Domain VHF/GSK – Entscheid vor Welle 10]), DNS einrichten
  - _Anforderungen: 8.4, 2.2, 1.2, 3.5_

- [ ] 10.2 Seed und Mandant anlegen [Backend]
  - `backend/src/main/resources/seed/vhf-gsk/dokumentarten.json` mit Zusatz-Schema, Standard-Vertraulichkeit und `gesetzliche_pflicht`; keine Finanzarten; Provisionierung nach Spec 01 (Liquibase `db/mandant`, Bucket mit Object Lock, Versionierung und Bucket Policy mit Deny auf `s3:BypassGovernanceRetention`, Zugriff mit dem App-Schlüssel `s3_app_zugang`, nie mit Bypass-Header nach Spec 01, 6.5); Keycloak-Client `archiv-vhf-gsk` nur manuell durch den Betreiber, nicht durch den Befehl (für Theme/Redirect, Req 1.1/2.3)
  - Test-Seed `backend/src/test/resources/seed/vhf-gsk` (Dokumentart `PROBE_VHF_BULLETIN` aus Spec 01, Aufgabe 1.4) entfernen, sobald der echte Seed besteht; die Szenarien von 1.4 auf eine echte Art umstellen
  - Szenario: Finanzwerkzeuge für `vhf-gsk` nicht verfügbar
  - Spec 05 ergänzt: Seed `backend/src/main/resources/seed/vhf-gsk/konfiguration.json` statt `dokumentarten.json`, mit Klassifikation `fachgebiet`, Reihen als Dokumentarten, Regeln `EINDEUTIG` und `FOLGE` für Ausgabe-Nr. und Jahr; `KonfigurationSeedTest` prüft den Seed (Spec 05, Req 1.6, 6.7; Aufgabe 17.7)
  - Ergänzt 13.11.2026: Klassifikation `verband` (`vhf`, `gsk`, mehrfach, öffentlich filterbar, ohne KI-Vorschlag) im Seed; `zugriff` und `veroeffentlicht` nach design.md «Reservierte Felder» (drei Stufen)
  - Nachtrag (PR #227): Produktions-Seed mit sieben Verbandsarten (inkl. `JAHRESBERICHT` nach Req 1.2; `NORM_RICHTLINIE`, `FORMULAR`, `SONSTIGES` sind zusätzlich, weil Req 1.2 «mindestens» sagt) ergänzt. «Keine Finanzarten» belegt allein `VhfGskDokumentartSeedTest`, der den echten Seed liest; ein Cucumber-Szenario dazu entfällt, weil der Test-Seed den echten Seed im Klassenpfad überdeckt und `werkzeuge-finanzen.feature` die Werkzeugauswahl ohne Finanzarten schon prüft. Der Test-Seed `PROBE_VHF_BULLETIN` bleibt, weil zahlreiche Szenarien aus Aufgabe 1.4 ihn direkt verwenden; Werte zu Aufbewahrung und Vertraulichkeit sind vorsichtig ausgelegt. Die `default`-Werte im Schema (`zugriff` `mitglieder`, `veroeffentlicht` `false`) wendet das Backend nicht an; wo Standardwerte gesetzt werden, regelt Spec 05. Offen für Fabian: ob `VORSTANDSPROTOKOLL` als Standard-`zugriff` `vorstand` statt `mitglieder` erhalten soll (design.md «Reservierte Felder» nennt `mitglieder`). `_hinweise` dokumentiert Filter- und KI-Vorgaben bis zur Umsetzung der Spec-05-Konfiguration. Offen: Spec-05-Konfiguration, Keycloak-Client in Produktion und Produktiv-Provisionierung (Fabian).
  - Entscheid Fabian 17.11.2026: Standard-Zugriffsstufe von `VORSTANDSPROTOKOLL` im Seed ist `vorstand` (alle anderen Arten bleiben `mitglieder`); `VhfGskDokumentartSeedTest` prüft den Standard je Art. Die Anwendung setzt Schema-Standards nicht selbst ein, die Stufe wählt weiterhin eine Person.
  - _Anforderungen: 1.1, 1.2, 1.3, 1.5, 1.6, 3.7_
  - Anzeigetexte (PR #256): Der Seed hängt an `verband` jeder Art `"x-labels": {"vhf": "VHF", "gsk": "GSK"}`; die Oberfläche zeigt diese Texte, gespeichert und gesendet wird weiter der Enum-Wert (`VhfGskDokumentartSeedTest`)
  - Entscheid Fabian 17.11.2026 (PR #257): Das Archiv legt keinen mandantenspezifischen Keycloak-Client an. Falls für Theme oder Redirect-URI nötig, richtet ihn der Betreiber manuell ein;

- [ ] 10.3 Branding pro Mandant [Frontend] [Infra]
  - `GET /api/v1/branding?domain=`, Web-App lädt Name/Logo/Farbe; Keycloak-Theme `vhf-gsk` auf `archiv-auth.apps.urinet.ch`; Traefik-Router (Labels in `deploy/infomaniak/compose.yml`) für die Verbands-Domain, ausgerollt nur über den Deploy-Workflow
  - Umsetzung (PR #228): öffentlicher Branding-Endpunkt und Web-App mit neutralem Fallback; Domains bleiben ausserhalb des Repos konfigurierbar, da die Domain-Entscheidung offen ist. Offen: Keycloak-Theme und Traefik-Router (Deploy). Nachbesserung Review: Quelle ist `archiv.branding.mandanten.*` statt `mandant.branding` (Entscheid in design.md); Domain `archiv.apps.urinet.ch` ist Standard für UriNet, `ARCHIV_BRANDING_VHF_GSK_DOMAIN` muss im Deploy gesetzt werden, sobald die Domain feststeht; Seitentitel und Startseite zeigen den Mandantennamen; Primärfarben ohne WCAG-AA-Kontrast (4,5:1) fallen auf die Standardfarbe zurück; Logos nur auf eigener Origin.
  - Vorschlag aus PR #242: Keycloak-Theme `vhf-gsk` und Traefik-Router sind vorbereitet; Einzelheiten in design.md. Deploy und Prüfung sind weiterhin nicht belegt, deshalb bleibt der Haken offen.
  - PR #299: Traefik-Router für den Host von VHF/GSK wieder entfernt (Compose-Policy der VPS; ein Host nach ihrer Namensregel wäre mit dem Deploy sofort öffentlich, Abweichung von tech.md «Erreichbarkeit»); er kommt mit einem Entscheid von Fabian in einem eigenen PR, Einzelheiten in design.md.
  - _Anforderungen: 2.1–2.3_

- [x] 10.4 Erfassung für die Geschäftsstelle [Backend] [Frontend]
  - Rolle `geschaeftsstelle` nach Rechtematrix (Spec 01, 2.6: A1 bis A10 erlaubt, A11 bis A14 bis zum Entscheid 403), veröffentlichen/zurückziehen, KI-Prompt pro Mandant (Reihe, Ausgabe, Jahr, Fachgebiet), Zugriffsstufe nur manuell (Standard `mitglieder`)
  - Eingangskorb zeigt Felder je Dokumentart
  - Spec 05 ergänzt: KI-Prompt und Felder je Art aus der Konfiguration von `vhf-gsk`; die Felder und Klassifikationen je Art im Eingangskorb und in der Detailansicht liefert Spec 05, Aufgabe 18.4 (Spec 05, Req 7.5, 8.1)
  - Ergänzt 13.11.2026 (E-C): Auswahl der Zugriffsstufe mit drei Werten (`oeffentlich`, `mitglieder`, `vorstand`), Standard `mitglieder`
  - Umsetzung (PR #229): Zusatzfelder aus dem Dokumentart-Schema; Veröffentlichung erst nach bestätigter Archivierung, Rückzug über A6; bestehende Rechte A1–A10/A11–A14 wiederverwendet, auch für Aufgabe 11.10
  - Umsetzung (PR #245): Listenfelder im Eingangskorb und in der Metadatenansicht: Typ `mehrfach` (Array mit `items.enum`, Checkboxen) und `liste` (Array freier Texte), Komponente `Listenfeld`; Pflicht aus `pflichtfelder` («zusatz.<name>») und aus `required` im Schema (so im Seed `vhf-gsk` bei `verband`); Grenzen `minItems`, `maxItems`, `uniqueItems`, `items.enum` (Listen mit anderen `items`-Typen bleiben unbearbeitbar); getippter Text wird beim Verlassen des Felds übernommen. Nachweis: Vitests (`aenderung.spec.ts`, `metadaten-formular.spec.ts`, `eingangskorb-karte.spec.ts`) und `@req-3.1` `@attrappe` in `eingangskorb-listenfeld-pwa.feature` (Teilnachweis mit der Attrappe, nicht gegen das echte Archiv)
  - Verschoben (Nachbesserung Review PR #229): Der Mandantenhinweis im KI-Prompt ist eine Teilumsetzung (fester Textblock für `vhf-gsk`, keine zusätzlichen Antwortfelder, durch `MetadatenExtraktionTest` über `vorschlagen` belegt). Req 3.2 mit Feldern je Art aus der Konfiguration ist beschlossen nach Spec 05, Aufgabe 18.1/18.4 verschoben (kein offener Rest dieser Aufgabe). Der Feed-Eintrag beim Veröffentlichen und Zurückziehen (Spec 04, #230, Aufgabe 11.9) ist im Szenario «Die Geschäftsstelle veröffentlicht nach der Archivierung und zieht zurück» belegt (`stufe_vorher`/`stufe_nachher`).
  - _Anforderungen: 3.1–3.5_

- [ ] 10.5 Altbestand übernehmen [Backend] [Fabian]
  - Drive-Import mit API-Token vom Scope `import` des Mandanten `vhf-gsk` und Pfad-Mustern (Reihe/Jahr), Bericht mit Lücken in der Nummerierung
  - Tabelle `alte_url` und CSV-Import der alten Links (die Tabelle besteht seit Aufgabe 11.2, Changeset `031-alte-url`; der Import verwendet `AlteUrl.normalisieren` für die Form der URL)
  - CSV-Teilauftrag (PR #252) umgesetzt: Der Drive-Import und die Datenübernahme bleiben [Fabian] zugeordnet; darum bleibt der Haken offen.
  - _Anforderungen: 4.1–4.3_

- [ ] 10.6 Sicherung für Mandant 2 [Infra]
  - Schema `m_vhf_gsk` und Bucket `urinet-archiv-vhf-gsk` in Sicherung, Zweitkopie und `backup-gate.sh` aufnehmen; Restore-Probe stellt beide mit wieder her
  - _Anforderungen: 1.5_

## Welle 11 — Öffentliche Schnittstelle und Plugin (parallel)

- [x] 11.1 Webseiten-Schlüssel und Zugriffsstufen [Backend]
  - Geändert 13.11.2026 (E-B, E-C; bisher «Site-Key und Zugriffsstufen» mit `SiteKeyAuthMechanism`): Anmeldung der Lese-Schlüssel über `ApiTokenAuthMechanism` (Scopes aus Aufgabe 11.7), `@WebseitenZugang`, Header `X-Archiv-Zugriff` (drei Stufen, Kappung durch den Schlüssel, 403/400 nach 5.9) und `X-Archiv-Client`, Filter auf `veroeffentlicht`, `zugriff = ANY(:stufen)` und Vertraulichkeit `NORMAL`, 404 bei zu hoher Stufe, kein CORS; Domain-Typ `Zugriffsstufe`
  - Vorstufe (entschieden von Fabian am 14.11.2026): mandantenneutral, vor Gate Phase 2 und Zuschlag; Schlüssel, Dokumente und Szenarien mit dem Mandanten `urinet` bzw. Testmandanten, ohne Domain und Seed von VHF/GSK
  - Umsetzung (Spec-PR #211 für den Vorzieh-Entscheid; Umsetzungs-PR #217; Stand: umgesetzt, Entscheide offen; offen für Fabian: Budget, Grenze je Webseite, Kontakt der Geschäftsstelle (Req 6.3: ohne `archiv.webseite.chat.geschaeftsstelle` nennt der Chat nur «die Geschäftsstelle», nicht `gs@vhf-gsk.ch`), `X-Archiv-Client`, Namen in Freitext; vor dem Merge mit #216 abstimmen, siehe design.md): wiederverwendbare Schicht ohne Endpunkte (die kommen mit 11.2): `Zugriffsstufe`, `Lesezugriff` (Domain), `FesterFilter` (Abfragebaustein), `LeseZugriffFilter` und `LeseAnfrage` (Header, Fehlerabbildung, Identität); Szenarien `@req-3.6`, `@req-3.7`, `@req-5.2`, `@req-5.3`, `@req-5.5`, `@req-5.6`, `@req-5.9` in `features/integration/zugriffsstufen.feature` gegen die Probe `/public/v1/probe/stufe|dokumente` (nur `src/test`); JUnit `ZugriffsstufeTest`, `FesterFilterTest`; Einzelheiten in design.md, «Umsetzung (Aufgabe 11.1)»
  - _Anforderungen: 3.6, 3.7, 5.2, 5.3, 5.5, 5.6, 5.9_
  - _Abhängig von: 11.7_

- [ ] 11.2 Endpunkte `/public/v1` [Backend]
  - Liste, Suche (Hybrid), Details, Datei-Link (5 Min., Protokoll mit gekürzter IP)
  - Spec 01 ergänzt: Suche ohne Dateinamen (`mitDateiname=false`, Spec 01, Req 3.13) braucht einen Index oder Vorfilter: `ts_filter(volltext_tsv, '{a,b,c}')` nutzt den GIN-Index nicht und durchsucht sonst alle Dokumente des Mandanten (Spec 01, design.md, «Umsetzung 4.10»; Review #104); Index oder Vorfilter anlegen, falls Spec 02 (Aufgabe 7.1) ihn noch nicht angelegt hat
  - Spec 05 ergänzt: Liste filtert zusätzlich nach den Klassifikationen mit `oeffentlich_filter` (z. B. `?fachgebiet=bad`), gibt nur diese aus; fester Sicherheitsfilter unverändert davor; reservierte Parameter in Spec 05 design.md (Spec 05, Req 8.6)
  - Ergänzt 13.11.2026: Endpunkte `/public/v1/filter` (nur Reihen und Werte bis zur Stufe) und `/public/v1/umleitung` (alte Links, bis zur Stufe); Antwortfelder nach design.md «Antwortfelder» (nur `dokumentId`, keine Seiten-URLs); Datei-Link nur für Stufe `oeffentlich` (Stream in Aufgabe 11.8); feste Parameter um `seit`, `max`, `referenz`, `groesse`, `status`, `url` ergänzt
  - Umsetzung Teil 1 (PR #216; Vorstufe, mandantenneutral, getestet mit `urinet` und Testmandanten, ohne Seed und Domain VHF/GSK): `GET /public/v1/dokumente`, `/suche`, `/dokumente/{id}`, `/dokumente/{id}/datei`, `/filter`, `/umleitung`; `WebseitenLesenResource`, `WebseiteLesen`, `WebseiteSuche`, `WebseiteDatei`, `SqlWebseitenLese`, `Sichtregel.fuerWebseite` (fester Filter in jeder Teilabfrage der Hybridsuche), Tabelle `alte_url` (Changeset `031-alte-url`), `AlteUrl`, `IpAdresse.gekuerzt()`; Vertrag `docs/schnittstelle/public-v1.yaml` mit den Endpunkten und den Headern `X-Archiv-Zugriff` und `X-Archiv-Client`; Szenarien `@req-5.1` bis `@req-5.5`, `@req-5.8` bis `@req-5.11`, `@req-15.3`, `@req-15.4` in `features/integration/webseiten-lesen.feature` (je Filterachse ein Beispiel gegen Liste, Suche, Details, Filter und Umleitung); Einzelheiten in design.md, «Umsetzung (Aufgabe 11.2)». Abgleich 17.11.2026: Stream für `mitglieder` und `vorstand` mit PR #221 (11.8), Fundstellen mit PR #225 (11.13) und Grenzen je Schlüssel mit PR #220 umgesetzt. Offen bleiben Filter nach den Klassifikationen mit `oeffentlich_filter` und deren Ausgabe in Liste und `/filter` (Spec 05, Req 8.6); deshalb kein Haken. Drosselung und Vertrags-CI bleiben Aufgabe 11.6.
  - Entschieden von Fabian am 16.11.2026 (PR #216): `seite` der Liste ist 1-basiert und in Version 1 fest; Datei-Link nur für Stufe `oeffentlich`, `mitglieder` und `vorstand` sind 404 bis Aufgabe 11.8; Suche über Partner und Tags (Gewicht B) bleibt
  - _Anforderungen: 5.1, 5.4, 5.8, 5.10, 5.11_
  - _Abhängig von: 11.1, 11.6_

- [x] 11.3 Öffentlicher Chatbot [Backend]
  - `OeffentlicherChatAgent` mit eingeschränkten Werkzeugen, Quellen mit Reihe/Ausgabe/Link, Hinweis auf Geschäftsstelle, Rate-Limit 20/h, `mandant_budget`, Speicherung 90 Tage ohne Personenbezug
  - Ergänzt 13.11.2026: Werkzeuge mit `:stufen` aus der Anfrage (drei Stufen); Quellen mit `dokumentId`, Titel, Reihe, Ausgabe (Link bildet die Webseite); 429 `BUDGET` und `ZU_VIELE_ANFRAGEN` als Problem Details, beide im selben PR in die Fehlercode-Tabelle von Spec 01 design.md eintragen, `ZU_VIELE_ANFRAGEN` falls noch nicht eingetragen (Spec 03 design.md, Error Handling)
  - Umsetzung (Vorzieh-Entscheid 16.11.2026; PR siehe PR-Text): `OeffentlicherChatAgent` (`OeffentlicherAgent`, `OeffentlicheWerkzeuge` mit nur `dokumente_suchen` und `dokument_zeigen`), fester Filter in zwei Schichten (`Sichtregel.fuerWebseite` wie 11.2, `OeffentlicheDokumente`), Quellen mit `dokumentId`, Titel, Reihe, Ausgabe, fester Hinweis auf die Geschäftsstelle, Grenzen im Speicher (20/h je Client, je Webseite), `public.mandant_budget` (Monat), `oeffentliche_frage` ohne Personenbezug mit Löschjob (Standard 90 Tage, je Mandant einstellbar, VHF/GSK 30 Tage laut Zusage an den Verband), `BUDGET` in `Fehlercode` und Spec 01 design.md, Endpunkt im Vertrag `public-v1.yaml`; Szenarien `@req-6.1`–`@req-6.5` in `features/integration/oeffentlicher-chat.feature`, JUnit `OeffentlicheGrenzenTest`, `OeffentlicheFragenAufraeumenTest`, `OeffentlicheGespraecheTest`, `FrageBereinigungTest`, `OeffentlicherSystemPromptTest`, `SuchFilterWebseiteTest`; Einzelheiten in design.md, «Umsetzung (Aufgabe 11.3)»
  - _Anforderungen: 6.1–6.5_
  - _Abhängig von: 11.1_

- [ ] 11.4 WordPress-Plugin Grundgerüst [Frontend]
  - Einstellungsseite (Lese- und Erfassungs-Schlüssel verschlüsselt; geändert 13.11.2026, bisher «Site-Key»), Client mit Fehlerbehandlung (Problem Details) und Transients, Zugriffsstufe aus Login/Rolle (drei Stufen, Rollen für Mitglieder und Vorstand)
  - Client nur nach `docs/schnittstelle/public-v1.yaml`, unbekannte Felder ignorieren; Mock des Archivs aus dieser Datei
  - wp-env für lokale Entwicklung, PHPUnit
  - _Anforderungen: 7.1, 7.3, 7.4, 7.5, 7.7, 11.1, 11.6_
  - _Abhängig von: 11.6_

- [ ] 11.5 Shortcodes, Blöcke, Download, Chatbot [Frontend]
  - `[urinet_archiv_liste]`, `[urinet_archiv_suche]`, `[urinet_chatbot]` + Gutenberg-Blöcke; Download-Route mit 302; WP-REST-Proxy für den Chat (SSE), `assets/chatbot.js`
  - Browser-Szenarien mit Cucumber.js + Playwright gegen wp-env; Barrierefreiheit prüfen (axe), Theme-Übernahme
  - Spec 05 ergänzt: Filter der Liste nach den Klassifikationen mit `oeffentlich_filter`, nur diese werden angezeigt (Spec 05, Req 8.6)
  - Geändert 13.11.2026 (E-D): Download-Route `oeffentlich` → 302, `mitglieder`/`vorstand` → Stream blockweise durchreichen (cURL-Write-Callback, keine temporäre Datei) mit `Cache-Control: private, no-store`, höchstens 2 gleichzeitige Downloads je Person, Meldung bei 429; Filter aus `/public/v1/filter` (Cache je Stufe); Treffer verlinken mit `#page=<n>` auf die Fundstelle
  - Ergänzt 13.11.2026: Dokumentseite `/urinet-archiv/dokument/{id}`, alle Links auf Dokumente bildet das Plugin; Umleitung alter Links mit 301 über `/public/v1/umleitung`
  - Reihenfolge (entschieden 16.11.2026): Der Download für `mitglieder` und `vorstand` setzt Aufgabe 11.8 voraus; 11.8 kommt vor dem Plugin-Download
  - _Anforderungen: 7.2, 7.6, 7.8, 5.4_
  - _Abhängig von: 11.2, 11.4, 11.8, 11.13_

- [ ] 11.6 Vertrag und Versionierung der Webseiten-Schnittstelle [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-B, neutrale Server-API); Nummer 11.6 als nächste freie der Welle 11
  - `docs/schnittstelle/public-v1.yaml` (OpenAPI 3.1) für Lesen und Erfassung; Pfade unter `/public/` über `mp.openapi.scan.exclude.packages` nicht in `frontend/openapi/` und nicht im Angular-Client, Vertrag in eigenem Lauf erzeugen (Weg wählen); `PublicVertragTest` (Code = Datei); CI-Schritt im Build-Job mit vollständiger Historie: nur additive Änderungen gegenüber `origin/main` (Werkzeug wählen); Header `Deprecation`, `Sunset`, `Link` über `archiv.webseite.v<n>.sunset`; Problem Details auch unter `/public/` (`ProblemAnmeldung`), `Cache-Control: no-store`
  - Traefik: eigener Router für `/public/` mit eigener Drosselung je IP (Labels in `deploy/infomaniak/compose.yml`, nur über den Deploy-Workflow)
  - Vorstufe (entschieden von Fabian am 14.11.2026): vor Gate Phase 2 und Zuschlag, nur mandantenneutral; getestet mit dem Mandanten `urinet` bzw. Testmandanten, ohne Domain und Seed von VHF/GSK. Der Vertrag enthält, was es bei der Umsetzung gibt (Problem Details, Header, Versionierung); die Endpunkte kommen additiv mit 11.2 und 11.10 dazu
  - Umsetzung Teil 1 (PR #140): Vertrag `docs/schnittstelle/public-v1.yaml` ohne Endpunkte, Reroute `/public/` → interner Pfad (`WebseitenRoute`), `PublicVertragTest`, Ausschluss aus der App-OpenAPI, Problem Details und `Cache-Control: no-store` unter `/public/`, Abkündigung über `archiv.webseite.v<n>.sunset`, Skript `pruefe-vertrag.sh` mit Selbsttest; offen: CI-Schritt, Traefik-Router
  - Reihenfolge (entschieden 16.11.2026): Die Drosselung im Reverse-Proxy kommt vor dem Livegang der Webseite von VHF/GSK; bis dahin nur Urinet und Testmandanten
  - Vorschlag aus PR #241: Traefik-Drosselung für `/public/`, Labels in `deploy/infomaniak/compose.yml`, Werte und Begründung in design.md; Haken erst nach Deploy und Prüfung. Offen bleibt der CI-Schritt «Vertrag nur additiv» gegen den echten PR-Diff; `VertragPruefungTest` führt nur den Werkzeug-Selbsttest in einem Wegwerf-Repository aus. Die Endpunkte von Lesen und Erfassung sind mit PR #216, #253 und #263 im Vertrag nachgeführt.
  - _Anforderungen: 11.1–11.6, 15.2–15.4_

- [x] 11.7 Webseiten-Schlüssel [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-B); Trennung Lese-/Erfassungs-Schlüssel vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Neues Changeset `db/public` (`api_client`: Scopes `webseite-lesen`, `webseite-erfassung`, Spalten `webseite`, `hoechste_stufe`, `gueltig_bis`, `absender_pruefen`); `ApiTokenScope` erweitern; Identität mit `webseite` und `hoechste-stufe`; `ZugangFilter` und `EndpunktWaechterTest` für `@WebseitenZugang`
  - Kommandos `archiv webseite schluessel erstellen|rotieren|widerrufen` (Wert nur auf stdout); `/api/v1/api-tokens` listet und widerruft Webseiten-Schlüssel, legt sie nicht an; Absendernetze je Webseite aus UTF-8-Properties mit literalen CIDR-Werten in `archiv.webseite.absender-datei`, nach erfolgreicher Schlüsselprüfung ausgewertet, fehlender/ungültiger Eintrag → ablehnen; Mandant deaktiviert → Schlüssel gesperrt (403 `MANDANT_FEHLT`)
  - Prüf-Cache für Webseiten-Schlüssel (HMAC des Geheimnisses je Schlüssel-ID, `PT5M`, Zeile in `api_client` bei jeder Anfrage gelesen); Szenario: fremde `ua_…`-Anfragen belegen die `HashSperre`, die Webseite wird trotzdem bedient; Widerruf wirkt trotz Cache sofort
  - Leistung messen: Trefferquote des Prüf-Caches und Wartezeiten der `HashSperre` unter Last mit fremden Anfragen (Open Points in design.md)
  - Spec 01 ergänzt: Scopes in Spec 01, 2.5 und design.md «Umsetzung (Aufgabe 1.5)»; `gueltig_bis` nach Rotation und Prüf-Cache in Spec 01 design.md «Sicherheit» (Spec 01, Req 2.5)
  - Umsetzung (PR #168): Changeset `010-webseiten-schluessel`; Szenarien `@req-12.1`–`@req-12.8` in `features/sicherheit/webseiten-schluessel.feature`; Kommandos `archiv webseite schluessel erstellen|rotieren|widerrufen`; Nachweis: `mvn -o test -Dquarkus.http.test-port=0` (Cucumber vollständig, 871 Szenarien grün) und die JUnit-Klassen `ApiTokenPruefungTest`, `WebseitenSchluesselVerwaltungTest`, `WebseitenAbsenderPruefungTest`, `WebseitenSchluesselConstraintTest`; Messung 15.11.2026: Trefferquote 100/100, `UEBERLASTET` nach 106–111 ms (design.md, Schlüsselverwaltung und Open Points)
  - Vorstufe (entschieden von Fabian am 14.11.2026): vor Gate Phase 2 und Zuschlag, nur mandantenneutral; Schlüssel und Szenarien mit dem Mandanten `urinet` bzw. Testmandanten, ohne Domain und Seed von VHF/GSK. Das Changeset `db/public` nimmt die nächste freie Nummer (009 ist durch Spec 02, Aufgabe 6.3 belegt)
  - _Anforderungen: 12.1–12.8, 15.3_

- [x] 11.8 Dateiauslieferung gemischt [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-D)
  - `DateiAuslieferung`: `oeffentlich` → vorsignierte URL (5 Min.), sonst Stream in Blöcken von 64 KiB mit Gegendruck; Plätze gesamt und je Schlüssel, 429 `ZU_VIELE_ANFRAGEN` (Fehlercode im selben PR in Spec 01 design.md eintragen, falls noch nicht eingetragen); Leerlauf- und Gesamt-Zeitlimit; Freigabe bei Abbruch; Protokoll `HERUNTERLADEN` in einer Transaktion, die vor dem ersten Byte endet; keine Dateinamen in Headern; `DateiStreamTest`; Messfall «50 MB an einen langsamen Client»
  - Reihenfolge (entschieden 16.11.2026): 11.8 kommt vor dem Plugin-Download (11.5); bis dahin liefert `/public/v1/dokumente/{id}/datei` für `mitglieder` und `vorstand` 404
  - Umsetzung (Vorzieh-Entscheid 16.11.2026; mandantenneutral, getestet mit `urinet` und Testmandanten): `GET /public/v1/dokumente/{id}/datei` liefert für `oeffentlich` weiter `{url, gueltigBis}`, für `mitglieder` und `vorstand` `application/pdf` als Stream (statt 404); `DateiAuslieferung`, `WebseiteDatei` (kurze Transaktionen), `DateiPlaetze`, `StreamLauf`, `StreamWaechter`, neu `AblagePort.oeffnen` und `DokumentDatei.finden`; Plätze `archiv.webseite.streams-gesamt` und `streams-je-schluessel` (je 8), Leerlauf `PT30S`, Gesamtdauer `PT15M`; Protokoll `HERUNTERLADEN` nach Platz und Öffnen in einer kurzen Transaktion vor dem ersten Byte; `Cache-Control: private, no-store`, `nosniff`, `Content-Disposition: inline` ohne Dateinamen, `Range` ignoriert (`Accept-Ranges: none`); Vertrag `public-v1.yaml` additiv (Stream-Antwort, 429); Szenarien `@req-13.1`–`@req-13.8` in `features/integration/webseiten-datei.feature` und `webseiten-lesen.feature`, JUnit `DateiStreamTest`, Messfall «50 MB an einen langsamen Client» (Szenario `@req-13.3`); Einzelheiten und Auslegungen in design.md, «Dateiauslieferung und Stream»; die Grenze je Schlüssel (Anfragen je Sekunde) bleibt Aufgabe 11.11
  - _Anforderungen: 13.1–13.8, 5.4_
  - _Abhängig von: 11.1_

- [x] 11.9 Änderungs-Feed [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Neues Changeset `db/mandant`: `webseite_aenderung` mit Trigger `AFTER INSERT OR UPDATE OR DELETE` auf `dokument`, `webseite_feed_stand`; Cursor über `pg_current_xact_id()` und `pg_snapshot_xmin`; `/public/v1/aenderungen` (`stufe` = tiefere sichtbare Stufe, nie über dem Schlüssel) und `/public/v1/erfassung/aenderungen` (Referenz nur der eigenen Webseite); Auftrag `FEED_BEREINIGEN` (90 Tage), 410 `CURSOR_ABGELAUFEN` (Fehlercode im selben PR in Spec 01 design.md eintragen); `AenderungFeedTest` mit Stufenwechseln und lang offener Transaktion; Warnung bei Stillstand des Horizonts
  - Umsetzung (Vorzieh-Entscheid 16.11.2026; mandantenneutral, getestet mit `urinet` und `vhf-gsk` als Testmandanten, ohne Seed und Domain von VHF/GSK): Changesets `db/mandant` 032 (`webseite_aenderung`, `webseite_feed_stand`, Trigger `webseite_aenderung_erfassen`) und 033 (`webseite_referenz`, von 11.10 vorweggenommen); `GET /public/v1/aenderungen` (Stufe des Schlüssels, `@FeedZugriff`) und `GET /public/v1/erfassung/aenderungen`; Cursor `v1.<xid>.<id>` mit Horizont `pg_snapshot_xmin` aus demselben Schnappschuss; `FEED_BEREINIGEN` als täglicher Worker-Takt (90 Tage), 410 `CURSOR_ABGELAUFEN` (Eintrag in Spec 01 design.md); `FeedHorizontWaechter` warnt bei Stillstand (`archiv.webseite.feed-stillstand`); Vertrag `public-v1.yaml` additiv; Szenarien `@req-14.1`–`@req-14.4` und `@req-15.1` in `features/integration/webseiten-feed.feature`, JUnit `AenderungFeedTest` (Trigger gegen festen Filter, alle Stufenwechsel, lang offene Transaktion, Bereinigung), `AenderungFeedLogikTest`, `FeedCursorTest`, `LeseAenderungTest`, ArchUnit `keinHttpClientInDerWebseitenSchnittstelle` (Req 14.5); Einzelheiten und Auslegungen in design.md, «Änderungs-Feed»
  - _Anforderungen: 14.1–14.5_
  - _Abhängig von: 11.7_

- [x] 11.10 Erfassung über eine Webseite [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-A, Erfassung «beides»)
  - Endpunkte unter `/public/v1/erfassung` (Upload multipart ohne URL-Abruf, Status über ID und Referenz, Liste, Datei ansehen als Stream, Eingangskorb, Dokumentarten, Bearbeiter melden, Bestätigen, Ändern, Veröffentlichen/Zurückziehen, Wiederholen; kein Löschen, kein Verwerfen), delegiert an die Services von Spec 01; Rechte nur nach der Positivliste A1 bis A8 und A10 der Rolle `geschaeftsstelle` (`ErfassungRechteTest`); `veroeffentlicht` in `PATCH`/Bestätigen → 400
  - Idempotenz mit `pg_advisory_xact_lock` auf (Webseite, Referenz) vor der Dublettenprüfung; verworfene Referenz frei; Dublette mit ID nur, wenn sichtbar (Spec 01, 3.4 ergänzt)
  - Neue Changesets `db/mandant`: `webseite_referenz`, `zugriff_protokoll.bearbeiter`, `dokument_version.bearbeiter`, Quelle `WEBSEITE`; Header `X-Archiv-Bearbeiter`; Bearbeiter im Benutzerverzeichnis (Spec 01, 10.10 ergänzt); 409 `REFERENZ_VERGEBEN` (Fehlercode im selben PR in Spec 01 design.md eintragen)
  - Erweiterte Isolationsszenarien (12.3): Upload und Statusabruf nach Referenz über Mandanten- und Webseitengrenze, Erfassungs-Schlüssel gegen Lesewege (`@req-1.4`, `@req-9.1`, `@req-9.4`) sind seit PR #287 belegt; die in «Umsetzung Teil 1» noch fehlenden Endpunkte und die Scope-Matrix sind damit nachgeführt. Die übrigen Reste der Gesamtaufgabe 12.3 bleiben dort offen.
  - Umsetzung Teil 1 (PR #253): Upload, Referenzbindung und -sperre, Status/Details, gefilterte Liste, Bearbeitermeldung samt Protokollierung; Changeset 033 wird verwendet, Changeset 039 ergänzt Quelle und Protokollspalte. Die Upload-Grenze prüft den echten Endpunkt. Der damals offene Teil 2 ist mit PR #263 umgesetzt.
  - Umsetzung Teil 2 (PR #263): Bestätigen/Ändern mit strikter Feld- und Typprüfung, gemeinsamen Fachregeln, A8 und `If-Match`; Veröffentlichen/Zurückziehen mit Fassung und festem Grund; Wiederholen A10; Eingangskorb, aktive Arten mit Formularfeldern; Original-/PDF-A-Prüfstream ohne Speicheradresse. Changeset 040 ergänzt den Fassungs-Bearbeiter. Nachtrag #253: unsichtbare Dublette ohne Referenzbindung getestet, Dateigrenze 1/s mit Spitze 2, deterministische JDBC-Gegenprobe ohne Referenzsperre. Einzelheiten und Auslegungen in design.md, «Erfassung».
  - Abdeckung 10.1–10.14: 10.1–10.3 Upload/Referenzen/Races und URL-Verbot aus Teil 1; 10.4 Status/Details plus Eingangskorb und vollständige Finanz-/Vertragsmetadaten; 10.5 gemeinsame Pflege mit Versions-, Pflichtfeld-, Schema- und Grundprüfung; 10.6 Veröffentlichung/Rückzug; 10.7 kein Löschen; 10.8 feste Rechte/kein Verwerfen/Wiederholen; 10.9 Bearbeiter in Zugriff und Fassung; 10.10 Konflikt zwischen Web-App und Webseite; 10.11 Dateinamen ausgeschlossen; 10.12 append-only Bearbeitermeldung aus Teil 1; 10.13 Liste/Filter/Referenzen plus Formularlisten; 10.14 Prüfdatei in jedem Status ausser VERWORFEN. Szenarien in `webseiten-erfassung.feature` und `webseiten-pflege.feature`, ergänzend `ErfassungsMetadatenTest`, `WebseitenErfassungTest`, `WebseitenGrenzenTest`, `DateiStreamTest`; keine offene Teilanforderung aus Requirement 10. Die umfassende Isolation und der OWASP-Nachweis bleiben Aufgabe 12.3.
  - Testnachweis Teil 1: synthetische Szenarien `@req-10.1`–`@req-10.4`, `@req-10.9`, `@req-10.11`–`@req-10.13`, `@req-1.4`, `@req-9.1`, `@req-9.4`, `@req-12.1` und `@req-15.1`, einschliesslich zweier paralleler Uploads derselben Referenz; `ErfassungRechteTest` prüft die Positivliste A1–A8/A10 gegen die Rechtematrix, `WebseitenErfassungKnapperHeapTest` den 96-MiB-Datei-Hash mit 64-MiB-Heap. Die ergänzenden Nachweise von Teil 2 stehen im vorigen Absatz und in PR #263.
  - _Anforderungen: 10.1–10.14, 3.1_
  - _Abhängig von: 11.7, 11.8_

- [ ] 11.11 Grenzen je Schlüssel [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Token-Bucket je Schlüssel (Startwerte in design.md «Grenzen»), Uploads je Stunde, 429 `ZU_VIELE_ANFRAGEN` mit `Retry-After` (Fehlercode im selben PR in Spec 01 design.md eintragen, falls noch nicht eingetragen); Grenze des Chats je Client-Hash bleibt; Startwerte messen
  - Reihenfolge (entschieden 16.11.2026): 11.11 kommt vor dem Livegang der Webseite von VHF/GSK, weil jede öffentliche Suche Embedding und Reranker auslöst; bis dahin nur Urinet und Testmandanten
  - Teilumsetzung (PR #220, #253, #263, #258): `WebseitenGrenzen`, `TokenBucket`, `Grenzklasse`, `WebseitenGrenzenFilter`, `@WebseitenGrenze`, `MicrometerWebseitenGrenzen`; Einstellungen `archiv.webseite.grenzen.*`; strengerer Bucket für die Suche (Startwert 2/s, Spitze 10, Entscheid Fabian offen); Vertrag mit 429 und `Retry-After` an den lesenden Endpunkten; `@req-15.1`/`@req-6.4` in `features/integration/webseiten-grenzen.feature`; Einzelheiten in design.md, «Grenzen». Echte Upload-Grenze mit PR #253, engere Prüfdateigrenze mit PR #263 und Fehlerpfad-/Kennzahlnachweis mit PR #258 umgesetzt. Offen bleibt die Messung der Startwerte am Livegang (Kennzahl `archiv_webseite_grenze_abgelehnt_total`); deshalb kein Haken.
  - _Anforderungen: 15.1, 6.4_
  - _Abhängig von: 11.7_

- [ ] 11.12 Plugin: Änderungs-Feed und Erfassung [Frontend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-A); Feed vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - `class-feed.php` (WP-Cron, Transients ab `stufe` aufwärts verwerfen, 410 → alles verwerfen; Hintergrund nur Feed und Statusabruf über die Referenz); `class-erfassung.php` (WP-Admin für die Rolle der Geschäftsstelle, Liste, Formular aus `/erfassung/dokumentarten`, Angaben je Referenz als Post-Meta und Vorbelegung beim Bestätigen, Datei ansehen, Datei nicht in der Mediathek, Referenz und Bearbeiter mit Meldung über `PUT /erfassung/bearbeiter/{kennung}`, `If-Match`, 412 → neu laden, Veröffentlichen erst nach `ARCHIVIERT`, Wiederholen, «Entfernen» = Zurückziehen)
  - Browser-Szenarien mit Cucumber.js + Playwright gegen wp-env
  - _Anforderungen: 7.9, 7.10_
  - _Abhängig von: 11.9, 11.10_

- [x] 11.13 Fundstelle in Suchtreffern [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Spec 02 ergänzt: `chunk.seite_von`/`seite_bis` über `Seitentext.von` (Spec 04) mit derselben Plausibilitätsprüfung gegen `dokument.seiten` (erledigt mit Spec 02, Aufgabe 6.3: Changeset `db/mandant` 021 legt die Spalten an, der `Chunker` füllt sie über `gemeinsam/domain/Seitentext`; kein neues Changeset, keine Nachberechnung nötig); `HybridSuche` gibt den besten Chunk mit (Spec 02, Aufgabe 6.4); öffentliche Suche liefert `fundstelle.seite`
  - Umsetzung: `HybridSuche` (Spec 02, 6.4) gab den besten Abschnitt samt Seitenbereich schon mit, `WebseiteSuche` reichte die erste Seite schon als `fundstelle.seite` durch; neu mit 11.13: `WebseiteSuche.plausibel` lässt die Fundstelle weg, wenn die Seite nicht in `1..dokument.seiten` liegt (veralteter Abschnitt, `seiten` leer), Vertrag `public-v1.yaml` nennt den Link `#page=<n>` (nur Beschreibung, additiv; das Archiv liefert keine Seiten-URL), Szenarien `@req-5.7` in `features/integration/webseiten-fundstelle.feature` (Seite des besten Abschnitts, ohne Seitenbereich, nur Volltext, Seite ausserhalb von `seiten`, Dokument über der Stufe, kein Abschnittstext in der Antwort); Chat-Quellen (11.3) nennen die Seiten über `Fundstelle` schon (`OeffentlicheAbfragen`), dort ohne Prüfung gegen `seiten`; kein neues Changeset
  - Folge 11.13a (erledigt): dieselbe Plausibilität für die Chat-Quellen. Die Regel liegt einmal in `Seitentext.plausibel` (`gemeinsam/domain`), `WebseiteSuche` und `Fundstelle.seitenText` nutzen sie; `OeffentlicheAbfragen` (neu `OeffentlichesDokument.seiten`) und `DokumenteSuchen` (neu `WerkzeugDaten.seiten`) lassen `seiten` weg, wenn die erste Seite nicht in `1..dokument.seiten` liegt oder `dokument.seiten` fehlt; liegt nur die letzte Seite ausserhalb, bleibt die erste allein. Szenarien `@req-5.7` in `oeffentlicher-chat.feature` und `werkzeuge-hybrid.feature`; Vertrag `public-v1.yaml` unverändert (die Chat-Quellen tragen keine Seiten)
  - _Anforderungen: 5.7_
  - _Abhängig von: 11.2; Spec 02: 6.3, 6.4_

## Welle 12 — Mandantenverwaltung und Abnahme

- [x] 12.1 Monatsbericht pro Mandant [Backend]
  - Auftrag `MANDANT_BERICHT` (Speicher, Dokumente, Tokens nach Zweck, Chat-Fragen), E-Mail an Betreiber
  - Umsetzung (PR #224): Changeset `034-mandant-bericht`, Bericht, tägliche Wiederholung fehlender Läufe, mandantengetrennte Speicherung und Betreiber-Mail; Zeitfenster und Req-8.1-Szenarien ergänzt
  - Nachweis: `mvn -o test -Dquarkus.http.test-port=0 -Dtest=MandantBerichtSzenarienTest,MandantBerichtHandlerTest,MandantBerichtFensterTest` (Feature `features/mandant/monatsbericht.feature`: fünf Szenarien `@req-8.1` für getrennte Mandanten, Monatsgrenze, Idempotenz, keine Inhalte und Planung ohne deaktivierte Mandanten; Mailfehler mit Wiederholung in `MandantBerichtHandlerTest`)
  - Auslegungen (Review entscheidet): «Speicher» zählt alle Objektversionen (`listObjectVersions`), nicht nur die aktuellen Objekte; Speicher und Dokumente je Status sind der Stand bei der Erhebung (nicht zum Monatsende), nur KI-Tokens und Chat-Fragen sind auf das Fenster begrenzt; Mail ist mindestens-einmal; ohne Empfänger bleibt der Bericht gespeichert und die Planung reiht täglich neu ein (WARN als Betreibersignal)
  - _Anforderungen: 8.1_

- [ ] 12.2 Vollexport und Abbau [Backend]
  - `MANDANT_EXPORT` (alle Jahre, Original + Arbeitsfassung + PDF/A, Metadaten JSON/CSV, Manifeste); Kommando `mandant abbauen` mit 30-Tage-Frist, Liste gesperrter Objekte und Ablaufdatum der Sicherungsstände
  - Teilstand PR #239: Vollexport und wiederholbarer Abbau mit Trockenlauf, Bestätigung, Sperrprüfung und Bericht umgesetzt. Req 8.3 angepasst (Entscheid Fabian 17.11.2026): gemeinsame Realm-Clients bleiben bestehen, der Abbau entfernt den Mandantenzugang, ein Client-Lifecycle-Port entfällt; siehe design.md «Mandantenverwaltung». Bericht an Req 8.3 angeglichen: Zustand `ABGESCHLOSSEN` ohne Zusatz, Bericht nennt Mandantenzugang und den Handschritt (Client, Benutzer); `MandantAbbauTest` belegt, dass der abgebaute Mandant nicht mehr auflösbar ist. Haken bleibt offen, bis auch die Ergänzungen von Spec 04 (OKF-Export, 15.5) und Spec 05 (Klassifikationen und Konfiguration im Vollexport, 17.5/17.7) umgesetzt sind.
  - Spec 04 ergänzt den Vollexport um den OKF-Export der Wissensbasis (Spec 04, 17.5; Aufgabe 15.5)
  - Spec 05 ergänzt: Der Vollexport enthält je Dokument die Klassifikationen mit Code und Name und die Konfigurationsversion sowie die aktuelle Konfigurationsdatei und alle Konfigurationsversionen (Spec 05 design.md, «Folgeänderungen»)
  - _Anforderungen: 8.2, 8.3, 8.5_

- [ ] 12.3 Isolations- und Sicherheitstests [QA]
  - `MandantenIsolationIT` (Szenarien `@req-1.4`, `@req-9.1`) über alle Wege; OWASP-API-Prüfliste und ZAP-Baseline gegen Staging, Prüfbericht unter `docs/abnahme/`; Befunde beheben
  - Spec 04 erweitert `MandantenIsolationIT` um die Wissensbasis (Spec 04, 11.5; Aufgabe 16.1)
  - Spec 05 erweitert `MandantenIsolationIT` um Arten, Klassifikationen, Regeln, KI-Texte und Konfigurationsversionen; liegt der Test noch nicht vor, legt ihn Spec 05, Aufgabe 18.5 an (Spec 05, Req 9.3)
  - Ergänzt 13.11.2026: `MandantenIsolationIT` mit Lese- und Erfassungs-Schlüsseln beider Mandanten über Lesen, Erfassung, Stream, Feed und Referenzen; zwei Schlüssel desselben Mandanten mit verschiedenen Stufen (9.4); OWASP-Prüfliste umfasst Erfassung, Stream, Feed, Filter und Umleitung, Stufe `vorstand` und die Erschöpfung der Schlüsselprüfung durch fremde Anfragen
  - Umsetzung Teil 1: Vorhandene Teilnachweise auf `origin/main` (interne Dokument-IDs, Schemas/Buckets, Export, Worker-Kontext, Protokoll und öffentliche Feed-/Chat-Grundlagen) werden nicht doppelt gebaut. Ergänzt wurden Szenarien zu fremden Mandanten bei Webseiten-Liste, Suche, Filter, Datei-Link/Stream, Umleitung, Erfassungs-Feed-Referenzen sowie zum Chat mit fremder Gesprächs-ID; die Stufe `oeffentlich` wird zusätzlich für Lesen, Datei, Feed und Chat gegen `mitglieder`/`vorstand` geprüft. Der Haken bleibt bis zur Gesamtaufgabe offen.
  - Entscheid zur Form (Review PR #235): «MandantenIsolationIT» ist vorläufig ein Tag-Verbund, keine eigene Klasse. Die Szenarien tragen `@req-1.4` und `@req-9.1` (Lesen, Datei/Stream in beide Richtungen mit Gegenprobe im eigenen Mandanten, Filter, Umleitung, Feed-Referenzen, Chat) bzw. `@req-9.4` (Stufen); der Nachweis ist `grep -rl "@req-1.4" backend/src/test/resources/features`. Die Matrix `sicherheit/mandanten-isolation.feature` liegt seit PR #287 vor; die Gesamtaufgabe bleibt wegen der unten genannten Reste offen.
  - Umsetzung Teil 2 (PR #287, 17.11.2026, aufbauend auf 11.10 Teil 1/2): `sicherheit/mandanten-isolation.feature` ergänzt den Tag-Verbund um die ausführbare Scope-Matrix beider Schlüsselarten und Mandanten über alle automatisch ermittelten produktiven Endpunkte, fremde/unbekannte IDs bei jeder Dokumentaktion mit eigener positiver Gegenprobe, Hilfslisten/Formulararten, gleiche Referenzen in zwei Mandanten und zwei Webseiten sowie getrennte Bearbeiterverzeichnisse. Die bestehenden Stufen-, Feed-Referenz- und Chat-Szenarien aus Teil 1 bleiben die übrigen Nachweise; keine doppelte Klasse mit eigenem Quarkus-Start.
  - Weiter offen ausserhalb dieses Teilauftrags: Ablage/S3-Link und Aufträge je Mandant sowie Zuordnung der älteren internen Teilnachweise (Download, Export, Suche, Chat) zur Gesamtmatrix. Eine Kennzahlen-REST-Route für einen Mandanten ist nicht vorhanden. ZAP/Staging-Prüfung bleibt offen für den Gesamtabschluss von 12.3 (Fabian, vor dem Livegang von VHF/GSK); der Haken bleibt deshalb offen.
  - Stand 17.11.2026 (Review PR #246): OWASP-Prüfbericht liegt unter `docs/abnahme/owasp-api-top10.md` vor (Codebericht, Quellstand `2ccc3ecf`); ZAP-Baseline gegen Staging bleibt offen, Haken bleibt offen. F1 (Erfassung) ist mit Teil 2 geschlossen (Code- und Testnachweis). Runde 16 ergänzt F2 (Quell-Drosselung und Vertrauensgrenze), F3 (atomare Chat-Budgetreservierung, eine letzte Frage darf überschreiten; Schreibfehler fail-open mit Kennzahl) und F4 (Test des Limiter-Fail-open-Pfads samt Kennzahl und Alarmvorschlag). Szenarien und Doku sind ergänzt; vollständiger lokaler `mvn verify`-Lauf war wegen ausgeschöpfter Docker-Adresspools nicht möglich, gezielte Tests liefen.
  - Ergänzung Teil 2: F1 im OWASP-Bericht mit Erfassungsnachweisen zu BOPLA, BFLA, API7 und API9 sowie Datei-/Request-/Ratengrenzen abgeschlossen. F2–F4 sind mit #258 umgesetzt und hier nicht verändert. Keine bestätigte ausnutzbare Lücke im begrenzt geprüften Erfassungspfad gefunden. Offen für Fabian: ZAP-Baseline gegen Staging.
  - _Anforderungen: 1.4, 9.1, 9.2, 9.4_

- [ ] 12.4 Einführung und Abnahme [Fabian]
  - Plugin auf der Verbandsseite installieren, Seiten mit Shortcodes/Blöcken gestalten, Geschäftsstelle schulen (30 Min.)
  - Ergänzt 13.11.2026: Lese- und Erfassungs-Schlüssel per Kommando anlegen und auf dem Webseitenserver hinterlegen; Schulung umfasst die Erfassung im WordPress-Admin
  - **Gate Phase 3:** Archiv online, Geschäftsstelle veröffentlicht selbständig eine neue Ausgabe
  - _Anforderungen: 9.3_
