# Workflow-Vorschläge für Fabian

`ci.yml` bleibt unverändert. Die folgenden Änderungen sind nur Vorschläge und
werden erst nach eigenem Entscheid in `.github/workflows/` übernommen.

## 1. Veraltete `main`-Läufe abbrechen

Ein `main`-Push kann einen älteren `main`-Lauf ersetzen: der neue Kopf enthält
bereits dessen Änderungen und erhält seinen eigenen vollständigen CI-Lauf.
PR-Läufe bleiben unabhängig und weiterhin Voraussetzung vor dem Merge. Das ist
die einzige gemessene Stelle mit grossem Queue-Potenzial: In Lauf 44764394051
startete Gruppe 4 24,6 min nach dem ersten Job, obwohl ihre Abhängigkeit nach
0,3 min fertig war. Ein abgebrochener älterer Commit hat keinen eigenen
Deploy-Nachweis; der aktuelle Kopf bleibt durch `pruefe-ci-nachweis.py`
fail-closed geschützt.

Geschätzte Wandzeit-Einsparung: **0–24 min je nach Queue und Fortschritt des
überholten Laufs**, nicht garantiert. Das entspricht keinem Testabbau; jeder
aktuelle `main`-Kopf muss weiterhin alle Jobs erfolgreich abschliessen.

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
@@
 concurrency:
-  group: ci-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}
-  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
+  group: ci-${{ github.ref }}
+  cancel-in-progress: ${{ github.event_name == 'pull_request' || github.ref == 'refs/heads/main' }}
```

Vor Übernahme: kontrollieren, dass parallele Deploy-Aufträge ausschliesslich
den aktuellen `main`-Kopf prüfen. `workflow_dispatch` auf `main` teilt dann
ebenfalls die Concurrency-Gruppe mit Push-Läufen; das ist beabsichtigt, weil
beide denselben Commit-Nachweis erzeugen.

## 2. Spec-Testlauf im vollen Lauf nicht doppelt ausführen

Alle sechs Tests in `SPEC_TESTS` laufen im vollen Lauf bereits in
`Backend-Tests (JUnit, ArchUnit, SpotBugs)`: dieser führt `mvn verify` ohne die
Start- und Szenario-Gruppen aus. Im reinen Spec-Lauf ist `spec` dagegen der
einzige Job, der sie ausführen darf. Daher kann die Ausführung der sechs Tests
an `needs.art.outputs.nur-spec == 'true'` gebunden werden, während der Job
`spec` selbst im vollen Lauf erfolgreich bleibt. So bleiben die
Deploy-Nachweis-Regel «jeder Job erfolgreich» und alle sechs Prüfungen erhalten.

Gemessene obere Schranke: **0,7 Runner-Minuten** pro vollem Lauf; die Wandzeit
ist bei freier Runner-Kapazität praktisch unverändert. Mittleres Risiko, weil
`NurSpecTest.ciLaeuftAufPushUndSpecPruefungenImmer` heute explizit prüft, dass
`spec` keine `needs`-Abhängigkeit hat. Diese und die Workflow-Konfiguration
müssten gemeinsam angepasst werden; die Testausführung in `backend` muss in
einem grünen vollen Lauf nachgewiesen sein.

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
@@
   spec:
     name: Spec-Prüfungen (nur Spec)
     if: github.event_name != 'pull_request' || !github.event.pull_request.draft
+    needs: art
@@
       - uses: actions/setup-java@<unveränderte gepinnte Version>
+        if: needs.art.outputs.nur-spec == 'true'
@@
       - name: Spec-Tests (ohne Quarkus-Start)
+        if: needs.art.outputs.nur-spec == 'true'
```

Der Platzhalter ist im tatsächlichen Diff durch die bereits gepinnte
`setup-java`-Zeile zu ersetzen. Der Job bleibt aktiv; nur Setup-Java und
Spec-Testschritt werden im vollen Lauf übersprungen. Ergänzend ist der Test
`NurSpecTest.ciLaeuftAufPushUndSpecPruefungenImmer` auf diese Regel umzustellen.
Die Job-Ergebnisse müssen für einen vollen Lauf weiterhin alle `success` sein.

## Nicht empfohlene Änderungen

- **Playwright-Cache:** Die Browser-Installation im Browser-Job dauert im
  gemeinsamen Schritt mit `npm ci` im Median 54,5 s. Die offizielle
  [Playwright-CI-Dokumentation](https://playwright.dev/docs/ci) rät vom
  Browser-Cache ab: Cache-Restore kann so lange wie der Download dauern;
  Systemabhängigkeiten unter Linux sind nicht cachebar.
- **Zusätzliche `paths-ignore`:** `nur-spec.py` setzt schon eine strenge
  Positivliste für Markdown unter `.kiro/` und `docs/`. Weitere Pfadfilter
  könnten Tests für sicherheits- oder mandantenrelevanten Code überspringen.
- **Löschen von Vollständigkeitsprüfungen, Szenarien oder Gruppen:** nicht
  empfohlen. `testzahl`, `SzenarienShardTest`, alle `@req`-Sicherheits- und
  Mandantentests sowie Archivierungsprüfungen bleiben erforderlich.
