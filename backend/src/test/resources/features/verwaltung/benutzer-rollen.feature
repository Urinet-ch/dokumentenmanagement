# language: de
@spec-06
Funktionalität: Benutzerrollen und Berechtigungen
  Der Mandant kann ausschliesslich die festgelegten Archiv-Rollen vergeben; Rechte und Eskalationsschutz stammen aus
  der Domain.

  @req-4.1
  Szenario: Die Rechtematrix legt A20 bis A23 nur für den Inhaber frei
    Dann gelten für A20 bis A23 diese Rechte:
      | Nr. | inhaber | mitarbeiter | treuhaender | geschaeftsstelle |
      | A20 | erlaubt | 403         | 403         | 403              |
      | A21 | erlaubt | 403         | 403         | 403              |
      | A22 | erlaubt | 403         | 403         | 403              |
      | A23 | erlaubt | 403         | 403         | 403              |

  @req-4.2
  Szenario: Eine Rolle ohne Verwaltungsrecht wird mit 403 verweigert
    Angenommen die Rolle «mitarbeiter» fragt Aktion «A22» an
    Dann wird die Anfrage mit HTTP-Status 403 verweigert

  @req-4.2 @verwaltung-rechte-http
  Szenariogrundriss: Jede Rolle ohne Verwaltungsrecht wird bei jeder Aktion protokolliert verweigert
    Angenommen «<rolle>» ist im Test-Realm angemeldet
    Wenn die Person die Verwaltungsaktion «<aktion>» über HTTP aufruft
    Dann wird der Aufruf mit «NICHT_ERLAUBT» und einem Verweigerungseintrag beantwortet

    Beispiele:
      | rolle            | aktion |
      | mitarbeiter      | A20    |
      | mitarbeiter      | A21    |
      | mitarbeiter      | A22    |
      | mitarbeiter      | A23    |
      | treuhaender      | A20    |
      | treuhaender      | A21    |
      | treuhaender      | A22    |
      | treuhaender      | A23    |
      | geschaeftsstelle | A20    |
      | geschaeftsstelle | A21    |
      | geschaeftsstelle | A22    |
      | geschaeftsstelle | A23    |

  @req-4.4 @req-4.6
  Szenario: Eine bekannte Archiv-Rolle ausserhalb des Mandantensatzes wird abgewiesen
    Angenommen «inhaber» will einer anderen Person die Rolle «geschaeftsstelle» zuweisen; vergebbar sind «inhaber,mitarbeiter,treuhaender»
    Wenn die Rollenvergabe geprüft wird
    Dann lautet das Ergebnis «ESKALATION_VERBOTEN»

  @req-4.4
  Szenario: Eine unbekannte Realm-Rolle ist keine vergebbare Archiv-Rolle
    Angenommen «inhaber» will einer anderen Person die Rolle «realm-admin» zuweisen; vergebbar sind «inhaber,mitarbeiter,treuhaender»
    Wenn die Rollenvergabe geprüft wird
    Dann lautet das Ergebnis «KONFIGURATION_UNGUELTIG»

  @req-4.6
  Szenario: Der Inhaber kann seine eigene Rolle nicht ändern
    Angenommen «inhaber» will sich selbst die Rolle «treuhaender» zuweisen; vergebbar sind «inhaber,mitarbeiter,treuhaender»
    Wenn die Rollenvergabe geprüft wird
    Dann lautet das Ergebnis «ESKALATION_VERBOTEN»

  @req-4.6
  Szenariogrundriss: Der Inhaber kann sich nicht selbst sperren, deaktivieren, löschen oder den Zugang zurücksetzen
    Angenommen «inhaber» richtet die Aktion «<aktion>» gegen sich selbst
    Wenn der Selbstzugriff geprüft wird
    Dann lautet das Ergebnis «ESKALATION_VERBOTEN»

    Beispiele:
      | aktion               |
      | SPERREN              |
      | DEAKTIVIEREN         |
      | LOESCHEN             |
      | ZUGANG_ZURUECKSETZEN |

  @req-4.7
  Szenario: Der letzte aktive Inhaber kann nicht entfernt werden
    Angenommen die Zielrolle «inhaber» gehört zu einem aktiven Benutzer und es gibt 1 aktive Inhaber
    Wenn die Zielrolle entfernt wird
    Dann wird das Entfernen als «true» beurteilt

  @req-4.7
  Szenario: Ein Mandant behält einen weiteren aktiven Inhaber
    Angenommen die Zielrolle «inhaber» gehört zu einem aktiven Benutzer und es gibt 2 aktive Inhaber
    Wenn die Zielrolle entfernt wird
    Dann wird das Entfernen als «false» beurteilt
