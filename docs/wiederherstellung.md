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

   S3 vergibt beim Schreiben neue Versions-IDs. Das Wiederherstellungskommando aktualisiert deshalb nach erfolgreichem
   Schreiben und Sperren die passenden Referenzen der Dokumentdateien unter `archiv/` und `eingang/` in der
   wiederhergestellten Datenbank. Die unveränderlichen Manifest-Dateien unter `manifest/` werden nicht umgeschrieben. Die Datenbankzeilen `manifest.s3_version`, `manifest.sig_s3_version` und `manifest_zeitstempel.s3_version` bleiben ebenfalls unverändert: Die Tabellen sind per Trigger nur einfügbar (Req 15), ein Umschreiben würde die Beweiskette verletzen. Nach einer Wiederherstellung zeigen diese drei Spalten deshalb auf Versions-IDs der alten Quelle, die im neuen Bucket nicht existieren. Bei der Prüfung (Schritt 5) Manifeste über Schlüssel und SHA-256 auflösen, nicht über die gespeicherte Versions-ID; Code, der Manifeste über diese Versions-ID liest (Export, Prüfwerkzeug), kann im Ziel scheitern, bis Aufgabe 5.12 die Auflösung festlegt.
5. **Prüfen.** Die Anzahl wiederhergestellter Objekte mit den Datenbankeinträgen abgleichen, Manifeste prüfen und den
   vorgesehenen Prüflauf erfolgreich abschliessen. Abweichungen sind vor der Freigabe zu klären.

## Sperrprüfung der Restore-Probe

Nach der Wiederherstellung von Datenbank und Bucket prüft dieses Kommando die Objektversionen unabhängig vom
Dokument-Prüflauf:

```sh
archiv sicherung sperren-pruefen --mandant <code> [--stichprobe <0..50>]
```

Die Stichprobe ist gleichmässig über `archiv/` und `manifest/` verteilt; Standard sind fünf Versionen, null deaktiviert
die Löschprobe. Das konfigurierte Bucketpräfix muss mit `urinet-archiv-p-` beginnen. Das Kommando prüft dies vor dem
Zugriff auf das Mandantenregister oder den Bucket und ist ausschliesslich in der isolierten Restore-Probe auszuführen.
Die geprüften Objekte werden mit den wiederhergestellten Datenbankeinträgen abgeglichen: Objektbestand, aktive Sperre,
Modus und `retainUntil` gegen den Sollwert. Erwartete Sperren werden in Stufe P höchstens einen Tag angesetzt. Ist die Frist so gekappt, genügt eine aktive Sperre (die Prüfung kennt den Zeitpunkt der Wiederherstellung nicht); Fristen unter einem Tag werden voll verglichen.

Für jede Stichprobenversion versucht die Probe, genau diese Version ohne Bypass-Header zu löschen. Bei einer fehlenden
Sperre kann S3 die Version tatsächlich löschen; die Probe meldet dies als Abweichung und endet fehlerhaft. Das ist nur
im wegwerfbaren Probe-Bucket zulässig.

Das Kommando gibt JSON mit Zählern und betroffenen Dokument-IDs beziehungsweise Manifest-Daten aus, keine
Objektschlüssel oder Dateinamen. Abweichungen: `SPERRE_FEHLT` (keine aktive Soll-Sperre), `MODUS_ABWEICHEND`,
`SPERRE_ZU_KURZ`, `OBJEKT_FEHLT` (Datenbankeintrag ohne Bucket-Version), `OBJEKT_UEBERZAEHLIG` (Bucket-Version ohne
Datenbankeintrag) und `LOESCHEN_MOEGLICH`. `SPERRE_FEHLT` bedeutet, dass ein Objekt mit Sperrpflicht keine aktive
Sperre hat. Exit-Codes: `0` keine Abweichung, `1` Abweichung gefunden, `2` ungültiger Aufruf, unbekannter/inaktiver
Mandant oder falsches Bucketpräfix, `3` Zugriffsfehler auf Datenbank oder Bucket beziehungsweise sonstiger technischer
Fehler.

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

Die lokale synthetische Teilprobe steht unter `deploy/backup/restore-uebung-lokal.sh`. Sie prüft den
Sicherungs-Shellablauf mit Attrappen sowie S3-Restore und Dateireferenzen mit lokalen Dev Services. Beim
Datenbank-Restore vergleicht sie je Mandant die Zahlen für Wissensseiten, Versionen, Quelleneinträge, Dokumente,
Webfreigaben, öffentliche Fragen und Benutzereinladungen mit dem vor der Sicherung erfassten Stand. Die vollständige
lokale synthetische Wiederherstellung einschliesslich Datenbank-Dumps und Keycloak-Realm wurde am 28.11.2026
durchgespielt. Die wiederkehrende betriebliche Probe bleibt offen.

| Lauf | Dauer (s) | Exit-Code |
| --- | ---: | ---: |
| 28.11.2026, lokal, synthetisch | 2838 | 0 |
