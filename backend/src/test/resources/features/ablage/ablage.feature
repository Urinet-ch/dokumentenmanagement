# language: de
@spec-01
Funktionalität: Ablage im Object Storage
  Der S3-Adapter legt Dateien gegen MinIO aus den Compose Dev Services ab (Höchstfrist 1 Tag wie auf Stufe A).
  Status, Versions-IDs in dokument_datei und Benachrichtigung folgen mit dem Handler ARCHIVIEREN (Aufgabe 2.6),
  das Protokoll des Downloads mit dem Endpunkt (Aufgabe 2.5).

  Grundlage:
    Angenommen ein Mandanten-Bucket mit Object Lock und Versionierung

  @req-6.2
  Szenario: Dateien eines nicht archivierten Dokuments liegen ungesperrt unter eingang/
    Wenn die Anwendung ein Original im Eingang ablegt
    Dann liegt das Original unter «eingang/» mit Versions-ID und SHA-256
    Und die Datei im Eingang ist nicht gesperrt
    Und die Anwendung kann die Datei im Eingang löschen

  @req-6.3
  Szenario: Archivieren kopiert, prüft, sperrt und löscht erst nach dem Festhalten den Eingang
    Angenommen ein Dokument mit Original, Arbeitsfassung und PDF/A im Eingang
    Wenn die Anwendung das Dokument einer Art mit gesetzlicher Pflicht bis «31.12.2036» archiviert
    Dann liegen 3 Dateien unter «archiv/» mit derselben SHA-256 wie im Eingang
    Und jede archivierte Datei hat eine Versions-ID und ist in GOVERNANCE gesperrt
    Und beim Festhalten lagen die Dateien noch im Eingang
    Und im Eingang des Dokuments liegt keine Version mehr

  @req-6.4
  Szenario: Eine abweichende Prüfsumme bricht die Archivierung ab
    Angenommen ein Dokument mit Original im Eingang
    Wenn die Anwendung das Dokument mit einer falschen Prüfsumme archiviert
    Dann bricht die Archivierung mit «PRUEFSUMME» ab
    Und unter «archiv/» liegt keine Datei
    Und das Original liegt unverändert im Eingang

  @req-6.9
  Szenario: Ein unbefristetes Dokument einer Art mit gesetzlicher Pflicht wird 10 Jahre gesperrt
    Angenommen ein Dokument mit Original im Eingang
    Wenn die Anwendung das Dokument einer Art mit gesetzlicher Pflicht ohne Frist archiviert
    Dann hat jede archivierte Datei eine Versions-ID und ist in GOVERNANCE gesperrt
    Und ohne Höchstfrist endet die Sperre eines am «10.11.2026» archivierten unbefristeten Dokuments am «10.11.2036»

  @req-6.12
  Szenario: Eine Art ohne gesetzliche Pflicht bleibt unter archiv/ ohne Sperre
    Angenommen ein Dokument mit Original im Eingang
    Wenn die Anwendung das Dokument einer Art ohne gesetzliche Pflicht archiviert
    Dann liegen 1 Dateien unter «archiv/» mit derselben SHA-256 wie im Eingang
    Und keine archivierte Datei ist gesperrt

  @req-14.6
  Szenario: Die Download-Adresse gilt nur für die gespeicherte Versions-ID und höchstens 5 Minuten
    Angenommen ein Dokument mit Original, Arbeitsfassung und PDF/A im Eingang
    Und die Anwendung das Dokument einer Art mit gesetzlicher Pflicht bis «31.12.2036» archiviert
    Und unter dem Schlüssel des PDF/A liegt eine neuere Version
    Wenn die Anwendung die Download-Adresse für die gespeicherte Versions-ID des PDF/A ausgibt
    Dann liefert die Adresse den Inhalt der gespeicherten Version
    Und die Adresse ist höchstens 5 Minuten gültig
