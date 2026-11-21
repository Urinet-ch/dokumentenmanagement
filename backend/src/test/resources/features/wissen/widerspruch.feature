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

  @req-7.3
  Szenario: Ein gespeicherter Altdiff erhält die Auflösungsmarkierung aus seiner historischen Basis
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine Wissensseite enthält eine alte belegte Aussage und eine unabhängige Aussage
    Wenn der Inhaber eine widersprechende neue Quelle als Widerspruchsblock freigibt
    Und ein vor dem Widerspruchsschutz gespeicherter Entwurf nach einer weiteren Freigabe gelesen wird
    Dann enthält dessen Diff den entfernten Widerspruch ohne die gespeicherten Entwurfsdaten zu ändern

  @req-7.2 @req-7.3
  Szenario: Eine normale Aussage teilt ihren Quellenschlüssel mit einem Widerspruch
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine Wissensseite enthält eine alte belegte Aussage und eine unabhängige Aussage
    Wenn der Inhaber einen Widerspruch und eine weitere Aussage mit demselben Quellenschlüssel freigibt
    Und der Inhaber beides mit einem Grund für den Widerspruch entfernen möchte
    Dann bleibt die Annahme durch «AUSSAGE_ENTFERNT» gesperrt
    Wenn er die weitere Aussage zusätzlich ausdrücklich begründet entfernt
    Dann wird die Änderung angenommen

  @req-7.3 @req-12.1
  Szenario: Rollen ohne A17 dürfen einen Widerspruch nicht auflösen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine Wissensseite enthält eine alte belegte Aussage und eine unabhängige Aussage
    Wenn der Inhaber eine widersprechende neue Quelle als Widerspruchsblock freigibt
    Und der Inhaber einen Entwurf zur Auflösung vorbereitet
    Dann erhalten Mitarbeiter und Treuhänder beim Annehmen mit Grund 403 und die Seite bleibt unverändert
