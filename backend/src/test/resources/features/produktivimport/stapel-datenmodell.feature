# language: de
@spec-09
Funktionalität: Datenmodell für Stapel
  Stapel und Seiten liegen je Mandant im eigenen Schema; Dokumente behalten den Stapel und ihren Seitenbereich.

  @req-5.1
  Szenario: Stapelprotokolle und Seiten bleiben mandantengetrennt
    Angenommen die Testmandanten für den Stapel bestehen
    Wenn ich Stapelprotokolle und Seiten für beide Mandanten speichere
    Dann sind die Protokolle und Seiten nur im jeweiligen Mandantenschema vorhanden

  @req-5.2
  Szenario: Dokumente verweisen auf Stapel und Seitenbereich
    Angenommen die Testmandanten für den Stapel bestehen
    Wenn ich Dokumente mit Stapel und Seitenbereich für beide Mandanten speichere
    Dann verweisen Dokumente und Stapelseiten auf den jeweiligen Stapel und Seitenbereich
