# Browser-Szenarien in der CI: Stabilität

Stand 28.11.2026, 07:41:02 UTC. Anlass: Der Job «Browser-Szenarien (Cucumber.js, Playwright)» ist der häufigste Grund für rote CI-Läufe.

## Nachmessung (Erhebung 28.11.2026, 07:41:02 UTC)
Beide Abfragen stammen vom selben Stichtag. Die Stichproben unterscheiden sich: Der Browserwert umfasst die 80 jüngsten
CI-Läufe aller Branches, die `main`-Zahl alle `main`-Läufe im angegebenen Zeitfenster.

```sh
python3 tools/ci_browser_quote.py 80 --seit 2026-11-26T12:10:27Z
gh run list --workflow CI --branch main \
  --created '2026-11-27T00:00:00Z..2026-11-28T07:41:02Z' --limit 100 \
  --json databaseId,conclusion,createdAt,url
```

Der erste Befehl wurde zum Stichtag ausgeführt und wertet die 80 jüngsten CI-Läufe unabhängig vom Branch aus; `--seit`
schliesst Läufe vor 26.11.2026, 12:10:27 UTC aus. Davon hatten 30 einen abgeschlossenen Browser-Job: 0 rot (0 %), 0
ausschliesslich wegen des Browser-Jobs rot.

Der zweite Befehl begrenzt die `main`-Läufe auf denselben Stichtag. Er ergibt 34 abgeschlossene CI-Läufe: 31 erfolgreich,
3 fehlgeschlagen. Die fehlgeschlagenen Läufe waren `45953018945` («Spec-Prüfungen»), `46039218320` («Browser-Szenarien»
und «WordPress-Plugin») sowie `46039250227` («WordPress-Plugin»). Kein Backend-Job war rot.

## Ausgangslage (Messung 26.11.2026, 80 jüngste Läufe)
- 31 Läufe mit Browser-Job; Browser-Job in 9 davon rot (29 %); in allen 9 war er der einzige rote Job.
- Browser-Dauer: Median rund 11 min, Maximum 22 min.
- Ursachen: echte Brüche durch Änderungen am Verhalten (zu Recht rot), bekannte Timeouts (PWA-Installation, Passkey,
  «Testbenutzer entfernen: HTTP 401»), dazu `apt-get` beim Bauen des Werkzeug-Images (nicht Browser, siehe unten).
  Details: `docs/ci-flakes-2026-11.md`, `docs/ci-laufzeit-2026-11.md`.

## Bekannte Ursachen
Am 27.11.2026 scheiterte die Playwright-Installation beim Abruf aus dem Paket-Repository mit HTTP 403. Das war kein
Test-Flake; der Fehler trat bei der Installation und nicht in einem Szenario auf.

## Entscheid: im Build lassen und entschärfen
Die Szenarien sind der einzige Test, der die Oberfläche bedient; sie haben am 26.11. echte Brüche gefunden (Treuhänder-Menü,
Hilfe-Leiste), die Unit-Tests nicht sahen. Ausserhalb des Builds würden solche Brüche erst nach dem Merge auffallen.

## Umgesetzt
1. **Einmal wiederholen:** `retry: 1` in `frontend/cucumber.mjs`. Ein Szenario, das zweimal scheitert, bleibt rot; ein
   einmaliger Timeout nicht mehr.
2. **Quarantäne:** Szenarien mit dem Tag `@flaky` laufen nicht im Hauptlauf (`@browser and not @flaky`), sondern über
   `npm run e2e:flaky` (ohne Wiederholung, für einen getrennten, nicht blockierenden Lauf). Der Tag wird nur mit belegter
   Ursache in `docs/ci-flakes-2026-11.md` vergeben, nie als Ersatz für eine Korrektur. Bisher ist kein Szenario markiert.
3. **Messung:** `python3 tools/ci_browser_quote.py [Anzahl] [--seit ISO-Zeit]` zählt rote Browser-Jobs; Vergleich vor und
   nach der Änderung (Zeitpunkt des Merges von `retry` = Stichtag).

## Bewusst nicht umgesetzt
- **Aufteilen in Shards** (`cucumber-js --shard`): Der Job verbringt rund 4 min mit Aufbau (Playwright, Archiv, Dev Services);
  zwei Shards sparen höchstens 3 bis 4 min und verdoppeln den Aufbau. Lohnt erst bei deutlich mehr Szenarien.
- **Eigener nächtlicher Workflow für `@flaky`:** wäre eine Änderung unter `.github/workflows` (geschützter Pfad); erst nötig,
  wenn ein Szenario markiert wird.
- **`apt-get` im Werkzeug-Image:** Mehrere Läufe scheiterten am Paketmirror (nicht Browser). Ursache am 26.11.: Der Ubuntu-Mirror lieferte nur 70 kB/s (Download 16 min), danach 404 auf ein inzwischen ersetztes Sicherheitspaket (libtiff6). Behoben mit einer Schleife (bis zu 3 Versuche mit frischen Paketlisten) und `Acquire::Retries=5` in `Dockerfile.jvm`.

## Beobachtung
Nach dem Merge die Quote regelmässig messen (Stichtag = Merge von `retry`); Ziel: Browser-Job rot nur bei echten Fehlern,
Wiederholungen selten. Bleibt ein Szenario trotz Wiederholung wackelig, Ursache belegen (`gh run view --log-failed`),
korrigieren, im Notfall mit `@flaky` aus dem Hauptlauf nehmen.
