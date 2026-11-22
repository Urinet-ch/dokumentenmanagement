# Sicherung des zweiten Mandanten

Diese Anleitung ergänzt den Repo-Anteil von Spec 03, Aufgabe 10.6. Sie enthält
keine Zugangsdaten und keinen konkreten Zielhost. Die eigentliche Ausführung
erfolgt ausschliesslich über das Infra-Repository und den freigegebenen
Deploy-/Sicherungsprozess.

## Vor der Inbetriebnahme

1. `ARCHIV_MANDANTEN` auf dem Sicherungsdienst auf `urinet vhf-gsk` setzen.
2. Prüfen, dass `m_urinet` und `m_vhf_gsk` in der Archivdatenbank vorhanden
   sind. `taeglich.sh` bricht bei einem Schema ab, das nicht in dieser Liste
   steht.
3. Die öffentlichen `age`-Empfänger über
   `ARCHIV_AGE_EMPFAENGER_SICHERUNG` und `ARCHIV_AGE_EMPFAENGER_PROBE`
   bereitstellen. Es sind zwei verschiedene öffentliche Schlüssel, keine
   Secret-Dateien. Private Schlüssel gehören nur in die Notfallunterlagen
   beziehungsweise auf die Restore-Probe.
4. Den ersten Lauf mit `ARCHIV_VOLLEXPORT=1` planen. Danach wird der
   verschlüsselte Bucket-Zuwachs je Mandant anhand des Vorinventars erzeugt.

## Täglicher Lauf

Der Betriebsdienst ruft `deploy/backup/taeglich.sh` ohne Positionsargumente
auf. Die Pflichtvariablen und die zulässigen Exit-Codes stehen in
[`deploy/backup/README.md`](../../deploy/backup/README.md). Der Lauf ist erst
nach dem Marker `FERTIG` zur Abholung freigegeben.

Zu prüfen sind insbesondere:

- ein Inventar und ein Bucket-Strom für `urinet` **und** `vhf-gsk`;
- `SHA256SUMS` und das Register der Prüfsummen;
- die zur Abholung freigegebenen Laufartefakte in `laeufe/` enthalten keine
  unverschlüsselten Nutzdaten; das Vorinventar `.zustand/` ausserhalb von
  `laeufe/` bleibt absichtlich unverschlüsselt und wird vom Infra-Repository
  nicht abgeholt;
- die unveränderte Verfügbarkeit der vorherigen erfolgreichen Sicherung bei
  einem Fehler;
- der vollständige Export nach einem Wechsel des Probe-Empfängers.

Transport zum zweiten Anbieter, Aufbewahrung, `backup-gate.sh` und der
zeitgesteuerte Restore-Nachweis gehören in das Infra-Repository. Dieses Repo
führt keinen Transport und keinen Zugriff auf einen Sicherungsanbieter aus.

## Wiederherstellung und Nachweis

Vor dem Einsatz des zweiten Mandanten führt der Betreiber die lokale
synthetische Übung aus:

```sh
bash deploy/backup/test/restore-uebung-lokal-test.sh
```

Die eigentliche lokale Roundtrip-Übung mit eigenen Wegwerf-Schlüsseln ist in
[`deploy/backup/README.md`](../../deploy/backup/README.md) beschrieben. Der
Nachweis muss beide Schemas und beide Bucket-Ströme enthalten. Die monatliche
Restore-Probe, ihre Aufbewahrung und die Alarmierung bleiben ein
Betriebsnachweis; sie werden nicht aus dieser Entwicklungsumgebung gestartet.
