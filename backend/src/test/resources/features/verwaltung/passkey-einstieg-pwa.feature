# language: de
# Die Einrichtung des Passkeys bleibt in der Kontoverwaltung von Keycloak (passkey.feature); hier prüft die PWA nur den Einstieg
# mit einer Attrappe für die Kontoverwaltung und für Keycloak. Seit Aufgabe 26.1 (Spec 06, Req 11.4) liegt der Einstieg auf
# «Mein Konto» («Passkeys verwalten») statt in der Kopfzeile.
@spec-06 @browser @attrappe
Funktionalität: Einstieg zur Kontoverwaltung für den Passkey

 @req-10.1
 Szenariogrundriss: Der angemeldete Benutzer öffnet die Kontoverwaltung über «Mein Konto» in einem neuen Tab
  Angenommen das Browserfenster ist <breite> Pixel breit
  Und das Farbschema ist «<schema>»
  Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Und die Kontoverwaltung des Realms liefert eine synthetische Seite
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann sehe ich den Einstieg «Passkeys verwalten» auf «Mein Konto» ohne Überlauf und axe-Befund
  Wenn ich den Einstieg «Passkeys verwalten» auf «Mein Konto» wähle
  Dann zeigt ein neuer Tab die Kontoverwaltung des Realms unter «account-security/signing-in»
  Und bin ich auf der Seite «/konto»

  Beispiele:
   | breite | schema |
   | 375 | hell |
   | 375 | dunkel |
   | 1280 | hell |
   | 1280 | dunkel |

 @req-10.1
 Szenario: Die Kopfzeile zeigt keinen eigenen Einstieg mehr
  Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Dann fehlt der Einstieg «Passkey einrichten» in der Kopfzeile

 @req-10.1
 Szenario: Ohne Anmeldung gibt es keinen Einstieg
  Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
  Wenn ich die Startseite öffne
  Dann sehe ich den Knopf «Anmelden»
  Und fehlt der Einstieg «Passkey einrichten» in der Kopfzeile
