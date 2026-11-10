# language: de
# Browser-Szenarien der PWA (Aufgabe 2.7). Keycloak ist eine Attrappe im Browser-Test (frontend/e2e/hilfen/):
# Sie prüft PKCE S256 wie Keycloak; die Prüfung der Tokens in der API belegt sicherheit/anmeldung.feature.
@spec-01 @browser
Funktionalität: Anmeldung in der PWA mit OIDC und PKCE

  @req-2.1
  Szenario: Anmeldung mit PKCE, Tokens nur im Arbeitsspeicher
    Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Wenn ich die Startseite öffne
    Und ich mich anmelde
    Dann hat die Anmeldung PKCE mit S256 verwendet
    Und sehe ich «Test mitarbeiter» und «urinet» in der Kopfzeile
    Und liegt kein Token im Browser-Speicher

  @req-2.1
  Szenario: Nach der Anmeldung geht es zur gewünschten Seite
    Angenommen Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/erfassen» öffne
    Dann sehe ich den Knopf «Anmelden»
    Wenn ich mich anmelde
    Dann bin ich auf der Seite «/erfassen»

  @req-2.1
  Szenario: Abmelden beendet die Sitzung auch bei Keycloak
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich auf «Abmelden» klicke
    Dann hat Keycloak die Abmeldung erhalten
    Und sehe ich kein Menü

  @req-2.1
  Szenario: Lehnt Keycloak die Erneuerung ab, meldet die PWA die abgelaufene Sitzung
    Angenommen Keycloak stellt Tokens mit 6 Sekunden Gültigkeit aus
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn Keycloak die Erneuerung der Sitzung ablehnt
    Dann sehe ich die Meldung «Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.»
    Und sehe ich den Knopf «Anmelden»
    Und sehe ich kein Menü

  @req-2.1
  Szenario: Antwortet die API mit 401, meldet die PWA die abgelaufene Sitzung
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und die API meldet die Sitzung als abgelaufen
    Wenn ich im Menü «Erfassen» wähle
    Und ich die Dateien «beleg.pdf» auswähle
    Und ich hochlade
    Dann sehe ich die Meldung «Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.»
    Und sehe ich den Knopf «Anmelden»
