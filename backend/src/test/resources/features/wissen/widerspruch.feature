# language: de
@spec-04 @wissen-widerspruch
Funktionalität: Widersprüche bleiben bis zur begründeten Entscheidung sichtbar

  @req-7.1 @req-7.2 @req-7.3 @req-7.4 @req-7.5
  Szenario: Beide Quellen bleiben erhalten und nur der begründet aufgelöste Block entfällt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine Wissensseite enthält eine alte belegte Aussage und eine unabhängige Aussage
    Wenn der Inhaber eine widersprechende neue Quelle als Widerspruchsblock freigibt
    Dann bleiben beide Quellen mit ihren Daten und dem Hinweis «Widerspruch offen» sichtbar
    Wenn der Inhaber den Block ohne Grund auflösen möchte
    Dann wird die Auflösung mit 422 «PFLICHTFELD_FEHLT» abgelehnt
    Wenn der Inhaber mit Grund zugleich die unabhängige Aussage entfernt
    Dann bleibt die Annahme durch «AUSSAGE_ENTFERNT» gesperrt
    Wenn der Inhaber nur den Widerspruch mit Grund auflöst
    Dann sind der Grund und «Widerspruch aufgelöst» protokolliert und die alte Version bleibt erhalten
