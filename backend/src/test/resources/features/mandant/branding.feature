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

  @req-2.2
  Szenario: Ungültige Domain ablehnen
    Wenn das öffentliche Branding für die Domain "https://fremd.test.invalid/pfad" angefragt wird
    Dann antwortet das Archiv mit Status 400

  @req-2.2
  Szenario: Fehlende, überlange und IP-Domain ablehnen
    Wenn das öffentliche Branding ohne Domain angefragt wird
    Dann antwortet das Archiv mit Status 400
    Wenn das öffentliche Branding für die Domain "127.0.0.1" angefragt wird
    Dann antwortet das Archiv mit Status 400
    Wenn das öffentliche Branding für eine Domain mit 254 Zeichen angefragt wird
    Dann antwortet das Archiv mit Status 400

  @req-2.1
  Szenario: Unsichere Konfiguration wird neutralisiert
    Wenn das öffentliche Branding für die Domain "unsicher.test.invalid" angefragt wird
    Dann antwortet das Archiv mit Status 200
    Und liefert das Archiv das neutrale Branding ohne Logo
    Und die Antwort enthält weder "script" noch "javascript" noch "fremd.example"

  @req-2.2
  Szenario: Die Domain wählt keinen Datenmandanten
    Wenn das öffentliche Branding für die Domain "vhf-gsk.test.invalid" angefragt wird
    Dann antwortet das Archiv mit Status 200
    Wenn Daten mit der Domain "vhf-gsk.test.invalid" ohne Token angefragt werden
    Dann antwortet das Archiv mit Status 401

  @req-2.2
  Szenario: Bekannte und unbekannte Domains haben dieselbe Antwortform
    Wenn das öffentliche Branding für die Domain "urinet.test.invalid" angefragt wird
    Dann die Feldmenge der Antwort ist gespeichert
    Wenn das öffentliche Branding für die Domain "fremd.test.invalid" angefragt wird
    Dann hat die Antwort dieselbe Feldmenge und denselben Cache-Control-Header wie die bekannte Domain

  @req-2.2
  Szenario: Domain mit abschliessendem Punkt wird wie ohne Punkt beantwortet
    Wenn das öffentliche Branding für die Domain "urinet.test.invalid." angefragt wird
    Dann antwortet das Archiv mit Status 200
    Und liefert das Archiv das Branding "UriNet Archiv" mit Farbe "#eeb111"

