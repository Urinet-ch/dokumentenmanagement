# language: de
# Die Einrichtung des Passkeys bleibt in der Kontoverwaltung von Keycloak (passkey.feature); hier prüft die PWA nur den Einstieg
# mit einer Attrappe für die Kontoverwaltung und für Keycloak.
@spec-06 @browser @attrappe
Funktionalität: Einstieg zur Kontoverwaltung für den Passkey

 @req-10.1
 Szenariogrundriss: Der angemeldete Benutzer öffnet die Kontoverwaltung in einem neuen Tab
  Angenommen das Browserfenster ist <breite> Pixel breit
  Und das Farbschema ist «<schema>»
  Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Und die Kontoverwaltung des Realms liefert eine synthetische Seite
  Dann sehe ich den Einstieg «Passkey einrichten» in der Kopfzeile ohne Überlauf und axe-Befund
  Wenn ich den Einstieg «Passkey einrichten» in der Kopfzeile wähle
  Dann zeigt ein neuer Tab die Kontoverwaltung des Realms
  Und bin ich auf der Seite «/»

  Beispiele:
   | breite | schema |
   | 375 | hell |
   | 375 | dunkel |
   | 1280 | hell |
   | 1280 | dunkel |

 @req-10.1
 Szenario: In «Erfassen» bleibt die Seite beim Öffnen der Kontoverwaltung stehen
  Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Und die Kontoverwaltung des Realms liefert eine synthetische Seite
  Und ich im Menü «Erfassen» wähle
  Wenn ich den Einstieg «Passkey einrichten» in der Kopfzeile wähle
  Dann zeigt ein neuer Tab die Kontoverwaltung des Realms
  Und bin ich auf der Seite «/erfassen»

 @req-10.1
 Szenario: Ohne Anmeldung gibt es keinen Einstieg
  Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
  Wenn ich die Startseite öffne
  Dann sehe ich den Knopf «Anmelden»
  Und fehlt der Einstieg «Passkey einrichten» in der Kopfzeile
