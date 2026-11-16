# language: de
@spec-03
Funktionalität: VHF/GSK-Dokumentarten
  Der echte Seed enthält nur Verbandsdokumente; der Test-Seed für die mandantenübergreifenden Tests bleibt bestehen.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-1.3
  Szenario: Im Mandanten vhf-gsk sind Finanzwerkzeuge nicht verfügbar
    Dann bietet das Archiv «inhaber-vhf-gsk» diese Werkzeuge an: «dokumente_suchen,dokument_zeigen,fristen,vollstaendigkeit»
    Und antwortet kennzahl und offene_posten für «inhaber-vhf-gsk» mit «{"fehler":"Werkzeug nicht verfügbar"}»
