# language: de
@spec-06 @browser @attrappe
Funktionalität: Wissenseinstellungen in der PWA

  @req-2.13
  Szenariogrundriss: Der Inhaber prüft eine riskante Budgetänderung
    Angenommen die Wissenseinstellungen liefern synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/wissensbasis/einstellungen» öffne
    Und ich mich anmelde
    Dann sind die Wissenseinstellungen ohne Überlauf und axe-Befund sichtbar
    Wenn ich ein erhöhtes Wissensbudget bestätige
    Dann wird vor dem Speichern eine erneute Anmeldung verlangt

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |
