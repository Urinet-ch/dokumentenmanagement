# language: de
@spec-01
Funktionalität: Datei eines Dokuments herunterladen (Aufgabe 4.5)
  «GET /api/v1/dokumente/{id}/datei» leitet auf eine vorsignierte Adresse für genau die gespeicherte Versions-ID um
  (höchstens 5 Minuten gültig) und protokolliert den Zugriff als HERUNTERLADEN. Standard ist die PDF/A-Fassung;
  Original und Arbeitsfassung gibt es mit «variante» und «nr». Wer das Dokument nicht sehen darf, erhält 404 wie beim
  Direktzugriff (Req 1.4, 2.3). Verlangt die Anfrage ausdrücklich JSON (PWA), kommt die Adresse als JSON statt als
  Umleitung. Die Dateien liegen synthetisch in MinIO.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Dokumente mit Dateien:
      | name        | vertraulichkeit | bestaetigt | erfasser       | person | status       | dateien                      |
      | D_NORMAL    | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT   | pdfa,original,arbeitsfassung |
      | D_INTERN    | INTERN          | ja         | inhaber-urinet |        | ARCHIVIERT   | pdfa                         |
      | D_OHNE_PDFA | NORMAL          | ja         | inhaber-urinet |        | EINGANGSKORB | original                     |
      | D_VERWORFEN | NORMAL          | ja         | inhaber-urinet |        | VERWORFEN    | pdfa,original                |
    Und im Mandanten «vhf-gsk» liegen diese Dokumente mit Dateien:
      | name     | vertraulichkeit | bestaetigt | erfasser                 | person | status     | dateien |
      | V_NORMAL | NORMAL          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT | pdfa    |

  @req-14.6
  Szenario: Öffnen liefert eine kurz gültige Adresse auf die gespeicherte Version der PDF/A-Fassung und wird protokolliert
    Angenommen unter dem Schlüssel des PDF/A von «D_NORMAL» liegt inzwischen eine neuere Version
    Wenn «mitarbeiter-urinet» die Datei von «D_NORMAL» öffnet
    Dann leitet das Archiv auf eine höchstens 5 Minuten gültige Adresse um, die nicht zwischengespeichert wird
    Und die Adresse liefert «PDF/A D_NORMAL»
    Und das Zugriffsprotokoll von «urinet» enthält HERUNTERLADEN von «mitarbeiter-urinet» für «D_NORMAL»

  @req-14.6
  Szenario: Original und Arbeitsfassung lassen sich ebenfalls herunterladen
    Wenn «inhaber-urinet» die Datei von «D_NORMAL» mit «variante=original&nr=1» öffnet
    Dann leitet das Archiv auf eine höchstens 5 Minuten gültige Adresse um, die nicht zwischengespeichert wird
    Und die Adresse liefert «Original D_NORMAL»
    Wenn «treuhaender-urinet» die Datei von «D_NORMAL» mit «variante=arbeitsfassung» öffnet
    Dann leitet das Archiv auf eine höchstens 5 Minuten gültige Adresse um, die nicht zwischengespeichert wird
    Und die Adresse liefert «Arbeitsfassung D_NORMAL»

  @req-14.6
  Szenario: Mit «Accept: application/json» kommt dieselbe Adresse als JSON statt als Umleitung (PWA, Aufgabe 4.6)
    Wenn «treuhaender-urinet» die Download-Adresse von «D_NORMAL» als JSON anfragt
    Dann liefert das Archiv die höchstens 5 Minuten gültige Adresse als JSON, die nicht zwischengespeichert wird
    Und die Adresse liefert «PDF/A D_NORMAL»
    Und das Zugriffsprotokoll von «urinet» enthält HERUNTERLADEN von «treuhaender-urinet» für «D_NORMAL»
    Wenn «mitarbeiter-urinet» die Download-Adresse von «D_INTERN» als JSON anfragt
    Dann antwortet das Archiv mit 404 «NICHT_GEFUNDEN» ohne Adresse
    Und das Zugriffsprotokoll von «urinet» enthält kein HERUNTERLADEN von «mitarbeiter-urinet» für «D_INTERN»

  @req-14.6 @req-14.5
  Szenario: Unsichtbare, fremde, verworfene und fehlende Dateien ergeben 404, falsche Angaben 400
    Dann antwortet der Download so:
      | benutzer                 | dokument    | anfrage                   | status | fehlercode        | grund                          |
      | inhaber-urinet           | D_INTERN    |                           | 302    |                   | A3                             |
      | treuhaender-urinet       | D_INTERN    |                           | 302    |                   | A3                             |
      | mitarbeiter-urinet       | D_INTERN    |                           | 404    | NICHT_GEFUNDEN    | INTERN nicht für mitarbeiter   |
      | inhaber-urinet           | V_NORMAL    |                           | 404    | NICHT_GEFUNDEN    | fremder Mandant                |
      | geschaeftsstelle-vhf-gsk | V_NORMAL    |                           | 302    |                   | eigener Mandant                |
      | inhaber-urinet           | D_OHNE_PDFA |                           | 404    | NICHT_GEFUNDEN    | noch kein PDF/A                |
      | inhaber-urinet           | D_OHNE_PDFA | variante=original         | 302    |                   | Original vorhanden             |
      | inhaber-urinet           | D_OHNE_PDFA | variante=arbeitsfassung   | 404    | NICHT_GEFUNDEN    | keine Arbeitsfassung           |
      | inhaber-urinet           | D_NORMAL    | variante=original&nr=2    | 404    | NICHT_GEFUNDEN    | keine zweite Datei             |
      | inhaber-urinet           | D_VERWORFEN |                           | 404    | NICHT_GEFUNDEN    | verworfen                      |
      | inhaber-urinet           | D_VERWORFEN | variante=original         | 404    | NICHT_GEFUNDEN    | verworfen                      |
      | inhaber-urinet           | UNBEKANNT   |                           | 404    | NICHT_GEFUNDEN    | gibt es nicht                  |
      | inhaber-urinet           | D_NORMAL    | variante=vorschau         | 400    | ANFRAGE_UNGUELTIG | unbekannte Variante            |
      | inhaber-urinet           | D_NORMAL    | variante=pdfa&nr=1        | 400    | ANFRAGE_UNGUELTIG | PDF/A hat keine Nummer         |
      | inhaber-urinet           | D_NORMAL    | variante=original&nr=0    | 400    | ANFRAGE_UNGUELTIG | Nummer ab 1                    |
      | inhaber-urinet           | D_NORMAL    | variante=original&nr=eins | 400    | ANFRAGE_UNGUELTIG | keine Zahl                     |
    Und das Zugriffsprotokoll von «urinet» enthält kein HERUNTERLADEN von «mitarbeiter-urinet» für «D_INTERN»
    Und das Zugriffsprotokoll von «urinet» enthält kein HERUNTERLADEN von «inhaber-urinet» für «D_VERWORFEN»
