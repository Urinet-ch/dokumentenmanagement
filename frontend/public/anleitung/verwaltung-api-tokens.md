Unter «API-Tokens» sehen Sie die Zugänge, mit denen Programme ohne Anmeldung ins Archiv schreiben oder lesen, zum Beispiel Generatoren für Rechnungen oder der Import des Altbestands. Die Seite zeigt je Token Name, Scope, wann es erstellt und zuletzt genutzt wurde, das Ablaufdatum und den Status «Aktiv», «Abgelaufen» oder «Widerrufen». Das Geheimnis eines Tokens zeigt die App nie an.

Neue Tokens stellen Sie nicht auf dieser Seite aus. Wie ein neues Token entsteht, steht in den Unterlagen für den Betrieb des Archivs.

## Webseiten-Schlüssel
Für eine Webseite gibt es getrennte Schlüssel zum Lesen und zur Erfassung. Die Person, die WordPress verwaltet, trägt sie in den Plugin-Einstellungen ein; angezeigt werden sie dort später nicht mehr. Leere Schlüsselfelder behalten den bisherigen Wert.
Zum Speichern der Plugin-Einstellungen muss JavaScript im Browser aktiviert sein.

Das Plugin darf erst eingesetzt werden, wenn der Änderungs-Feed zur sofortigen Aktualisierung zurückgezogener Dokumente eingerichtet und geprüft ist. Wenden Sie sich dafür an den Betrieb.

## Ablaufdatum setzen
1. Wählen Sie beim Token unter «Gültig bis einschliesslich» ein Datum, frühestens heute und höchstens fünf Jahre in der Zukunft.
2. Wählen Sie «Ablaufdatum speichern».
3. Mit «Ablaufdatum entfernen» gilt das Token wieder ohne Ablauf.

Ein abgelaufenes Token lässt sich nicht verlängern. Widerrufen Sie es und stellen Sie ein neues Token aus.

## Token widerrufen
1. Wählen Sie beim Token «Token widerrufen».
2. Bestätigen Sie die Frage mit «Widerruf bestätigen» oder wählen Sie «Abbrechen».

Ein widerrufenes Token kann kein Programm mehr verwenden. Das lässt sich nicht rückgängig machen.

> **Achtung:** Ist ein Token verloren gegangen oder in falsche Hände geraten, widerrufen Sie es sofort. Ein Token geben Sie nur über einen sicheren Weg weiter, zum Beispiel einen Passwort-Manager, nie per E-Mail oder Chat.

## Hinweise bei einem Token
- «Dieses Token hat kein Ablaufdatum und wurde seit mindestens einem Jahr nicht genutzt.»: Prüfen Sie, ob ein Programm es noch braucht. Sonst widerrufen Sie es.
- «Dieses Token ist abgelaufen und lässt sich nicht verlängern.»: Widerrufen Sie es und stellen Sie ein neues Token aus.
- «Das API-Token ist nicht mehr vorhanden. Die Liste wurde neu geladen.»: Die Liste zeigt jetzt den aktuellen Stand.
