# Geheimnisse rotieren

> Die Übersicht nennt die aus Spec und Code belegten Wechselwege sowie Fälle, in denen ein sicherer Ablauf noch nicht feststeht.
> Sie dokumentiert keine ausgeführten Rotationen und ersetzt weder die Betreiberprüfung noch die Notfallunterlagen.

## Warnung: Datenschlüssel und Salz der Namenssperre nie ungeplant wechseln
- **Datenschlüssel `archiv_data_key`** (GitHub-Secret `ARCHIV_DATA_KEY`, AES-256-GCM): Er schützt gespeicherte Zugangsdaten, zum
  Beispiel das Passwort des Mail-Eingangs. Mit einem anderen Schlüssel lassen sie sich nicht mehr entschlüsseln; beim
  Mail-Eingang muss das Postfach dann neu eingerichtet werden (README, «Mail-Eingang einrichten»). Ein geprüftes
  Wechselverfahren fehlt. Den Schlüssel dauerhaft sichern und nur mit einem Plan für die Neuverschlüsselung wechseln.
- **Salz `namenssperre_salz`** (GitHub-Secret `NAMENSSPERRE_SALZ`, 32 zufällige Bytes in Standard-Base64): Die gespeicherten
  Sperren gesperrter Benutzernamen sind HMAC-Prüfwerte über das Salz. Ein Austausch macht bestehende Sperren unprüfbar; die
  Datei muss dauerhaft erhalten bleiben (Spec 06, `design.md`). Ein Verfahren, das die Sperren beim Wechsel erhält, gibt es nicht.
  Bei Verlust oder Verdacht auf Offenlegung nichts auf eigene Faust ersetzen: den Fall zuerst planen und entscheiden.
- Beide gehören mit in die Notfallunterlagen ([Notfallunterlagen](notfall/notfallunterlagen.md)). Bei Verlust ohne Sicherung
  gibt es keinen Weg zurück.

Geheimnisse gehören nicht in das Repository, in Protokolle oder in Umgebungsvariablen. Der Betreiber verwaltet die
GitHub-Secrets und stellt Secret-Dateien ausschliesslich über den Deploy-Workflow bereit. Bei Verdacht auf Offenlegung
ist der Zugang nach dem dafür freigegebenen Verfahren zu sperren; Nachweise enthalten keine Geheimnisse.

## Übersicht und Grenzen

Die folgende Übersicht beruht auf Spec 06, `design.md`, «Klasse (c): Geheimnisse». Sie beschreibt die vorgesehene
Richtung, **noch keine ausführbare Betriebsanleitung**.

