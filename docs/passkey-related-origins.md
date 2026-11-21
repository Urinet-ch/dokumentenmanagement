# Passkey: Related Origins

Die RP-ID ist der Host der App-Basisadresse. Bei Anmeldung auf einer anderen Keycloak-Origin verlangt WebAuthn auf
`https://<RP-ID>/.well-known/webauthn` eine öffentlich erreichbare JSON-Antwort:

```json
{"origins":["https://<KEYCLOAK-ORIGIN>"]}
```

`<KEYCLOAK-ORIGIN>` ist die öffentliche Origin von Keycloak, einschliesslich eines abweichenden Ports, ohne Pfad oder
Schrägstrich. Sie wird nicht aus der Anfrage oder dem Host-Header abgeleitet. Die Route des Archivs liest sie aus
`quarkus.oidc.auth-server-url`; diese Adresse muss im Betrieb auf die öffentliche Keycloak-Origin zeigen. Fehlt sie oder
ist sie ungültig, antwortet die Route mit 503 statt einer leeren Erfolgsmeldung.

Vor der Inbetriebnahme prüft Fabian die HTTPS-Auslieferung auf der RP-ID-Origin: HTTP 200, `Content-Type:
application/json`, das Feld `origins` mit genau der öffentlichen Keycloak-Origin und keine Weiterleitung auf die
Anmeldung oder die PWA-Startseite. Proxy und App müssen den Pfad unverändert erreichen. Der virtuelle Chromium-Spike
belegte Related Origins mit synthetischen Domains; der Nachweis auf echten Geräten folgt in 24.3.

Ein Wechsel der RP-ID macht bestehende Passkeys ungültig. Rückfall ist Passwort plus TOTP; danach werden Passkeys für die
neue RP-ID eingerichtet. Die Aktualisierung eines bereits importierten Keycloak-Realms erfolgt separat in 24.3.
