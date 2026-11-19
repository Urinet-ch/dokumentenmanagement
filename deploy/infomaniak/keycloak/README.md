# Google-IdP

Der Realm-Export enthält den deaktivierten Provider `google`, beide Anmeldeflows und die Ereignistypen. `start.sh` kann
`google_client_id` und `google_client_secret` aus `/run/secrets/` lesen und die Werte beim erstmaligen Import als
`GOOGLE_CLIENT_ID` und `GOOGLE_CLIENT_SECRET` bereitstellen. Ohne diese Dateien werden die Platzhalter leer aufgelöst; das ist nur zulässig, solange der Provider
deaktiviert bleibt. Das Produktions-Compose-File bindet das Secret noch nicht ein: Der Deploy-Workflow muss die Datei
erst nach dem Entscheid zu OAuth-Client und GitHub-Secrets (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) bereitstellen.

`--import-realm` überspringt einen Realm, der bereits in der Datenbank vorhanden ist. Die Vorlage
[`google-idp-einrichten-vorlage.sh`](./google-idp-einrichten-vorlage.sh) bildet darum die einmalige Nachrüstung ab. Sie
ist für einen Wegwerf-Container mit authentifiziertem `kcadm.sh`, `jq` und Secret-Dateien unter
`/run/secrets/google_client_id` und `/run/secrets/google_client_secret` gedacht. Die Vorlage wurde mit einem `kcadm.sh`-Stub und synthetischen Testwerten
geprüft, aber nicht gegen einen bestehenden Realm oder einen Betriebsserver ausgeführt. Sie legt den Provider absichtlich
deaktiviert an.

Zum Verknüpfen eines Kontos ausschliesslich als Administrator:

```bash
kcadm.sh create "users/$KC_USER_ID/federated-identity/google" -r urinet-archiv \
  -s identityProvider=google -s "userId=$GOOGLE_SUB" -s "userName=$GOOGLE_SUB"
```

Zum Lösen der Verknüpfung und Beenden aller Benutzersitzungen:

```bash
kcadm.sh delete "users/$KC_USER_ID/federated-identity/google" -r urinet-archiv
kcadm.sh create "users/$KC_USER_ID/logout" -r urinet-archiv
```

Die Shell- und Stub-Prüfung läuft mit:

```bash
bash -n deploy/infomaniak/keycloak/google-idp-einrichten-vorlage.sh
bash deploy/infomaniak/keycloak/test/google-idp-einrichten-vorlage-test.sh
```

## Nachbesserung Review (PR #334)

- Die Vorlage ist wiederholbar: Flows, Ausführungen und Provider werden vor dem Anlegen geprüft, ein vorhandener Provider
  wird aktualisiert. Die Rollensperre `manage-account-links` steht am Anfang.
- «Verknüpfung nur durch den Administrator» ist eine vorläufige Voreinstellung bis zum Entscheid zu Req 2.27 (O.8).
- Verknüpfen und Lösen durch den Administrator erscheinen im Admin-Ereignisprotokoll (`adminEventsEnabled`), nicht als
  Benutzerereignisse `FEDERATED_IDENTITY_LINK` und `REMOVE_FEDERATED_IDENTITY`. Fehlversuche des zweiten Faktors stehen
  als `LOGIN_ERROR` im Protokoll.
- Die Cucumber-Szenarien prüfen nur die Konfiguration. Anmeldeläufe gegen einen `oidc`-Test-IdP und ein Test zum
  Sitzungsende nach dem Lösen (`users/{id}/logout`) sind in `tasks.md` O.8 offen.
- `eventsListeners` ist in Realm-Export und Vorlage gleich (`[]`, Ereignisse nur in der Datenbank); `eventsExpiration` ist
  bewusst nicht gesetzt (unbegrenzte Aufbewahrung, Zugriffsprotokoll nach Req 2.27).
- `start.sh` setzt fehlende `google_client_id` und `google_client_secret` auf leer; das gilt nur bei deaktiviertem Provider. Mit der Freigabe
  werden die Dateien Pflicht (offen für Fabian, Workflow-Prüfung).
- Der Stub-Test der Vorlage läuft nicht in der CI (Workflow-Änderung liegt bei Fabian) und wird von Hand gestartet.
