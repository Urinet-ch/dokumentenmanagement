# CI-Prüfung bei «Ready for review»

## Befund zu PR #736

Am 28.11.2026 um 05:59:21 UTC wurde PR #736 auf «Ready for review» gesetzt. Drei Sekunden später starteten zwei
`pull_request`-Läufe für denselben Head `e8ce2f72`:

- Lauf `46114526690` endete nach 9 Sekunden mit `conclusion=skipped`. Alle zehn Jobs, darunter «Änderungsart
  (nur Spec?)» und «Spec-Prüfungen (nur Spec)», waren `skipped` und hatten null Schritte.
- Lauf `46114527300` endete erfolgreich nach rund 89 Sekunden. «Änderungsart» lief; «Spec-Prüfungen» führte Tests aus
  und war erfolgreich. Build, Frontend, Browser und Backend-Tests waren übersprungen.

Die Workflow-Datei hat für die Jobs `art` und `spec` dieselbe Bedingung:
`github.event_name != 'pull_request' || !github.event.pull_request.draft`. Die nachgelagerten Jobs hängen von `art`
ab. Damit erklärt ein `draft=true` im Ereignis-Snapshot, warum im ersten Lauf alle Jobs ohne Schritte übersprungen
wurden; der andere Lauf für denselben Head passierte diese Bedingung und führte die für die reine Dokumentationsänderung
vorgesehenen Spec-Prüfungen aus. Der Timeline-Zeitpunkt, gleiche Head-SHA, Job-Ergebnisse und Null-Schritte sind im
GitHub-Actions-Laufprotokoll nachvollziehbar.

Die Concurrency-Gruppe verwendet für PR-Ereignisse denselben PR-Ref und `cancel-in-progress: true`. Das erlaubt
konkurrierenden PR-Ereignissen, einen laufenden Lauf zu beenden. Für diese beiden konkreten Läufe ist aber kein Abbruch
belegt: der vollständig übersprungene Lauf hat `conclusion=skipped`, nicht `cancelled`, und der zweite Lauf lief
erfolgreich durch. Die Concurrency-Einstellung ist deshalb ein mögliches zusätzliches Rennen, aber nicht die durch
diese Laufdaten belegte Ursache des Überspringens.

## Vorgeschlagene Korrektur (nicht angewendet)

Die Datei [ci-pr-ready-draft-race.diff](./ci-pr-ready-draft-race.diff) lässt `art` und `spec` beim Ereignis
`ready_for_review` laufen, auch wenn dessen Snapshot das alte Draft-Flag enthält. Drafts bleiben für die übrigen
Ereignisse wie bisher ausgeschlossen. Die Änderung ist nur als Diff abgelegt und wurde in einem Wegwerf-Verzeichnis
angewendet und als YAML geparst; `.github/workflows/ci.yml` im Repository wurde nicht geändert.

## CI vor «Ready» und vor Merge prüfen

Vor «Ready for review» die Branch-CI lokal oder als `workflow_dispatch` ausführen. Für einen PR-Branch:

```sh
gh workflow run ci.yml --ref <pr-branch>
gh run list --workflow ci.yml --branch <pr-branch> --event workflow_dispatch --limit 5 \
  --json databaseId,headSha,status,conclusion,url
```

Anschliessend in `gh run view <lauf-id> --json headSha,status,conclusion,jobs` prüfen: `headSha` muss dem aktuellen
PR-Head entsprechen, der Lauf muss abgeschlossen und erfolgreich sein, und die erwarteten Prüfjobs müssen tatsächlich
Schritte ausgeführt haben. Ein grüner Lauf mit allen Jobs `skipped` ist kein CI-Nachweis. Bei reinen Spec-Änderungen
ist der erwartete Umfang «Änderungsart» plus «Spec-Prüfungen»; bei Codeänderungen müssen auch die vorgesehenen
Build-, Frontend-, Backend- und Browser-Prüfungen gelaufen sein. Eine wegen `nur-spec` übersprungene Gruppe ist nur
dann erwartungsgemäss, wenn «Änderungsart» den reinen Spec-Lauf korrekt bestimmt hat.

Vor dem Merge dieselbe Prüfung für den aktuellen PR-Head wiederholen und in der PR-Ansicht verifizieren, dass die
Läufe diesem Head zugeordnet sind und nicht bloss älteren Commits. Wird ein PR-Lauf erneut vollständig übersprungen,
`workflow_dispatch` auf dem PR-Branch ausführen, dessen grünen Lauf am aktuellen Head nachweisen und den Vorfall im
PR nennen. Drafts absichtlich nicht anhand übersprungener Checks als geprüft markieren.
