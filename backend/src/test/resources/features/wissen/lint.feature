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

  @req-1.4 @req-10.3 @req-10.4 @req-10.5 @req-13.7 @req-13.9
  Szenario: Ein gemischtes Paar erzeugt nur für die höhere Seite einen Entwurf
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und zwei verknüpfbare Lint-Seiten mit Stufen NORMAL und INTERN und eine PERSONAL-Hülle bestehen
    Und WireMock erkennt den Lint-Widerspruch und liefert Seitenänderungen
    Wenn der Inhaber den Lint für das Seitenpaar startet
    Dann der Lint verarbeitet die Teilaufträge mit höchstens einem KI-Aufruf je Auftrag
    Und nur die interne Seite erhält einen Lint-Entwurf und PERSONAL erreicht die KI nicht
    Und die Originale, Arbeitsfassung, PDF-A, Volltext und Metadaten bleiben unverändert
    Und Bericht und Protokolle enthalten keine Seiten- oder Dokumentinhalte

  @req-10.2
  Szenario: Der Lint zählt die Befundarten ohne KI (Links, veraltet ab, Quelle prüfen, Querverweise, Quellseiten, Formfehler)
    Angenommen Lint-Seiten mit fehlendem Link, abgelaufenem «veraltet ab», «Quelle prüfen», fehlendem Querverweis, einer Quellseite ohne berührte Seiten und einer Aussage ohne Beleg bestehen
    Wenn der Lint höchstens zwei Seitenpaare auswählt
    Dann zählt der Lint je Befundart mindestens einen Befund ohne KI

  @req-10.3
  Szenario: Die am längsten nicht geprüften Paare kommen zuerst
    Angenommen drei verwandte Lint-Seiten sind verschieden lange oder noch nie geprüft
    Wenn der Lint höchstens zwei Seitenpaare auswählt
    Dann kommen das ungeprüfte und danach das am längsten nicht geprüfte Paar

  @req-10.3
  Szenario: Ein neueres Dokument bestimmt einen Veraltet-Kandidaten
    Angenommen eine neuere Wissensquelle verweist auf eine Seite mit älterem Quelldokument
    Wenn der Lint höchstens zwei Seitenpaare auswählt
    Dann erkennt der Lint die ältere Aussage als Veraltet-Kandidaten für die neuere Quelle

  @req-10.4 @req-13.7
  Szenario: Eine nachträglich persönliche Quelle verhindert den nächsten Lint-KI-Aufruf
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und zwei verknüpfbare Lint-Seiten mit Stufen NORMAL und INTERN und eine PERSONAL-Hülle bestehen
    Und WireMock erkennt den Lint-Widerspruch und liefert Seitenänderungen
    Wenn der Inhaber den Lint für das Seitenpaar startet
    Und nach dem Paarauftrag wird die normale Quelle PERSONAL
    Dann der Lint verarbeitet die Teilaufträge mit höchstens einem KI-Aufruf je Auftrag
    Und nach dem PERSONAL-Wechsel erfolgt kein weiterer KI-Aufruf und keine Änderung

  @req-13.8
  Szenario: Nach zwei ungültigen Paarantworten fehlt die Änderung mit Hinweis
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und zwei verknüpfbare Lint-Seiten mit Stufen NORMAL und INTERN und eine PERSONAL-Hülle bestehen
    Und die Lint-KI liefert zweimal eine ungültige Paarantwort
    Wenn der Inhaber den Lint für das Seitenpaar startet
    Dann der Lint verarbeitet die Teilaufträge mit höchstens einem KI-Aufruf je Auftrag
    Und der Lint fragt einmal nach und beendet ohne Änderung mit Hinweis

  @req-13.5 @req-13.6 @req-13.7
  Szenario: Das Laufbudget stoppt weitere Änderungen und bewahrt den bisherigen Entwurf
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und zwei verknüpfbare Lint-Seiten mit Stufe NORMAL bestehen
    Und das Lint-Laufbudget erlaubt zwei KI-Aufrufe und danach keinen weiteren
    Wenn der Inhaber den Lint für das Seitenpaar startet
    Dann der Lint verarbeitet die Teilaufträge mit höchstens einem KI-Aufruf je Auftrag
    Und das erreichte Lint-Budget hinterlässt die bisherige Änderung als unvollständigen Entwurf
