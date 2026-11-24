# language: de
# Die REST-Rechte und Mandantentrennung prüft das Backend; die PWA-Szenarien verwenden synthetische Antworten.
@spec-06 @browser @attrappe
Funktionalität: Benutzer und Rechte in der PWA lesen

  @req-8.1 @req-8.2 @req-8.3 @req-8.7
  Szenariogrundriss: Der Inhaber liest Benutzer, Anträge und Rechte barrierefrei
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Dann zeigt die Benutzerverwaltung Liste, Anträge und Rollen ohne Überlauf und axe-Befund
    Wenn ich den synthetischen Benutzer öffne
    Dann zeigt das Benutzerdetail Status, Rechte und Token-Hinweis ohne Überlauf und axe-Befund

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-8.1
  Szenariogrundriss: Ohne A21 sind Liste und Detail nicht sichtbar
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und Keycloak kennt mich als «<rolle>» im Mandanten «urinet»
    Wenn ich die Seite «<pfad>» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/nicht-gefunden»
    Und fehlt der Menüpunkt Benutzer und es wurde kein Benutzer abgefragt

    Beispiele:
      | rolle            | pfad                        |
      | mitarbeiter      | /verwaltung/benutzer        |
      | mitarbeiter      | /verwaltung/benutzer/probe  |
      | treuhaender      | /verwaltung/benutzer        |
      | geschaeftsstelle | /verwaltung/benutzer/probe  |

  @req-8.4
  Szenariogrundriss: Eine Benutzeraktion wird erst nach sichtbarer Bestätigung gesendet
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich öffne den Dialog zum Sperren
    Dann zeigt der Sperrdialog die Auswirkung und wartet auf die Bestätigung
    Wenn ich die Sperrung bestätige
    Dann wird die Sperrung erst nach der Bestätigung einmal gesendet

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-8.4 @req-8.6
  Szenariogrundriss: Die Einladung nennt die E-Mail, aber zeigt kein Passwort oder Link
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich sende eine synthetische Benutzereinladung
    Dann wird die Einladung bestätigt, ohne Passwort oder Link anzuzeigen

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-8.4 @req-8.6
  Szenariogrundriss: Profil, Rollenwechsel und Löschen sind barrierefrei bedienbar
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und die synthetischen Rollen enthalten zusätzlich «inhaber»
    Und der synthetische Benutzer ist deaktiviert
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich das Profil, den Rollendialog und den Löschdialog prüfe
    Dann sind Profil, Rollendialog und Löschdialog ohne Überlauf und axe-Befund bedienbar

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 375 | dunkel |
      | 1280 | hell |
      | 1280 | dunkel |

  @req-8.5
  Szenariogrundriss: Das Sperren wird nach frischer Anmeldung genau einmal ausgeführt
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und die Benutzerverwaltung verlangt vor «sperren» ein Step-up
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/benutzer/probe» öffne
    Und ich mich anmelde
    Und ich öffne den Dialog zum Sperren
    Und ich die Sperrung bestätige
    Dann wird die Aktion «sperren» nach Step-up genau einmal ausgeführt
    Und liegt nichts Persönliches im Browser-Speicher

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 1280 | dunkel |

  @req-8.5
  Szenariogrundriss: Die Rollenänderung wird nach frischer Anmeldung genau einmal mit demselben ETag gesendet
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und die synthetischen Rollen enthalten zusätzlich «inhaber»
    Und die Benutzerverwaltung verlangt vor «rolle» ein Step-up
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/benutzer/probe» öffne
    Und ich mich anmelde
    Und ich die Rolle auf «inhaber» ändere
    Dann wird die Aktion «rolle» nach Step-up genau einmal ausgeführt
    Und liegt nichts Persönliches im Browser-Speicher

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 1280 | dunkel |

  @req-8.5
  Szenariogrundriss: Das Löschen fragt nach frischer Anmeldung Namen und Grund neu ab
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer ist deaktiviert
    Und die Benutzerverwaltung verlangt vor «loeschen» ein Step-up
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/benutzer/probe» öffne
    Und ich mich anmelde
    Und ich den Löschdialog öffne
    Und ich im Löschdialog den Benutzer «a.beispiel» mit dem Grund «Sicherheit» bestätige
    Dann öffnet sich der Löschdialog nach dem Step-up erneut ohne gespeicherte Eingaben
    Wenn ich im Löschdialog den Benutzer «a.beispiel» mit dem Grund «Sicherheit» bestätige
    Dann wurde der Benutzer nach dem Step-up genau einmal gelöscht
    Und liegt nichts Persönliches im Browser-Speicher

    Beispiele:
      | breite | schema |
      | 375 | dunkel |
      | 1280 | hell |

  @req-8.5
  Szenariogrundriss: Die Profiländerung wird nach frischer Anmeldung neu erfasst, nicht aus dem Speicher gesendet
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und die Benutzerverwaltung verlangt vor «profil» ein Step-up
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/benutzer/probe» öffne
    Und ich mich anmelde
    Und ich den Vornamen auf «Neuername» ändere und speichere
    Dann zeigt das Profil nach dem Step-up den Hinweis zur erneuten Eingabe
    Wenn ich den Vornamen auf «Neuername» ändere und speichere
    Dann wurde die Profiländerung nach dem Step-up genau einmal ausgeführt
    Und liegt nichts Persönliches im Browser-Speicher

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 1280 | dunkel |

  @req-8.5
  Szenariogrundriss: Eine verlangte Bestätigung wird gemeldet und nicht erneut gesendet
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und die Benutzerverwaltung verlangt vor «sperren» eine Bestätigung
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich öffne den Dialog zum Sperren
    Und ich die Sperrung bestätige
    Dann zeigt die Benutzerverwaltung die Bestätigungsnotwendigkeit und sendet nichts erneut

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 1280 | dunkel |

  @req-10.6
  Szenariogrundriss: Liste und Detail zeigen Passkey eingerichtet mit Anzahl, ohne Gerätedaten, und filtern danach
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer hat 2 Passkeys
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Dann zeigt die Benutzerliste «Passkey eingerichtet: Ja, 2 Passkeys» ohne Überlauf und axe-Befund
    Wenn ich die Benutzerliste nach «Mit Passkey» filtere
    Dann wurde die Benutzerliste mit dem Filter «ja» abgefragt
    Wenn ich den synthetischen Benutzer öffne
    Dann zeigt das Benutzerdetail «Passkey eingerichtet» «Ja, 2 Passkeys» ohne Gerätedaten, Überlauf und axe-Befund

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 375 | dunkel |
      | 1280 | hell |
      | 1280 | dunkel |

  @req-10.5
  Szenariogrundriss: Beim Zugang zurücksetzen wählt der Inhaber die Option Passkeys löschen
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer hat 2 Passkeys
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich öffne den Dialog zum Zugang zurücksetzen
    Dann zeigt der Dialog die Option Passkeys löschen mit der Zahl 2, nicht gewählt, ohne Überlauf und axe-Befund
    Wenn ich die Option Passkeys wähle und bestätige
    Dann wird der Zugang genau einmal mit der Option Passkeys zurückgesetzt

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 375 | dunkel |
      | 1280 | hell |
      | 1280 | dunkel |

  @req-10.5
  Szenario: Ohne gewählte Option wird der Zugang wie bisher ohne Passkeys zurückgesetzt
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer hat 2 Passkeys
    Und das Browserfenster ist 375 Pixel breit
    Und das Farbschema ist «hell»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich öffne den Dialog zum Zugang zurücksetzen
    Und ich ohne die Option Passkeys bestätige
    Dann wird der Zugang genau einmal ohne die Option Passkeys zurückgesetzt

  @req-10.5
  Szenariogrundriss: Das Zurücksetzen mit Passkeys wird nach frischer Anmeldung genau einmal ausgeführt
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer hat 2 Passkeys
    Und die Benutzerverwaltung verlangt vor «zugang» ein Step-up
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/benutzer/probe» öffne
    Und ich mich anmelde
    Und ich öffne den Dialog zum Zugang zurücksetzen
    Und ich die Option Passkeys wähle und bestätige
    Dann wird der Zugang nach Step-up genau einmal mit der Option Passkeys zurückgesetzt
    Und liegt nichts Persönliches im Browser-Speicher

    Beispiele:
      | breite | schema |
      | 375 | hell |
      | 1280 | dunkel |

  @req-10.5
  Szenario: Ein Fehler des Identitätsdienstes beim Zurücksetzen wird angezeigt und die Aktion bleibt wiederholbar
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und der synthetische Benutzer hat 2 Passkeys
    Und die Benutzerverwaltung antwortet beim Zurücksetzen des Zugangs mit dem Fehler «IDENTITAETSDIENST_NICHT_ERREICHBAR»
    Und das Browserfenster ist 375 Pixel breit
    Und das Farbschema ist «dunkel»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Und ich den synthetischen Benutzer öffne
    Und ich öffne den Dialog zum Zugang zurücksetzen
    Und ich die Option Passkeys wähle und bestätige
    Dann zeigt das Benutzerdetail die Fehlermeldung des Identitätsdienstes ohne Überlauf und axe-Befund
