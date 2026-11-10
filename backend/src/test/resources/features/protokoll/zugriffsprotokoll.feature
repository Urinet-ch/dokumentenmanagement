# language: de
@spec-01
Funktionalität: Zugriffsprotokoll (Grundlage aus Aufgabe 1.6)
  Bis es fachliche Endpunkte gibt (2.x), prüfen Probe-Endpunkte unter src/test die Grundlage:
  «GET /api/v1/probe/dokumente/{id}» ist mit «@Protokolliert(ANZEIGEN)» erklärt, «PATCH /api/v1/probe/dokumente/{id}»
  (legt als Wirkung eine Dokumentart an) mit «@Protokolliert(AENDERN)». Die Nachweise je fachlicher Aktion folgen mit den Endpunkten ab Welle 2.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Stand des Zugriffsprotokolls von «urinet» ist gemerkt

  @req-10.2
  # Teilnachweis: Anzeigen und Ändern über Probe-Endpunkte; Erfassen, Herunterladen, Bestätigen usw. folgen mit 2.x.
  Szenario: Das Anzeigen eines Dokuments wird mit Benutzer-ID, Rolle, Aktion, Dokument und IP protokolliert (Teilnachweis)
    Wenn «treuhaender-urinet» das Probe-Dokument «NORMAL» über die IP «203.0.113.7» liest
    Dann antwortet das Archiv mit Status 200 und liefert das Probe-Dokument «NORMAL»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer           | rolle       | aktion   | dokument | ip          | grund | fehlercode |
      | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   | 203.0.113.7 |       |            |

  @req-10.2
  Szenario: Eine ungültige IP im Header X-Real-Ip wird nicht übernommen
    Wenn «inhaber-urinet» das Probe-Dokument «NORMAL» über die IP «203.0.113.7, 198.51.100.1» liest
    Dann antwortet das Archiv mit Status 200 und liefert das Probe-Dokument «NORMAL»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer       | rolle   | aktion   | dokument | ip | grund | fehlercode |
      | inhaber-urinet | INHABER | ANZEIGEN | NORMAL   |    |       |            |

  @req-10.3
  Szenario: Einträge im Zugriffsprotokoll lassen sich weder ändern noch löschen
    Wenn «inhaber-urinet» das Probe-Dokument «NORMAL» über die IP «203.0.113.7» liest
    Dann scheitert im Schema von «urinet» jeder Versuch, das Zugriffsprotokoll zu ändern, zu löschen oder zu leeren
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer       | rolle   | aktion   | dokument | ip          | grund | fehlercode |
      | inhaber-urinet | INHABER | ANZEIGEN | NORMAL   | 203.0.113.7 |       |            |

  @req-10.7
  Szenariogrundriss: Jede Verweigerung wird mit Fehlercode protokolliert, obwohl die Anfrage abbricht
    Wenn «<benutzer>» <anfrage>
    Dann antwortet das Archiv mit Status <status> und Fehlercode «<fehlercode>»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument   | ip | grund   | fehlercode   |
      | <benutzer> | <rolle> | VERWEIGERT | <dokument> |    | <grund> | <fehlercode> |

    Beispiele:
      | benutzer           | anfrage                                      | status | fehlercode     | rolle       | dokument | grund | fall                      |
      | mitarbeiter-urinet | das Probe-Dokument «NORMAL» bestätigt        | 403    | NICHT_ERLAUBT  | MITARBEITER | NORMAL   | A7    | sichtbar, aber ohne Recht |
      | mitarbeiter-urinet | das Probe-Dokument «INTERN» bestätigt        | 404    | NICHT_GEFUNDEN | MITARBEITER | INTERN   | A7    | nicht sichtbar            |
      | treuhaender-urinet | ein Dokument erfasst                         | 403    | NICHT_ERLAUBT  | TREUHAENDER |          | A1    | Rolle nach @RolesAllowed  |
      | ohne-rolle-urinet  | die Dokumentart «PROBE_VHF_BULLETIN» abfragt | 403    | NICHT_ERLAUBT  |             |          |       | ohne Archiv-Rolle         |

  @req-10.7
  Szenario: Die Dokument-ID eines anderen Mandanten wird im eigenen Mandanten als VERWEIGERT protokolliert
    Angenommen der Stand des Zugriffsprotokolls von «vhf-gsk» ist gemerkt
    Wenn «inhaber-vhf-gsk» das Probe-Dokument «NORMAL» liest
    Dann antwortet das Archiv mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und das Zugriffsprotokoll von «vhf-gsk» hat genau einen neuen Eintrag:
      | benutzer        | rolle   | aktion     | dokument | ip | grund | fehlercode     |
      | inhaber-vhf-gsk | INHABER | VERWEIGERT | NORMAL   |    | A2    | NICHT_GEFUNDEN |
    Und das Zugriffsprotokoll von «urinet» hat keinen neuen Eintrag

  @req-10.7
  Szenario: Ohne gültigen Mandanten gibt es kein Mandanten-Schema und keinen Eintrag
    Wenn «unbekannt-mandant» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 403 und Fehlercode «MANDANT_FEHLT»
    Und das Zugriffsprotokoll von «urinet» hat keinen neuen Eintrag

  @req-10.8
  Szenario: Lässt sich das Protokoll nicht schreiben, wird die Aktion zurückgerollt und das Archiv antwortet mit 500
    Angenommen im Schema von «urinet» scheitert jeder neue Eintrag ins Zugriffsprotokoll beim Festschreiben
    Wenn «inhaber-urinet» das Probe-Dokument «NORMAL» ändert und dabei die Dokumentart «PROBE_PROTOKOLL_FEHLER» anlegt
    Dann antwortet das Archiv mit Status 500 und Fehlercode «INTERNER_FEHLER»
    Und im Schema von «urinet» gibt es die Dokumentart «PROBE_PROTOKOLL_FEHLER» nicht
    Und das Zugriffsprotokoll von «urinet» hat keinen neuen Eintrag

  @req-10.8
  Szenario: Mit schreibbarem Protokoll sind die Aktion und ihr Eintrag festgeschrieben
    Wenn «inhaber-urinet» das Probe-Dokument «NORMAL» ändert und dabei die Dokumentart «PROBE_PROTOKOLL_OK» anlegt
    Dann antwortet das Archiv mit Status 204 und liefert keine Daten
    Und im Schema von «urinet» gibt es die Dokumentart «PROBE_PROTOKOLL_OK»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer       | rolle   | aktion  | dokument | ip | grund | fehlercode |
      | inhaber-urinet | INHABER | AENDERN | NORMAL   |    |       |            |

  @req-10.7
  Szenariogrundriss: Eine Verweigerung wird nie auf dem Event-Loop protokolliert
    Angenommen die Threads des Verweigerungs-Melders werden beobachtet
    Wenn «<benutzer>» <anfrage>
    Dann antwortet das Archiv mit Status 403 und Fehlercode «<fehlercode>»
    Und lief der Melder nur auf Threads, die blockieren dürfen

    Beispiele:
      | benutzer           | anfrage                               | fehlercode    | fall                     |
      | treuhaender-urinet | ein Dokument erfasst                  | NICHT_ERLAUBT | Rolle nach @RolesAllowed |
      | ohne-rolle-urinet  | ein Dokument erfasst                  | NICHT_ERLAUBT | ohne Archiv-Rolle        |
      | mitarbeiter-urinet | das Probe-Dokument «NORMAL» bestätigt | NICHT_ERLAUBT | im ZugangFilter          |
