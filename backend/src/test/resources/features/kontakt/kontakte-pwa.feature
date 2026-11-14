# language: de
# Kontakte ansehen und pflegen in der PWA (Aufgabe 4.12, Req 17.1–17.6, 17.17) gegen das echte Archiv im Testmodus:
# Playwright leitet alle Anfragen der PWA an /api/v1/** an das Archiv weiter (BrowserBackendTest mit PostgreSQL,
# Keycloak und MinIO) und tauscht das Token der Keycloak-Attrappe gegen ein echtes Token desselben Testbenutzers
# (frontend/e2e/hilfen/echtes-backend.mjs, Schritte in frontend/e2e/schritte/kontakte.mjs). Kontakte und Dokumente
# entstehen über die API, alle Daten sind synthetisch. Im Test-Realm gibt es «geschaeftsstelle» nur im Mandanten
# «vhf-gsk»; die Szenarien zu Geschäftsstelle und Inhaber im selben Mandanten laufen darum dort. Rollen und Protokoll
# des Archivs selbst belegen die Java-Szenarien in kontakte-pflegen.feature.
@spec-01 @browser
Funktionalität: Kontakte ansehen und pflegen in der PWA

  Hintergrund:
    Angenommen die PWA spricht mit dem Archiv im Testmodus

  @req-17.5
  Szenariogrundriss: Ohne Recht A6 gibt es keinen Menüpunkt, der direkte Aufruf führt zur Startseite und das Archiv verweigert (<breite> px)
    Angenommen der Szenariobeginn ist gemerkt
    Und Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Und das Browserfenster ist <breite> Pixel breit
    Wenn ich die Seite «/kontakte» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/»
    Und sehe ich im geöffneten Menü genau «Erfassen, Eingangskorb, Suche»
    Und hat die PWA die Kontakte-Schnittstelle nicht aufgerufen
    Und verweigert das Archiv «mitarbeiter» im Mandanten «urinet» die Kontaktliste mit 403 «NICHT_ERLAUBT»
    Und enthält das Zugriffsprotokoll von «urinet» eine neue Verweigerung «VERWEIGERT» für die Rolle «MITARBEITER» mit dem Grund «A6»

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.5
  Szenariogrundriss: Verweigert das Archiv trotz Menüpunkt, zeigt die Seite nur die Meldung und keine Kontaktdaten (<breite> px)
    Angenommen «inhaber» im Mandanten «urinet» hat den Kontakt «Verweigert Test AG» in «Altdorf» angelegt
    Und der Szenariobeginn ist gemerkt
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und das Archiv hält mich bei den Kontakten für «treuhaender» im Mandanten «urinet»
    Wenn ich die Kontakte über das Menü öffne
    Dann zeigt die Seite die Meldung «Für die Kontakte fehlt Ihnen das Recht» und keine Kontaktdaten
    Und enthält das Zugriffsprotokoll von «urinet» eine neue Verweigerung «VERWEIGERT» für die Rolle «TREUHAENDER» mit dem Grund «A6»

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.6 @req-17.1
  Szenariogrundriss: Die Geschäftsstelle sieht dieselbe Liste und das Detail, aber keine Aktionen (<breite> px)
    Angenommen «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Ansicht Test GmbH» in «Altdorf» angelegt
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «geschaeftsstelle» im Mandanten «vhf-gsk» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Ansicht Test GmbH» suche
    Dann zeigt die Liste den Kontakt «Ansicht Test GmbH»
    Und sieht «inhaber» im Mandanten «vhf-gsk» bei der Suche nach «Ansicht Test GmbH» dieselben Kontakte wie die Liste
    Wenn ich den Kontakt «Ansicht Test GmbH» öffne
    Dann sehe ich die Kontaktdetails von «Ansicht Test GmbH»
    Und zeigen die Stammdaten bei «Ort» den Wert «Altdorf»
    Und sehe ich keinen der Knöpfe «Bearbeiten», «Zusammenführen …», «Als inaktiv markieren», «Wieder aktivieren»

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.6 @req-17.7
  Szenariogrundriss: Der Inhaber ändert einen Kontakt und führt ihn nach der Rückfrage mit einem anderen zusammen (<breite> px)
    Angenommen «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Alt Test GmbH» in «Altdorf» angelegt
    Und «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Ziel Test AG» in «Luzern» angelegt
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «inhaber» im Mandanten «vhf-gsk» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Alt Test GmbH» suche
    Und ich den Kontakt «Alt Test GmbH» öffne
    Dann sehe ich die Knöpfe «Bearbeiten», «Zusammenführen …», «Als inaktiv markieren»
    Und sehe ich keinen der Knöpfe «Wieder aktivieren»
    Wenn ich die Aktion «Bearbeiten» wähle
    Und ich im Formular das Feld «Ort» auf «Erstfeld» ändere
    Und ich das Formular speichere
    Dann meldet die Seite «Die Änderungen sind gespeichert.»
    Und steht beim Kontakt «Alt Test GmbH» im Archiv bei «ort» der Wert «Erstfeld»
    Wenn ich die Aktion «Zusammenführen …» wähle
    Und ich den Kontakt «Ziel Test AG» als Ziel suche und wähle
    Und ich als Grund «Dublette aus dem Import» angebe
    Und ich im Dialog auf «Weiter» klicke
    Dann fragt die Seite zurück, ob «Alt Test GmbH» in «Ziel Test AG» zusammengeführt werden soll
    Und hat die PWA noch nichts zu «zusammenfuehren» gesendet
    Und ist der Kontakt «Alt Test GmbH» im Archiv aktiv
    Wenn ich die Rückfrage mit «Zusammenführen» bestätige
    Dann meldet die Seite «zusammengeführt in»
    Und hat die PWA «zusammenfuehren» mit «If-Match» und dem Grund «Dublette aus dem Import» gesendet
    Und zeigt die Seite den Link auf den Zielkontakt «Ziel Test AG»
    Und ist der Kontakt «Alt Test GmbH» im Archiv inaktiv und in «Ziel Test AG» zusammengeführt
    Und sehe ich keinen der Knöpfe «Bearbeiten», «Zusammenführen …», «Als inaktiv markieren», «Wieder aktivieren»
    Und erscheint der Kontakt «Alt Test GmbH» in der Partnerauswahl des Archivs nicht mehr

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.6 @req-17.12
  Szenariogrundriss: Der Inhaber markiert einen Kontakt nach der Rückfrage als inaktiv und aktiviert ihn wieder (<breite> px)
    Angenommen «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Falsch Test AG» in «Stans» angelegt
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «inhaber» im Mandanten «vhf-gsk» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Falsch Test AG» suche
    Und ich den Kontakt «Falsch Test AG» öffne
    Und ich die Aktion «Als inaktiv markieren» wähle
    Und ich als Grund «Falscher Eintrag» angebe
    Dann hat die PWA noch nichts zu «inaktiv» gesendet
    Wenn ich die Rückfrage abbreche
    Dann ist der Kontakt «Falsch Test AG» im Archiv aktiv
    Und hat die PWA noch nichts zu «inaktiv» gesendet
    Wenn ich die Aktion «Als inaktiv markieren» wähle
    Und ich als Grund «Falscher Eintrag» angebe
    Und ich die Rückfrage mit «Als inaktiv markieren» bestätige
    Dann meldet die Seite «inaktiv markiert»
    Und hat die PWA «inaktiv» mit «If-Match» und dem Grund «Falscher Eintrag» gesendet
    Und ist der Kontakt «Falsch Test AG» im Archiv inaktiv
    Und erscheint der Kontakt «Falsch Test AG» in der Partnerauswahl des Archivs nicht mehr
    Und sehe ich die Knöpfe «Wieder aktivieren»
    Wenn ich «Wieder aktivieren» wähle
    Dann meldet die Seite «wieder aktiv»
    Und hat die PWA «aktiv» mit «If-Match» und dem Grund «» gesendet
    Und ist der Kontakt «Falsch Test AG» im Archiv aktiv

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.17
  Szenariogrundriss: Liste, Detail und Zusammenführen-Dialog laufen ohne waagrechtes Scrollen, die Rückfrage kommt vor der Ausführung (<breite> px)
    Angenommen «inhaber» im Mandanten «urinet» hat diese Kontakte angelegt:
      | name                                   | plz  | ort                           | email                                                                                      | iban                                   |
      | Lange <b>Fett</b> קבלה Musterfirma     | 6460 | Altdorf-mit-sehr-langem-Namen | sehr.lange.adresse.fuer.den.test.der.breite@sehr-langer-domainname-fuer-den-test.beispiel.example | CH00 0000 0000 0000 0000 0000 0000 000 |
      | Ziel Handy Test AG                     | 6000 | Luzern                        |                                                                                            |                                        |
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Lange <b>Fett</b> קבלה Musterfirma» suche
    Dann zeigt die Liste den Kontakt «Lange <b>Fett</b> קבלה Musterfirma» mit dem Namen als reinem Text
    Und lässt sich die Kontaktseite nicht waagrecht scrollen
    Wenn ich den Kontakt «Lange <b>Fett</b> קבלה Musterfirma» öffne
    Dann sehe ich die Kontaktdetails von «Lange <b>Fett</b> קבלה Musterfirma»
    Und zeigt die Seite die Nutzer-Texte von «Lange <b>Fett</b> קבלה Musterfirma» nur als Text
    Und lässt sich die Kontaktseite nicht waagrecht scrollen
    Wenn ich die Aktion «Bearbeiten» wähle
    Dann lässt sich die Kontaktseite nicht waagrecht scrollen
    Wenn ich die Aktion «Zusammenführen …» wähle
    Dann liegt der Dialog innerhalb des Bildschirms
    Und lässt sich die Kontaktseite nicht waagrecht scrollen
    Wenn ich den Kontakt «Ziel Handy Test AG» als Ziel suche und wähle
    Und ich als Grund «Dublette» angebe
    Und ich im Dialog auf «Weiter» klicke
    Dann fragt die Seite zurück, ob «Lange <b>Fett</b> קבלה Musterfirma» in «Ziel Handy Test AG» zusammengeführt werden soll
    Und liegt der Dialog innerhalb des Bildschirms
    Und lässt sich die Kontaktseite nicht waagrecht scrollen
    Und hat die PWA noch nichts zu «zusammenfuehren» gesendet
    Wenn ich die Rückfrage abbreche
    Dann ist der Kontakt «Lange <b>Fett</b> קבלה Musterfirma» im Archiv aktiv
    Und hat die PWA noch nichts zu «zusammenfuehren» gesendet

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.1 @req-17.3 @req-17.4
  Szenariogrundriss: Suche, Detail und Dokumentliste: nur was das Archiv liefert, ohne eigene Zahl, ohne verworfene Dokumente (<breite> px)
    Angenommen «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Garage Test AG» in «Altdorf» angelegt
    Und «inhaber» im Mandanten «vhf-gsk» hat das Dokument «Offerte_Garage.pdf» dem Kontakt «Garage Test AG» mit Vertraulichkeit «NORMAL» zugeordnet
    Und «inhaber» im Mandanten «vhf-gsk» hat das Dokument «Interne_Kalkulation.pdf» dem Kontakt «Garage Test AG» mit Vertraulichkeit «INTERN» zugeordnet
    Und «inhaber» im Mandanten «vhf-gsk» hat das Dokument «Entwurf_Verworfen.pdf» dem Kontakt «Garage Test AG» mit Vertraulichkeit «NORMAL» zugeordnet
    Und «inhaber» im Mandanten «vhf-gsk» hat das Dokument «Entwurf_Verworfen.pdf» verworfen
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «geschaeftsstelle» im Mandanten «vhf-gsk» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Garage Test AG» suche
    Und ich den Kontakt «Garage Test AG» öffne
    Dann sehe ich die Kontaktdetails von «Garage Test AG»
    Und zeigen die Stammdaten bei «Ort» den Wert «Altdorf»
    Und zeigt die Dokumentliste genau «Offerte_Garage.pdf», «Interne_Kalkulation.pdf»
    Und zeigt die Dokumentliste den Titel von «Offerte_Garage.pdf»
    Und nennt die Dokumentliste die Gesamtzahl 2 wie die Antwort des Archivs
    Und hat die PWA die Dokumente des Kontakts «Garage Test AG» nach Datum ohne Suchbegriff angefragt

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-17.1 @req-1.4
  Szenariogrundriss: Der Kontakt eines anderen Mandanten ist weder in der Liste noch per direktem Aufruf sichtbar (<breite> px)
    Angenommen «inhaber» im Mandanten «vhf-gsk» hat den Kontakt «Fremd Test AG» in «Altdorf» angelegt
    Und «inhaber» im Mandanten «urinet» hat den Kontakt «Eigen Test AG» in «Luzern» angelegt
    Und das Browserfenster ist <breite> Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Kontakte über das Menü öffne
    Und ich nach dem Kontakt «Fremd Test AG» suche
    Dann zeigt die Liste keine Kontakte
    Wenn ich nach dem Kontakt «Eigen Test AG» suche
    Dann zeigt die Liste den Kontakt «Eigen Test AG»
    Wenn ich die Adresse des Kontakts «Fremd Test AG» aus dem Mandanten «vhf-gsk» direkt aufrufe
    Dann zeigt die Seite «nicht gefunden» ohne Kontaktdaten

    Beispiele:
      | breite |
      | 375    |
      | 1280   |
