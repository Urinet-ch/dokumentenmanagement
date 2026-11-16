# Wiederherstellung

## Voraussetzungen

Wiederherstellungen erfolgen zuerst in einer getrennten Testumgebung. Die benötigten Schlüssel werden aus den
Notfallunterlagen bezogen und geschützt bereitgestellt; Geheimnisse gehören weder in Befehle noch in Protokolle.
Das Sicherungsmaterial wird nur über den freigegebenen Sicherungsweg bereitgestellt.

In Stufe A sind ausschliesslich synthetische oder anonymisierte Testdaten zulässig. Die Sicherung dient dort nur als
Funktionstest; die Aufbewahrung nach Req 16.2 gilt erst ab Stufe B (Req 16.19).

## Reihenfolge

1. **Schlüssel bereitstellen.** Den privaten Entschlüsselungsschlüssel und den Manifest-Schlüssel aus den
   Notfallunterlagen beziehen. Den öffentlichen Manifest-Schlüssel aus dem Repository zur Prüfung verwenden.
2. **Snapshots auswählen und prüfen.** Nur Snapshots verwenden, deren SHA-256 in einem gültigen Manifest im Feld
   `sicherung` belegt ist. `archiv manifest pruefen <datum> [<bis-datum>]` prüft die Manifeste; mit aktiver Datenbank
   vergleicht das Kommando sie zusätzlich mit den festgehaltenen Exporten. Die zugehörigen Einträge lassen sich so
   nachsehen:

   ```sql
   SELECT art, mandant, sha256, erstellt
   FROM public.sicherung_export
   WHERE mandant IS NULL OR mandant = '<mandant-code>'
   ORDER BY erstellt, id;
   ```

   Die SHA-256 muss exakt einem Hash im Feld `sicherung` eines geprüften Manifests und dem passenden `sha256`-Eintrag
   in `public.sicherung_export` entsprechen. Ein «neuester» Snapshot ohne Manifestnachweis wird nicht eingespielt.
3. **Datenbanken und Realm wiederherstellen.** Die Zielrollen und leeren Datenbanken mit den vorgesehenen
   Berechtigungen bereitstellen. Dumps nicht als Superuser einspielen, zum Beispiel:

   ```sh
   pg_restore --no-owner --role=archiv_app --dbname=archiv archiv.dump
   pg_restore --no-owner --role=keycloak_app --dbname=keycloak keycloak.dump
   ```

   Den Realm-Export bei gestopptem Keycloak einspielen:

   ```sh
   kc.sh import --file realm-export.json
   ```
4. **Bucket und Sperren wiederherstellen.** Als Ziel zuerst einen neuen, leeren Bucket des Mandanten anlegen, mit
   aktivierter Versionierung und aktiviertem Object Lock (keine Versionen, keine Löschmarken). Vollexport und sämtliche Zuwächse chronologisch zu einer Datei
   zusammenfügen. Die Zuwächse explizit in zeitlicher Reihenfolge aufführen; als `--inventar` das jüngste Inventar
   verwenden:

   ```sh
   cat voll.bin zuwachs-2026-01-02.bin zuwachs-2026-01-03.bin > bucket-export.bin
   archiv sicherung wiederherstellen --mandant <code> --inventar inventar.jsonl --strom bucket-export.bin
   ```

   Exit-Codes: `0` Erfolg. `2` Ziel ungeeignet (Bucket fehlt, Versionierung oder Object Lock aus), Ziel nicht leer,
   Kappung ausserhalb des Probe-Präfixes `urinet-archiv-p-` oder Ablage-Stub (`archiv.ablage.stub`) aktiv; in all
   diesen Fällen wurde nichts geschrieben. `3` Format-, Prüfsummen- oder technischer Schreibfehler. `4` Exportkette
   unvollständig oder überzählig (vor dem ersten Schreibzugriff erkannt, das Ziel bleibt leer; fehlende Zuwächse
   ergänzen und erneut starten).

   **Abbruch und Wiederaufnahme.** Es gibt keine Fortsetzung in einem teilweise befüllten Ziel. Nach Exit 3 oder einem
   Abbruch mitten im Lauf (zum Beispiel Netzausfall) ist das Ziel nicht mehr leer, und bereits geschriebene Versionen
   sind bis `retainUntil` gesperrt und lassen sich nicht löschen. Ein zweiter Lauf im selben Bucket endet deshalb mit
   Exit 2. Die Wiederholung erfolgt immer in einem frischen, leeren Bucket (anderer Name, gleiche Voraussetzungen); den
   Teilstand bis zum Ablauf der Sperren liegen lassen.

   `--kappen` ist ausschliesslich für die Restore-Probe zulässig. Bei einer echten Wiederherstellung werden Modus und
   `retainUntil` aus dem Inventar ohne Kappung neu gesetzt. Die Prüfung der Sperren gegen die Sollwerte aus der
   wiederhergestellten Datenbank ist Aufgabe der Restore-Probe.
5. **Prüfen.** Die Anzahl wiederhergestellter Objekte mit den Datenbankeinträgen abgleichen, Manifeste prüfen und den
   vorgesehenen Prüflauf erfolgreich abschliessen. Abweichungen sind vor der Freigabe zu klären.

## Aufbewahrung und Rückfall

`forget` und `prune` werden nur von Hand mit dem zweiten Schlüssel aus den Notfallunterlagen ausgeführt. Vor dem
Ausdünnen sicherstellen, dass die Zuwächse des Buckets mit ihrem eigenen Tag erhalten bleiben. Beispiel für die
Aufbewahrung von Datenbank- und Realm-Ständen:

```sh
restic forget --keep-daily 30 --keep-monthly 12 --keep-yearly 10 --keep-tag <zuwachs-tag> --prune
```

Wird Hostpoint als Zweitkopie abgelehnt, ist cloudscale.ch der vorgesehene Rückfall. Die Auswahl der Zweitkopie ändert
nichts daran, dass ein Snapshot nur mit passendem Manifestnachweis verwendet werden darf.

## Status

Entwurf; einmal vollständig in einer Testumgebung durchgespielt: offen (Nachweis manuell, tasks.md 5.5)
