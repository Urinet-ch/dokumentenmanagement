# Vorschlag: Browser-Szenarien in zwei Shards

Dieser Diff ist ein Vorschlag und **nicht angewendet**. Jeder Matrixjob startet
auf einem eigenen Runner ein eigenes Browser-Testbackend mit eigenem Keycloak,
PostgreSQL und MinIO. So teilen sich die Shards weder die TOTP-Schritte noch
die Testdaten. Die Jobnamen ändern sich; wer den Diff anwendet, prüft auch
externe Auswertungen und erforderliche Checks auf die neuen Namen.

Mit dem Cucumber-Dry-Run auf `main` wurden 154 Szenarien einschliesslich
Outline-Beispielen über URI, Szenario-ID und Zeile verglichen: 77 je Shard,
keine Überschneidung, keines fehlt. Das belegt die Aufteilung, nicht die
Lauffähigkeit oder Zeitersparnis. Beide Shards müssen mit unabhängigen
Testbackends und anschliessend in mindestens zwei vollständigen CI-Läufen
geprüft werden; `fail-fast: false` verhindert, dass ein fehlerhafter Shard
den anderen abbricht. Kein Szenario wird ausgelassen.

Grundlage des folgenden Diffs: Stand `457d32cb` von `main`:

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -345,6 +345,10 @@
   browser:
-    name: Browser-Szenarien (Cucumber.js, Playwright)
+    name: Browser-Szenarien (Cucumber.js, Playwright) – Shard ${{ matrix.shard }}/2
     if: needs.art.outputs.nur-spec != 'true'
     needs: [art, build]
     runs-on: ubuntu-24.04
+    strategy:
+      fail-fast: false
+      matrix:
+        shard: [1, 2]
     steps:
@@ -434,5 +438,5 @@
       - working-directory: frontend
-        run: npx cucumber-js
+        run: npx cucumber-js --shard ${{ matrix.shard }}/2
       - name: Echtes Archiv beenden
         if: always()
         run: touch backend/target/browser-backend.stop
```

Die Baselines der Browser-Jobs in den Läufen 44899529866 und 44898942261
betragen 5 min 37 s und 5 min 53 s; die Cucumber-Schritte dauern 186,5 s und
200,7 s. Eine Nachher-Messung und damit eine belastbare Einsparung fehlen.
