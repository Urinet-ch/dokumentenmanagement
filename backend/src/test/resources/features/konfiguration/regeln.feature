# language: de
@spec-05
Funktionalität: Regeln je Mandant
  Regeln steuern Pflichtfelder und Lückenberichte, ohne bestehende Dokumente rückwirkend zu ändern.

  @req-5.1
  Szenario: Die vier erlaubten Regeltypen werden typisiert
    Wenn die vier Regeltypen der Konfiguration aufgebaut werden
    Dann sind alle vier Regeltypen typisiert

  @req-5.2
  Szenario: Eine verletzte Regel mit Schwere FEHLER blockiert, eine Warnung nicht
    Wenn eine aktive Pflichtregel mit Schwere «FEHLER» und fehlendem Datum geprüft wird
    Dann wird der Vorgang mit «REGEL_VERLETZT» abgelehnt
    Wenn eine aktive Pflichtregel mit Schwere «WARNUNG» und fehlendem Datum geprüft wird
    Dann enthält die Antwort die Warnung «datum_pflicht»

  @req-5.3
  Szenario: Eine bestehende Verletzung wird bei unveränderten Metadaten zur Warnung
    Wenn eine bereits verletzte Pflichtregel mit unveränderten Metadaten geprüft wird
    Dann enthält die Antwort die Warnung «datum_pflicht»
    Und wird der Vorgang nicht abgelehnt

  @req-5.4
  Szenario: Der Lückenbericht bleibt Rollen mit uneingeschränkter Sicht vorbehalten
    Dann wird «inhaber-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «treuhaender-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «geschaeftsstelle-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «mitarbeiter-urinet» das Werkzeug vollstaendigkeit nicht angeboten
    Wenn «mitarbeiter-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann ist die Antwort der Fehler «Werkzeug nicht verfügbar»

  @req-5.5
  Szenario: Eine Regel darf kein in der Dokumentart fehlendes Feld verwenden
    Wenn eine Regel ein nicht vorhandenes Zusatzfeld referenziert
    Dann wird die Test-Konfiguration mit Befund «REGEL_FELD_UNGUELTIG» abgelehnt

  @req-5.6
  Szenario: Der Lückenbericht nennt die Zahl der bestätigten Dokumente betroffener Arten
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» sind die Seed-Regeln für den Lückenbericht eingerichtet
    Und im Mandanten «urinet» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «2003-001, 2003-002, 2003-004»
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann nennt der Datenstand die Zahl der bestätigten Belege der Arten «AUSGANGSRECHNUNG» im Mandanten «urinet»
