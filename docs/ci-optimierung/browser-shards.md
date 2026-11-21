# Vorschlag: Browser-Szenarien in zwei Shards

Dieser Diff ist ein Vorschlag und **nicht angewendet**. Jeder Matrixjob startet
auf einem eigenen Runner ein eigenes Browser-Testbackend mit eigenem Keycloak,
PostgreSQL und MinIO. So teilen sich die Shards weder die TOTP-Schritte noch
die Testdaten. Die Jobnamen ändern sich; wer den Diff anwendet, prüft auch
externe Auswertungen und erforderliche Checks auf die neuen Namen.

Mit dem Cucumber-Dry-Run auf `main` bei `f5e9248a` wurden 215 Szenarien
einschliesslich Outline-Beispielen über URI, Szenario-ID und Zeile verglichen:
108 und 107 je Shard, keine Überschneidung, keines fehlt. Auf dem früheren Stand
`c8aba344` waren es 158 Szenarien, je 79. #418 behebt inzwischen die damals
beobachtete Chat-Synchronisation.

Der erste erneute lokale Lauf von Shard 1 mit frischem Backend (Compose-Projekt
`urinet-archiv-dev-ooqhka`) dauerte 139,20 s; 106 von 108 Szenarien waren grün.
Zwei Hilfe-Leiste-Szenarien liefen nach Navigation zur Suche beim Öffnen der
Leiste in den 30-Sekunden-Timeout. Isoliert trat dieser Fehler für alle vier
Rollen auf. Ein frisches Backend für Shard 2 (Projekt `urinet-archiv-dev-jooofl`)
wurde wegen gleichzeitig gestarteter fremder schwerer Container wieder beendet,
bevor Browser-Szenarien liefen. Beide Backends wurden regulär beendet.

Nach dem Merge von #440 wurden beide Shards je zweimal lokal gegen das
PWA-Produktionsartefakt und jeweils ein frisches Testbackend ausgeführt. Die
Compose-Projekte waren je Shard und Lauf verschieden:

| Lauf | Shard 1 (108 Szenarien) | Projekt | Shard 2 (107 Szenarien) | Projekt | Summe |
| --- | --- | --- | --- | --- | --- |
| 1 | 108 grün, 78,76 s | `urinet-archiv-dev-ugdonv` | 107 grün, 82,19 s | `urinet-archiv-dev-apkbdi` | 160,95 s |
| 2 | 108 grün, 76,75 s | `urinet-archiv-dev-zomsom` | 107 grün, 82,64 s | `urinet-archiv-dev-fhhxqk` | 159,39 s |

Die Zeiten messen den jeweiligen Cucumber-Befehl inklusive Prozessstart, nicht
den Backend-Start. Die Summe ist die Addition der nacheinander gemessenen Shards,
keine gemessene parallele Laufzeit. Der erneute Dry-Run bestätigte 215
Szenarien, vollständig und disjunkt.

Der parallele CI-Lauf und eine Zeitersparnis sind noch nicht belegt. Nach
Anwendung muss der Diff in mindestens zwei vollständigen CI-Läufen geprüft
werden; `fail-fast: false` verhindert, dass ein fehlerhafter Shard den anderen
abbricht. Kein Szenario wird ausgelassen.

Grundlage des folgenden Diffs: Stand `c8aba344` von `main`:

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
200,7 s für je 154 Szenarien. Wegen des gewachsenen Umfangs sind sie mit dem
aktuellen lokalen Läufen nicht direkt vergleichbar. Eine CI-Nachher-Messung
und damit eine belastbare Einsparung fehlen.
