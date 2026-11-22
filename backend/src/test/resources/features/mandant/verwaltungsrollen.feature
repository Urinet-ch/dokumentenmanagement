# language: de
@spec-03
Funktionalität: Verwaltungsrollen bei der Mandantenanlage

  @req-1.1
  Szenario: Die Provisionierung übernimmt die konfigurierten Verwaltungsrollen
    Wenn der Betreiber den Mandanten «vhf-gsk» über das Kommando anlegt
    Dann enthält der Registereintrag für «vhf-gsk» die Verwaltungsrolle «geschaeftsstelle»
