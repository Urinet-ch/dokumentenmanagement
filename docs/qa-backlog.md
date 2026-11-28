# QA-Backlog

Offene Soll- und Kann-Punkte aus den Reviews vom 26. und 27.11.2026, abgeglichen mit
`origin/main`. Nur Punkte, die auf dem aktuellen Stand noch offen sind, werden aufgeführt.

| PR | Datei | Vorschlag | Status |
| --- | --- | --- | --- |
| #654 | `docs/verfahrensbeschreibung-erfassen-archivieren.md`; `.kiro/specs/01-archiv-kern/tasks.md` | Die freigegebene Fassung in den kanonischen Zielpfad und auf höchstens zwei Seiten bringen, vom Treuhänder gegenlesen lassen und als PDF/A ablegen. | Offen gemäss Aufgabe 5.7; der ergänzende Entwurf trägt zwar eine Freigabeangabe, Gegenlesen, Zielfassung und PDF/A-Nachweis fehlen weiterhin. |
| #656 | `.kiro/specs/07-einfuehrung-hilfe/design.md` | Die Einführungs-Matrix an die umgesetzten Rechte angleichen: «Ihre erfassten Dokumente» nur A1 ohne A7; für A5 nur sichtbare, bestätigte Dokumente beschreiben. | Teilweise erledigt durch #658 und #721; die Einführungs-Matrix auf `origin/main` nennt weiterhin A5 bei den eigenen Dokumenten und spricht beim Treuhänder pauschal von wartenden Dokumenten. |
| #661 | `frontend/package.json`; `frontend/cucumber.mjs` | Falls ein Szenario mit `@flaky` markiert wird, den separaten Lauf automatisiert ausführen und seinen Status sichtbar machen. | Bedingt offen: `e2e:flaky` ist ein manueller Befehl; auf `origin/main` ist aktuell kein Szenario mit `@flaky` markiert. |
