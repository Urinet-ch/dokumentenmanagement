# language: de
@rauchtest
Funktionalität: Rauchtest Backend

  Szenario: Der Health-Endpunkt meldet «UP»
    Wenn ich «/q/health/live» abfrage
    Dann antwortet das Archiv mit Status 200 und «UP»
