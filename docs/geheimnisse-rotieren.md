# Geheimnisse rotieren

> **Vorbereitung, nicht freigegeben.** Die konkreten Abläufe und ihre Betriebsnachweise fehlen noch.
> Diese Übersicht ersetzt weder eine Rotationsprobe noch die Notfallunterlagen. Aufgabe 21.5 bleibt offen.

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

Die tatsächliche Rotation, die Verwahrung und die Prüfung auf der Betriebsumgebung liegen beim Betreiber. Die
Rotationsprobe des Dienstkonto-Geheimnisses ist Teil der manuellen Abnahme 21.6; die Betriebsnachweise zur
Secret-Überlappung bleiben in 19.3 offen.
