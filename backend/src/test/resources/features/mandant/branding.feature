# language: de
@spec-03
Funktionalität: Mandanten-Branding
  Das öffentliche Branding ist nur anhand einer explizit konfigurierten Domain abrufbar.

  @req-2.1
  Szenario: UriNet-Branding ohne Anmeldung laden
    Wenn das öffentliche Branding für die Domain "urinet.test.invalid" angefragt wird
    Dann antwortet das Archiv mit Status 200
    Und liefert das Archiv das Branding "UriNet Archiv" mit Farbe "#eeb111"

  @req-2.2
  Szenario: VHF/GSK-Branding getrennt von UriNet laden
    Wenn das öffentliche Branding für die Domain "vhf-gsk.test.invalid" angefragt wird
    Dann antwortet das Archiv mit Status 200
    Und liefert das Archiv das Branding "VHF/GSK Archiv" mit Farbe "#eeb111"

  @req-2.2
  Szenario: Unbekannte Domains verraten keine Mandanten
    Wenn das öffentliche Branding für die Domain "fremd.test.invalid" angefragt wird
    Dann die neutrale Antwort ist gespeichert
    Wenn das öffentliche Branding für die Domain "andere.test.invalid" angefragt wird
    Dann antwortet das Archiv mit Status 200
    Und antwortet das Archiv mit derselben neutralen Antwort

  @req-2.3
  Szenario: Ungültige Domain ablehnen
    Wenn das öffentliche Branding für die Domain "https://fremd.test.invalid/pfad" angefragt wird
    Dann antwortet das Archiv mit Status 400
