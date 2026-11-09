# language: de
@spec-01 @rauchtest
Funktionalität: Rauchtest Backend

  Szenariogrundriss: Der Health-Endpunkt «<pfad>» meldet «UP»
    Wenn ich «<pfad>» abfrage
    Dann antwortet das Archiv mit Status 200 und «UP»

    Beispiele:
      | pfad            |
      | /q/health/live  |
      | /q/health/ready |
