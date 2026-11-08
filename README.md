# UriNet Archiv

Repository `Urinet-ch/dokumentenmanagement`. Mandantenfähiges Dokumentenarchiv mit KI-Erfassung und Chat-Suche für Urinet Aschwanden
(Mandant 1) und den Verband VHF/GSK (Mandant 2). Java/Quarkus, Angular-PWA, PostgreSQL,
Infomaniak (Schweiz).

## Spec-driven Development im Kiro-Stil, umgesetzt mit Claude Code
Die Specs folgen dem Kiro-Format; umgesetzt wird mit Claude Code, nicht mit dem Werkzeug Kiro.
- `CLAUDE.md` – Einstieg und Arbeitsweise für Claude Code (bindet die Steering-Dateien ein)
- `.kiro/steering/` – Produkt, Technik, Struktur (in jeder Sitzung aktiv)
- `.kiro/specs/01-archiv-kern/` – Phase 0 und 1
- `.kiro/specs/02-abgleich-chat/` – Phase 2
- `.kiro/specs/03-zweiter-mandant/` – Phase 3
- `docs/umsetzungsplan.md` – Wellen, Crew, Gates, Zeitplan, Risiken

Eine Spec ist freigegeben, wenn Fabian den PR auf `main` merged. Start: `docs/umsetzungsplan.md`, Abschnitt 3.
