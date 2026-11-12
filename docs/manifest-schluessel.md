# Manifest-Schlüssel (Ed25519)

Das Tages-Manifest (Spec 01, Req 15.2; Aufgabe 5.2) wird mit einem Ed25519-Schlüssel signiert. Der private
Schlüssel liegt nur als Secret-Datei `/run/secrets/archiv_manifest_key` auf dem Server (Req 16.9) und zusätzlich im
Passwort-Manager; der öffentliche Schlüssel liegt unter `docs/manifest-schluessel.pub.pem`, damit jedes Manifest
ohne Zugang zum Server prüfbar ist.

## Erzeugen (einmalig, Inhaber)

Auf einem vertrauenswürdigen Rechner, nicht auf dem Server und nicht in einer Agenten-Sitzung:

```sh
umask 077
openssl genpkey -algorithm ed25519 -out archiv_manifest_key.pem
openssl pkey -in archiv_manifest_key.pem -pubout -out manifest-schluessel.pub.pem
```

1. Inhalt von `archiv_manifest_key.pem` (PKCS#8-PEM, mit `-----BEGIN PRIVATE KEY-----`) als GitHub-Secret
   `ARCHIV_MANIFEST_KEY` im Repo setzen. Der Deploy-Workflow schreibt ihn als Secret-Datei `archiv_manifest_key` ins
   Bundle und bricht ab, wenn das Secret fehlt.
2. Denselben Inhalt im Passwort-Manager ablegen (Notfallunterlagen, `tech.md` «Plattform und Betrieb»).
3. `manifest-schluessel.pub.pem` per PR unter `docs/manifest-schluessel.pub.pem` ablegen.
4. Die lokale Datei `archiv_manifest_key.pem` sicher löschen.

Optional prüft das Archiv beim ersten Gebrauch, dass der öffentliche zum privaten Schlüssel passt
(`archiv.manifest.oeffentlicher-schluessel`, SPKI-PEM); ohne den Wert leitet es ihn aus dem privaten ab.

## Wechsel

Ein Schlüsselwechsel macht alte Manifeste nicht ungültig, sie bleiben mit dem alten öffentlichen Schlüssel prüfbar.
Den alten öffentlichen Schlüssel darum unter `docs/` behalten und mit dem Datum des letzten damit signierten Manifests
benennen. Das Prüfwerkzeug `archiv manifest pruefen` prüft heute mit einem Schlüssel; einen Zeitraum über einen
Wechsel hinweg in zwei Aufrufen prüfen.
