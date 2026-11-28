# language: de
# Seite «Mein Konto» (Spec 06, Requirement 11) in der PWA mit der Keycloak-Attrappe; die Kontoverwaltung ist eine
# synthetische Seite. Die Grenzen der echten Kontoverwaltung (11.5) prüft kontoverwaltung-pwa.feature gegen Keycloak.
@spec-06 @browser @attrappe
Funktionalität: Mein Konto

 @req-11.1 @req-11.2 @req-11.12
 Szenariogrundriss: Das Benutzerelement führt zu «Mein Konto», die Kopfzeile zeigt angemeldet nur «Hilfe» und das Element
  Angenommen das Browserfenster ist <breite> Pixel breit
  Und das Farbschema ist «<schema>»
  Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
  Dann zeigt die Kopfzeile rechts nur «Hilfe» und «Mein Konto: Test inhaber Inhaber · urinet»
  Und zeigt das Benutzerelement die Initialen «TI» und ab 960 px auch den Namen «Test inhaber»
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann ist das Benutzerelement als aktuelle Seite markiert
  Und zeigt «Mein Konto» die Abschnitte «Angaben zur Person, Anmeldung und Sicherheit, Darstellung, Hilfe und Einführung, Dieses Gerät, Abmelden»
  Und ist «Mein Konto» ohne Überlauf und axe-Befund
  Und hat «Mein Konto» keine Daten an das Archiv gesendet

  Beispiele:
   | breite | schema |
   | 375 | hell |
   | 375 | dunkel |
   | 1280 | hell |
   | 1280 | dunkel |

 @req-11.1 @req-11.11
 Szenario: Ohne Mandant erreicht der Benutzer «Mein Konto» mit den vorhandenen Angaben
  Angenommen Keycloak kennt mich als «mitarbeiter» ohne Mandant
  Wenn ich die Startseite öffne
  Und ich mich anmelde
  Und ich «Mein Konto» in der Kopfzeile wähle
  Dann zeigt «Mein Konto» die Abschnitte «Angaben zur Person, Anmeldung und Sicherheit, Darstellung, Dieses Gerät, Abmelden»
  Und zeigt «Mein Konto» unter «Angaben zur Person» «Name: Test mitarbeiter; Benutzername: test.mitarbeiter; E-Mail-Adresse: test.mitarbeiter@example.invalid; Rolle: Mitarbeiter»
  Und fehlt auf «Mein Konto» der Abschnitt «Hilfe und Einführung»

 @req-11.2
 Szenario: Ohne Anmeldung bleibt der Farbschema-Knopf in der Kopfzeile
  Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
  Wenn ich die Startseite öffne
  Dann sehe ich den Knopf «Anmelden»
  Und zeigt die Kopfzeile ohne Anmeldung rechts nur den Farbschema-Knopf und «Hilfe»

 @req-11.2
 Szenario: Bei abgelaufener Sitzung bleibt «Abmelden» in der Kopfzeile erreichbar
  Angenommen Keycloak stellt Tokens mit 6 Sekunden Gültigkeit aus
  Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
  Wenn Keycloak die Erneuerung der Sitzung ablehnt
  Dann sehe ich die Meldung «Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.»
  Und sehe ich den Knopf «Abmelden»
  Und fehlt das Benutzerelement in der Kopfzeile

 @req-11.3
 Szenario: «Angaben zur Person» zeigt nur lesend, was das Token enthält, und keine Kennungen
  Angenommen ich bin als «geschaeftsstelle» im Mandanten «urinet» angemeldet
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann zeigt «Mein Konto» unter «Angaben zur Person» «Name: Test geschaeftsstelle; Benutzername: test.geschaeftsstelle; E-Mail-Adresse: test.geschaeftsstelle@example.invalid; Rolle: Geschäftsstelle; Mandant: urinet»
  Und zeigt «Mein Konto» weder Token noch Benutzerkennung

 @req-11.4
 Szenario: Die Einstiege in die Kontoverwaltung öffnen einen neuen Tab
  Angenommen ich bin als «treuhaender» im Mandanten «urinet» angemeldet
  Und die Kontoverwaltung des Realms liefert eine synthetische Seite
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann bietet «Mein Konto» die Einstiege «Passkeys verwalten, Passwort ändern, Einmalcode (Authenticator) verwalten, Angemeldete Geräte» in einem neuen Tab
  Und enthält «Mein Konto» kein Eingabefeld für Zugangsdaten
  Wenn ich den Einstieg «Angemeldete Geräte» auf «Mein Konto» wähle
  Dann zeigt ein neuer Tab die Kontoverwaltung des Realms unter «account-security/device-activity»
  Und bin ich auf der Seite «/konto»

 @req-11.4 @req-11.11
 Szenario: Offline sind die Einstiege deaktiviert, Darstellung, «Dieses Gerät» und «Abmelden» bleiben nutzbar
  Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Und das Handy offline ist
  Dann sind die Einstiege in die Kontoverwaltung deaktiviert und «Mein Konto» sagt warum
  Wenn ich unter «Darstellung» «Dunkel» wähle
  Dann gilt das Farbschema «dunkel» und ist nur im Browser gemerkt
  Und zeigt «Mein Konto» «Auf diesem Gerät wartet keine Aufnahme von Ihnen auf den Upload.»
  Und ist auf «Mein Konto» der Knopf «Abmelden» bedienbar

 @req-11.6
 Szenario: Eine bisher gespeicherte Wahl gilt weiter, «Wie das Gerät» entfernt sie
  Angenommen im Browser ist das Farbschema «hell» gespeichert
  Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann ist unter «Darstellung» «Hell» gewählt
  Wenn ich unter «Darstellung» «Dunkel» wähle
  Dann gilt das Farbschema «dunkel» und ist nur im Browser gemerkt
  Wenn ich unter «Darstellung» «Wie das Gerät» wähle
  Dann folgt die App dem Farbschema des Geräts und hat keine Wahl gespeichert

 @req-11.7
 Szenario: «Einführung erneut zeigen» öffnet die Einführung ab Schritt 1
  Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Und ich auf «Mein Konto» «Einführung erneut zeigen» wähle
  Dann zeigt die Startseite die Einführung ab Schritt 1

 @req-11.7
 Szenario: «Anleitung öffnen» führt zur ganzen Anleitung
  Angenommen ich bin als «treuhaender» im Mandanten «urinet» angemeldet
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Und ich auf «Mein Konto» «Anleitung öffnen» wähle
  Dann bin ich auf der Seite «/hilfe»

 @req-11.8
 Szenario: «Dieses Gerät» zählt nur die eigenen Aufnahmen
  Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
  Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
  Und das Archiv antwortet auf den nächsten Upload mit Status 503
  Wenn ich im Menü «Erfassen» wähle
  Und ich 1 Seite aufnehme
  Und ich die Erfassung abschliesse
  Dann hat das Archiv einen Upload-Versuch erhalten
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Dann zeigt «Mein Konto» «1 Aufnahme von Ihnen wartet auf diesem Gerät auf den Upload oder wurde nicht hochgeladen.»
  Und führt auf «Mein Konto» der Link zur Warteschlange zur Seite «/erfassen»
  Wenn ich mich unter «Mein Konto» abmelde
  Dann hat Keycloak die Abmeldung erhalten
  Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
  Wenn ich mich anmelde
  Und ich «Mein Konto» in der Kopfzeile wähle
  Dann zeigt «Mein Konto» «Auf diesem Gerät wartet keine Aufnahme von Ihnen auf den Upload.»
  Und liegen 1 Erfassung im lokalen Speicher

 @req-11.9
 Szenario: «Lokale Einstellungen entfernen» löscht nach der Rückfrage genau die eigenen Einstellungen
  Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
  Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
  Und das Archiv antwortet auf den nächsten Upload mit Status 503
  Wenn ich im Menü «Erfassen» wähle
  Und ich 1 Seite aufnehme
  Und ich die Erfassung abschliesse
  Dann hat das Archiv einen Upload-Versuch erhalten
  Angenommen auf diesem Gerät liegen lokale Einstellungen von mir und von anderen
  Wenn ich «Mein Konto» in der Kopfzeile wähle
  Und ich auf «Mein Konto» «Lokale Einstellungen entfernen» wähle
  Dann nennt die Rückfrage alles, was entfernt wird und was bleibt
  Wenn ich die Rückfrage auf «Mein Konto» mit «Entfernen» bestätige
  Dann sind nur meine lokalen Einstellungen entfernt
  Und liegen 1 Erfassung im lokalen Speicher
  Und bin ich weiterhin angemeldet

 @req-11.10
 Szenario: «Abmelden» auf «Mein Konto» beendet die Sitzung wie bisher auch bei Keycloak
  Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
  Wenn ich mich unter «Mein Konto» abmelde
  Dann hat Keycloak die Abmeldung erhalten
  Und sehe ich kein Menü

 @req-11.11
 Szenario: Ohne Anmeldung führt «/konto» zur Anmeldung und danach zurück
  Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
  Wenn ich die Seite «/konto» öffne
  Dann sehe ich den Knopf «Anmelden»
  Wenn ich mich anmelde
  Dann bin ich auf der Seite «/konto»
