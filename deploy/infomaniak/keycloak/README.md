# Google-IdP

Der Realm-Export enthält den deaktivierten Provider `google`, beide Anmeldeflows und die Ereignistypen. `start.sh` kann
`google_client_secret` aus `/run/secrets/` lesen und den Wert beim erstmaligen Import als `GOOGLE_CLIENT_SECRET`
bereitstellen. Ohne diese Datei wird der Platzhalter leer aufgelöst; das ist nur zulässig, solange der Provider
deaktiviert bleibt. Das Produktions-Compose-File bindet das Secret noch nicht ein: Der Deploy-Workflow muss die Datei
erst nach dem Entscheid zu OAuth-Client und GitHub-Secret bereitstellen.

`--import-realm` überspringt einen Realm, der bereits in der Datenbank vorhanden ist. Die Vorlage
[`google-idp-einrichten-vorlage.sh`](./google-idp-einrichten-vorlage.sh) bildet darum die einmalige Nachrüstung ab. Sie
ist für einen Wegwerf-Container mit authentifiziertem `kcadm.sh`, `jq` und einer Secret-Datei unter
`/run/secrets/google_client_secret` gedacht. Die Vorlage wurde mit einem `kcadm.sh`-Stub und synthetischem Secret
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