| Zugang | Vorgesehener Wechsel | Vor Freigabe noch zu klären oder nachzuweisen |
| --- | --- | --- |
| Datenbankzugänge und Keycloak-Admin | Zugang beim jeweiligen Dienst ändern, GitHub-Secret und Deploy abstimmen; Notfallunterlagen aktualisieren | Reihenfolge, Wiederanlauf und Rückfall für die einzelnen Datenbankrollen und Keycloak prüfen |
| S3-App-Schlüssel | Neuen Schlüssel im Projekt ausstellen, GitHub-Secrets aktualisieren und deployen | Umstellung, Prüfung und Widerruf des alten Schlüssels nachweisen |
| KI-Token und allfälliger TSA-Zugang | Neuen Zugang beim Anbieter erstellen bzw. ändern, GitHub-Secret aktualisieren und deployen | Tatsächlich verwendete Zugänge, Prüfung und Widerruf nachweisen |
| Archiv-SMTP und Keycloak-SMTP | Passwort beim Postfach ändern, GitHub-Secret aktualisieren und deployen | Unterschiedliche Verbraucher und Reihenfolge der Umstellung prüfen |
| Google-Client-Secret | Secret beim Anbieter erneuern, GitHub-Secret aktualisieren und deployen | Umstellung und Anmeldung nachweisen |
| Keycloak-Dienstkonto | Neues Client-Secret in Keycloak erzeugen, `KEYCLOAK_VERWALTUNG_SECRET` aktualisieren und deployen | Überlappung und Ablaufzeit auf der Zielumgebung prüfen; Zugriff über das Dienstkonto nach dem Deploy und Widerruf des alten Secrets nachweisen |
| API-Tokens | Token widerrufen und neu ausstellen; den neuen Wert dem berechtigten Client sicher übergeben | Betroffene Clients und den Übergang vor dem Widerruf prüfen |
| Webseiten-Schlüssel | Neuen Schlüssel ausstellen und die Webseite umstellen; bei Offenlegung den alten widerrufen | Siehe [Schnittstellenhandbuch](schnittstelle/handbuch.md#2-schlüssel-rotation-und-absendernetze); Umstellung mit Überlappung prüfen |
| Manifest-Schlüssel | Nur mit einem Schlüsselwechselplan wechseln | Alte öffentliche Schlüssel zur Prüfung alter Manifeste aufbewahren; [Manifest-Schlüssel](manifest-schluessel.md#wechsel) beachten |
| Datenschlüssel | Nur mit einem Plan für die Neuverschlüsselung wechseln | Vorhandene verschlüsselte Daten müssen weiterhin lesbar bleiben; ein geprüftes Wechselverfahren fehlt |
| Salz der Namenssperre | Nicht durch ein beliebiges neues Salz ersetzen | Die gespeicherten HMAC-Prüfwerte hängen vom Salz ab. Ein Verfahren zur Erhaltung der Namenssperren und dessen Nachweis fehlen |

## Ablauf für Geheimnisse mit belegtem Weg
Allgemein: Geheimnisse liegen nur als GitHub-Secret und als Secret-Datei unter `/run/secrets/` vor, die der Deploy-Workflow ins
Bundle schreibt; die Pflichtprüfung im Workflow bricht ab, wenn ein Secret fehlt oder ein falsches Format hat. Wechsel nie
direkt auf dem Server, sondern über GitHub-Secret und Deploy.

**Dienstkonto Keycloak (`KEYCLOAK_VERWALTUNG_SECRET`)** nach Spec 06, `design.md`:
1. Keycloak erzeugt ein neues Client-Secret für den Client `archiv-verwaltung` (`kcadm.sh` im Wegwerf-Container, wie im README
   unter «Admin-Kommandos auf der VPS»); den Wert nicht in Terminal-Verlauf, Protokoll oder Nachweis übernehmen.
2. GitHub-Secret `KEYCLOAK_VERWALTUNG_SECRET` setzen und deployen; danach ist das alte Secret ungültig.
3. Nachweis: Token des Dienstkontos ist holbar (Benutzerliste lädt). Keycloak kann zwei Secrets mit Überlappung führen;
   **[offen:** ob und wie lange auf der Zielumgebung, siehe 19.1 und 19.3**]**.
Vorbereitet, aber nicht nachgewiesen: die Rotationsprobe ist Teil der manuellen Abnahme 21.6 und liegt beim Betreiber.

**Stufe-A-Stand (25.11.2026):** `keycloak_verwaltung_secret`, `keycloak_smtp_passwort` und `namenssperre_salz` sind im
Deploy eingerichtet; der Dienstkonto-Zugriff und der Keycloak-Mailversand wurden geprüft. Das belegt die Einrichtung,
nicht einen Secret-Wechsel. Die interne Verbindung mit korrektem Issuer, die automatische Ablaufzeit einer Secret-Überlappung
und die Rotationsprobe sind weiterhin offen (19.3, 21.6). Passkey-Realm und Ereignis-Probe sind keine Rotation; die
kopierbaren Vorlagen und deren Stufe-A-Nachweis stehen in
[deploy/infomaniak/keycloak/README.md](../deploy/infomaniak/keycloak/README.md).

**Keycloak-SMTP (`KEYCLOAK_SMTP_PASSWORT`)** und **Archiv-SMTP**: Passwort beim Postfach ändern, GitHub-Secret aktualisieren,
deployen. **[offen:** Reihenfolge zwischen Postfach und Deploy (Übergang ohne Mailversand) ist nicht belegt.**]**

**Alle weiteren Zeilen** der Tabelle: nur der oben genannte Wechselweg; Details und Nachweise stehen nicht fest.

## Noch offen beim Betreiber
- Die Notfallunterlagen nennen `keycloak_verwaltung_secret`, `keycloak_smtp_passwort` und `namenssperre_salz` nur als
  Platzhalter. Die geschützte Offline-Verwahrung und deren Erreichbarkeit sind nicht bestätigt (21.6); Secret-Werte bleiben
  ausserhalb des Repositorys.
- Rotationsprobe des Dienstkonto-Geheimnisses (21.6) und Betriebsnachweise zur Secret-Überlappung (19.3). Diese
  Betreiberprüfungen stehen getrennt von der Dokumentationsaufgabe 21.5 aus.

Die tatsächliche Rotation, die Verwahrung und die Prüfung auf der Betriebsumgebung liegen beim Betreiber. Die
Rotationsprobe des Dienstkonto-Geheimnisses ist Teil der manuellen Abnahme 21.6; die Betriebsnachweise zur
Secret-Überlappung bleiben in 19.3 offen.
