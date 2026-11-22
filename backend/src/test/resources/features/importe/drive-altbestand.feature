# language: de
@spec-03
Funktionalität: Altbestand von VHF/GSK übernehmen
  Der Import verwendet ausschliesslich synthetische Dateien und einen Token des Mandanten «vhf-gsk».

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-4.1
  Szenario: Reihe und Jahr aus einem synthetischen Altbestand übernehmen
    Angenommen die synthetische Dokumentart «BULLETIN» ist im Mandanten «vhf-gsk» vorbereitet
    Wenn «inhaber-vhf-gsk» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-vhf-gsk» ordnet die Klassifikation «fachgebiet» der Dokumentart «BULLETIN» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen ein Ordner «Altbestand» mit diesen synthetischen Dateien:
      | pfad                 | typ |
      | Bulletin/2026/1.pdf | pdf |
      | Bulletin/2026/3.pdf | pdf |
    Und ein Token vom Scope «import» für «vhf-gsk» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Altbestand «Altbestand» mit Klassifikation «fachgebiet» und Wert «bau» lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und hat das Archiv im Mandanten «vhf-gsk» diese Dateien mit Quelle «DRIVE» vom Import-Token angelegt:
      | pfad                | hinweis                                                                                 |
      | Bulletin/2026/1.pdf | Drive-Import; Ordner: Altbestand/Bulletin/2026; Reihe: BULLETIN; Jahr: 2026; Ausgabe: 1 |
      | Bulletin/2026/3.pdf | Drive-Import; Ordner: Altbestand/Bulletin/2026; Reihe: BULLETIN; Jahr: 2026; Ausgabe: 3 |
    Und hat jedes importierte Dokument die Klassifikation «fachgebiet» mit dem Wert «bau»
    Und hat jedes importierte Dokument den Art-Vorschlag «BULLETIN»
    Und enthält der Altbestandsbericht «BULLETIN / 2026: 2 Dateien; Ausgabe-Nr. erkannt: [1, 3]; nicht erkannt: 0»
