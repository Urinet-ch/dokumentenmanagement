# language: de
@spec-04 @lint
Funktionalität: Lint der Wissensbasis starten
  @req-10.1 @req-14.3
  Szenario: Der Inhaber startet einen protokollierten Lint-Auftrag
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Wenn «inhaber-urinet» den Lint startet
    Dann antwortet der Lint-Endpunkt mit Status 202
    Und der Lint ist im Mandanten «urinet» eingereiht und protokolliert

  @req-10.1
  Szenario: Ein Treuhänder ohne Recht A17 darf keinen Lint starten
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «treuhaender-urinet» den Lint startet
    Dann antwortet der Lint-Endpunkt mit Status 403

  @req-10.2 @req-10.5 @req-10.6
  Szenario: Der Worker meldet eine verwaiste Seite ohne sie zu ändern
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und die Wissensbasis enthält eine Seite «konzepte/lintprobe» mit Stufe «NORMAL» und Marker «LINTPROBE»
    Und WireMock ist zurückgesetzt
    Wenn «inhaber-urinet» den Lint startet
    Dann antwortet der Lint-Endpunkt mit Status 202
    Und der Lint meldet die verwaiste Seite und lässt ihre Version unverändert
    Und es erfolgt kein KI-Aufruf

  @req-13.6
  Szenario: Ein manueller Start bei erschöpftem Monatsbudget wird abgelehnt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 1000000 Tokens
    Und das Monatsbudget der Wissensbasis ist ausgeschöpft
    Und WireMock ist zurückgesetzt
    Wenn «inhaber-urinet» den Lint startet
    Dann antwortet der Lint-Endpunkt mit Status 429 und Fehlercode «BUDGET»
    Und es erfolgt kein KI-Aufruf

  @req-10.6 @req-13.6
  Szenario: Ein geplanter Lint entfällt bei erschöpftem Monatsbudget mit Logeintrag
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 1000000 Tokens
    Und das Monatsbudget der Wissensbasis ist ausgeschöpft
    Und WireMock ist zurückgesetzt
    Wenn der geplante Lint für «urinet» fällig wird
    Dann ist kein Lint-Auftrag eingereiht und «BUDGET_ERSCHOEPFT» im Lint-Log gezählt
    Und es erfolgt kein KI-Aufruf
