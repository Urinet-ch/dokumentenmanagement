# language: de
@spec-03 @webseiten-isolation
Funktionalität: Isolationsmatrix der Webseiten-Erfassung (MandantenIsolationIT, Teil 2)
  Die Matrix ergänzt den bestehenden Tag-Verbund aus webseiten-lesen.feature, webseiten-datei.feature,
  webseiten-feed.feature und oeffentlicher-chat.feature. Deren Stufen-, Feed- und Chat-Gegenproben bleiben bestehen.
  Die Zugriffsstufen (Req 9.4) belegen diese Szenarien nicht; der Nachweis liegt in den genannten Lese-, Feed- und Chat-Features.
  Sie ersetzt weder die manuellen Staging-Nachweise noch die späteren Erweiterungen von Spec 04 und 05.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-9.2 @req-10.2
  Szenariogrundriss: Der echte Erfassungs-Upload begrenzt Datei und gesamte Multipart-Anfrage
    Angenommen der Erfassungs-Schlüssel «E» für die Webseite «matrix» im Mandanten «urinet» ist angelegt
    Und die Zahl der Dokumente im Mandanten «urinet» wird gemerkt
    Wenn der Schlüssel «E» eine synthetische Multipart-Datei mit <mib> MiB hochlädt
    Dann ist die letzte Antwort HTTP 413 mit «DATEI_ZU_GROSS»
    Und hat der Mandant «urinet» die Dokumentzahl um 0 erhöht
    Und gibt es in der Referenztabelle für «E» und Referenz «zu-gross» genau 0 Eintrag
    Beispiele:
      | mib |
      | 51  |
      | 61  |

  @req-1.4 @req-9.1 @req-9.2
  Szenariogrundriss: Beide Schlüsselarten sind an jedem automatisch ermittelten Endpunkt getrennt
    Angenommen der Erfassungs-Schlüssel «E» für die Webseite «matrix» im Mandanten «<mandant>» ist angelegt
    Und der Lese-Schlüssel «L» für die Webseite «matrix» im Mandanten «<mandant>» ist angelegt
    Und für den Schlüssel «E» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Dann trennen «L» und «E» beide Schlüsselarten über das vollständige Endpunktinventar
    Beispiele:
      | mandant |
      | urinet  |
      | vhf-gsk |

  @req-1.4 @req-9.1 @req-9.2
  Szenariogrundriss: Fremde und unbekannte IDs sind bei jeder Dokumentaktion ununterscheidbar
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «matrix» im Mandanten «<eigener>» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «matrix» im Mandanten «<fremder>» ist angelegt
    Und für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Und für den Schlüssel «B» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Dann sind alle Dokumentaktionen für «A» gegen fremde IDs von «B» wie gegen unbekannte IDs gesperrt
    Beispiele:
      | eigener | fremder |
      | urinet  | vhf-gsk |
      | vhf-gsk | urinet  |

  @req-1.4 @req-9.1
  Szenariogrundriss: Hilfslisten und Formulararten bleiben im Mandanten
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «matrix» im Mandanten «<eigener>» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «matrix» im Mandanten «<fremder>» ist angelegt
    Und für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Und für den Schlüssel «B» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Dann liefern Liste, Eingangskorb und Arten für «A» keine Daten von «B»
    Beispiele:
      | eigener | fremder |
      | urinet  | vhf-gsk |
      | vhf-gsk | urinet  |

  @req-1.4 @req-9.1
  Szenario: Dieselbe Referenz ist je Mandant und Webseite unabhängig belegt
    Angenommen der Erfassungs-Schlüssel «A1» für die Webseite «seite-1» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «A2» für die Webseite «seite-2» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «B1» für die Webseite «seite-1» im Mandanten «vhf-gsk» ist angelegt
    Und der Erfassungs-Schlüssel «B2» für die Webseite «seite-2» im Mandanten «vhf-gsk» ist angelegt
    Wenn der Schlüssel «A1» die Datei «a1.pdf» mit Referenz «gleiche-referenz» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «A2» die Datei «a2.pdf» mit Referenz «gleiche-referenz» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «B1» die Datei «b1.pdf» mit Referenz «gleiche-referenz» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «B2» die Datei «b2.pdf» mit Referenz «gleiche-referenz» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Und sind die Dokument-IDs der Referenz «gleiche-referenz» von «A1» und «A2» verschieden
    Und sind die Dokument-IDs der Referenz «gleiche-referenz» von «A1» und «B1» verschieden
    Und sind die Dokument-IDs der Referenz «gleiche-referenz» von «B1» und «B2» verschieden
    Wenn der Schlüssel «A1» den Status der Referenz «gleiche-referenz» abruft
    Dann weist die Statusantwort auf die eigene Referenz «gleiche-referenz» von «A1»
    Wenn der Schlüssel «A2» den Status der Referenz «gleiche-referenz» abruft
    Dann weist die Statusantwort auf die eigene Referenz «gleiche-referenz» von «A2»
    Wenn der Schlüssel «B1» den Status der Referenz «gleiche-referenz» abruft
    Dann weist die Statusantwort auf die eigene Referenz «gleiche-referenz» von «B1»
    Wenn der Schlüssel «B2» den Status der Referenz «gleiche-referenz» abruft
    Dann weist die Statusantwort auf die eigene Referenz «gleiche-referenz» von «B2»
    Wenn der Schlüssel «A1» eine andere Datei «konflikt.pdf» mit Referenz «gleiche-referenz» hochlädt
    Dann ist die letzte Antwort HTTP 409 mit «REFERENZ_VERGEBEN»
    Und enthält die letzte Antwort keine internen Details

  @req-1.4 @req-9.1
  Szenariogrundriss: Referenzen sind auf Status, Liste und Details nur der eigenen Webseite bekannt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «seite-1» im Mandanten «<mandant>» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «seite-2» im Mandanten «<mandant>» ist angelegt
    Wenn der Schlüssel «A» die Datei «referenz.pdf» mit Referenz «nur-seite-1» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «B» den Status der Referenz «nur-seite-1» abruft
    Dann ist die letzte Antwort HTTP 404 mit «NICHT_GEFUNDEN»
    Und nennt das Dokument der Referenz «nur-seite-1» von «A» für «B» keine fremde Referenz
    Beispiele:
      | mandant |
      | urinet  |
      | vhf-gsk |

  @req-1.4 @req-9.1
  Szenario: Gleiche Bearbeiterkennungen sind je Mandant und Webseite getrennt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «matrix» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «matrix» im Mandanten «vhf-gsk» ist angelegt
    Und der Erfassungs-Schlüssel «C» für die Webseite «andere» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» den Anzeigenamen des Bearbeiters «wp:1» auf «Person A» ändert
    Und der Schlüssel «B» den Anzeigenamen des Bearbeiters «wp:1» auf «Person B» ändert
    Dann das Bearbeiterverzeichnis enthält «Person A» für «A»
    Und das Bearbeiterverzeichnis enthält «Person B» für «B»
    Wenn der Schlüssel «A» den Anzeigenamen des Bearbeiters «wp:17» auf «Nur A» ändert
    Und der Schlüssel «B» die Datei «b-unbekannt.pdf» mit Referenz «unbekannt» und der Bearbeiterkennung «wp:17» sendet
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «C» die Datei «c-unbekannt.pdf» mit Referenz «unbekannt» und der Bearbeiterkennung «wp:17» sendet
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «B» den Anzeigenamen des Bearbeiters «wp:17» auf «Eigenes B» ändert
    Und der Schlüssel «B» die Datei «b-gemeldet.pdf» mit Referenz «gemeldet» und der Bearbeiterkennung «wp:17» sendet
    Dann ist die letzte Antwort HTTP 202
