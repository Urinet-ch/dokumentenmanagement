# Branding und Anmeldung des zweiten Mandanten

Der Repo-Anteil von Spec 03, Aufgabe 10.3, liefert das konfigurierbare
Branding und das vorbereitete Keycloak-Theme. Die Domain des Mandanten ist
kein fest eingebauter Wert und wird erst nach dem Entscheid von Fabian im
Betrieb gesetzt.

## Branding

Die API liest den Eintrag
`archiv.branding.mandanten.vhf-gsk.*`. Der produktive Deploy setzt
`ARCHIV_BRANDING_VHF_GSK_DOMAIN` über den freigegebenen Secret-/Konfigurations-
prozess. Der Wert gehört nicht in dieses Repository. Die Web-App verwendet
die Antwort von `GET /api/v1/branding?domain=...`; der Mandant für geschützte
Daten kommt weiterhin ausschliesslich aus dem Token.

## Keycloak-Theme

Das Theme liegt unter
`deploy/infomaniak/keycloak/themes/vhf-gsk/login/` und enthält nur
Platzhalterfarben und Textmarken. Für den Einsatz muss der Betreiber den
mandantenspezifischen Client im bestehenden Realm manuell anlegen und dort
das Login-Theme `vhf-gsk` sowie die freigegebene Redirect-URI setzen. Die
Änderung ist ein Betriebsnachweis und wird nicht über eine Änderung des
Realm-Exports vorweggenommen.

## Domain-Router

Ein zusätzlicher öffentlicher Router wird erst nach einem eigenen Entscheid
von Fabian und der Prüfung der Compose-Policy ausgerollt. Bis dahin bleibt
die bestehende Erreichbarkeit unverändert. Kein Router, keine Domain und
keine Zugangsdaten werden aus dieser Entwicklungsumgebung auf einen Server
übertragen.

## Repo-Prüfungen

Die Branding-Fallbacks und die Validierung unsicherer Konfiguration werden
mit den bestehenden Domain- und Szenariotests geprüft. Vor einer späteren
Betriebsfreigabe sind zusätzlich der Client, die Redirect-URI, das Theme im
laufenden Realm und der Router gegen die freigegebene Infrastruktur zu
prüfen.
