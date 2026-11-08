# UriNet Archiv – Arbeitsweise für Claude Code

Mandantenfähiges, GeBüV-konformes Dokumentenarchiv für Urinet Aschwanden und später VHF/GSK.
Entwickelt wird spec-driven im Kiro-Stil (`.kiro/`), umgesetzt mit Claude Code.

## Immer geltende Vorgaben
@.kiro/steering/product.md
@.kiro/steering/tech.md
@.kiro/steering/structure.md

## Specs
- `.kiro/specs/<nr>-<name>/requirements.md` – User Stories und EARS-Kriterien (Nummern x.y)
- `design.md` – Architektur, Komponenten, Datenmodell, Fehler, Tests
- `tasks.md` – Aufgaben als Checkboxen mit `_Anforderungen: x.y_`
- Wellen, Rollen und Gates: `docs/umsetzungsplan.md`

## Arbeitsweise
1. **Nur freigegebene Specs umsetzen.** Freigegeben ist, was gemergt auf `main` liegt. Keine Aufgabe aus einem offenen Spec-PR beginnen.
2. **Eine Aufgabe = ein Branch + ein PR.** Branch `feature/<spec>-<aufgabe>-<stichwort>`, z. B. `feature/01-2.6-ocr`. Aufgaben anderer Wellen nicht vorziehen.
3. **PR-Beschreibung** nennt die erfüllten Akzeptanzkriterien (`@req-x.y`) und die Szenarien bzw. Tests dazu; manuelle Nachweise werden verlinkt.
4. **Checkbox in `tasks.md`** im selben PR abhaken.
5. **Erkenntnisse** (Spikes, Abweichungen vom Design) im selben PR in `design.md` nachtragen.
6. **Requirements nie stillschweigend ändern.** Jede Änderung an `requirements.md` im PR ausdrücklich als Änderung ausweisen (Kriterium, alt → neu, Grund); bestehende Nummern nie umnummerieren, neue hinten anhängen.
7. **Offenes nicht raten.** Steht ein Wert als `[offen: …]` in der Spec, nachfragen statt erfinden.
8. **Nur über die Pipeline deployen.** Kein Zugriff auf Server oder Secrets aus der Sitzung; Deploy nur über den GitHub-Actions-Workflow.

## Definition of Done
Gilt wie in `tech.md`, Abschnitt «Tests und Qualität». Fabian prüft und merged jeden PR.
