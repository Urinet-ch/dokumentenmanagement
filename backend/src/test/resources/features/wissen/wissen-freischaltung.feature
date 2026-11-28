# language: de
@spec-04 @wissen-freischaltung
Funktionalität: Wissen je Mandant freischalten (Requirement 22, Aufgaben 25.1 bis 25.3)
  Der Betreiber schaltet Wissen je Mandant mit «archiv mandant wissen <code> ein|aus» (22.2; JUnit
  «WissenFreischaltungKommandoTest», Migration 22.1 in «WissenFreischaltungMigrationTest»). Die Szenarien schalten
  über denselben Anwendungsfall wie das Kommando; danach sind «urinet» und «vhf-gsk» wieder freigeschaltet
  (Testdaten). Dass nur das Kommando den Zustand ändert (22.3), prüft zusätzlich «WissenFreischaltungArchitekturTest»,
  dass jede Operation unter «/wissen…» erfasst ist (22.6), «WissenspfadeVertragTest». Die Oberfläche (22.5) belegt
  «freischaltung-oberflaeche.feature». Die Datei läuft nach «lint.feature», weil ihre Leseproben sonst die Seitenpaare
  des Lints vermehren. Alle Daten sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-22.4
  Szenario: Die Funktionsabfrage nennt den Zustand des eigenen Mandanten
    Angenommen Wissen ist für «vhf-gsk» abgeschaltet
    Wenn «inhaber-urinet» fragt die Funktionen des Mandanten ab
    Dann ist Wissen in den Funktionen verfügbar
    Wenn «geschaeftsstelle-vhf-gsk» fragt die Funktionen des Mandanten ab
    Dann ist Wissen in den Funktionen nicht verfügbar

  @req-22.3
  Szenario: Auch der Inhaber kann Wissen weder über die Einstellungen noch über die Schnittstelle freischalten
    Angenommen Wissen ist für «urinet» abgeschaltet
    Dann antworten für «inhaber-urinet» diese Anfragen mit Status 403 und Fehlercode «WISSEN_NICHT_FREIGESCHALTET»:
      | methode | pfad                                   | koerper                         |
      | PUT     | /api/v1/wissensbasis/einstellungen     | {"freigeschaltet": true}        |
      | POST    | /api/v1/mandant/einstellungen/vorschau | {"wissen": {"freigeschaltet": true}} |
    Und ist Wissen für «urinet» nicht freigeschaltet

  @req-22.6
  Szenario: Ohne Freischaltung antwortet jeder Wissenspfad nach 401 und vor Rechtematrix und 404 mit 403
    Angenommen Wissen ist für «urinet» abgeschaltet
    Und der Bestand der Wissensbasis von «urinet» ist gezählt
    Dann antworten ohne Anmeldung diese Anfragen mit Status 401:
      | methode | pfad                  | koerper |
      | GET     | /api/v1/wissensseiten |         |
    Und antworten für «mitarbeiter-urinet» diese Anfragen mit Status 403 und Fehlercode «WISSEN_NICHT_FREIGESCHALTET»:
      | methode | pfad                                            | koerper             |
      | GET     | /api/v1/wissensseiten                           |                     |
      | GET     | /api/v1/wissensseiten/<unbekannt>               |                     |
      | GET     | /api/v1/wissensseiten/<unbekannt>/versionen     |                     |
      | GET     | /api/v1/wissensbasis/log                        |                     |
      | GET     | /api/v1/wissensbasis/einstellungen              |                     |
      | POST    | /api/v1/wissensbasis/lint                       | {}                  |
      | POST    | /api/v1/wissensbasis/exporte                    | {}                  |
      | GET     | /api/v1/wissensbasis/exporte/<unbekannt>        |                     |
      | GET     | /api/v1/wissen/quellen                          |                     |
      | GET     | /api/v1/wissensentwuerfe                        |                     |
      | GET     | /api/v1/wissensentwuerfe/<unbekannt>            |                     |
      | POST    | /api/v1/wissensentwuerfe/analyse                | {}                  |
      | POST    | /api/v1/dokumente/<unbekannt>/wissensquelle     |                     |
      | DELETE  | /api/v1/dokumente/<unbekannt>/wissensquelle     |                     |
      | POST    | /api/v1/dokumente/<unbekannt>/wissen-ingest     | {}                  |
      | POST    | /api/v1/chat/gespraeche                         | {"modus": "WISSEN"} |
    Und antworten für «inhaber-urinet» diese Anfragen mit Status 403 und Fehlercode «WISSEN_NICHT_FREIGESCHALTET»:
      | methode | pfad                               | koerper |
      | GET     | /api/v1/wissensbasis/einstellungen |         |
      | POST    | /api/v1/wissensbasis/lint          | {}      |
    Und antworten mit einem API-Token «generator» von «urinet» diese Anfragen mit Status 403 und Fehlercode «WISSEN_NICHT_FREIGESCHALTET»:
      | methode | pfad                                        | koerper |
      | GET     | /api/v1/wissensseiten                       |         |
      | GET     | /api/v1/wissensbasis/log                    |         |
      | POST    | /api/v1/dokumente/<unbekannt>/wissensquelle |         |
    Und zeigt der Einstellungskatalog für «inhaber-urinet» die Wissensbasis nicht
    Und ist der Bestand der Wissensbasis von «urinet» unverändert
    Und wartet in «urinet» kein Auftrag der Wissensbasis
    Wenn Wissen für «urinet» wieder freigeschaltet wird
    Dann antworten für «mitarbeiter-urinet» diese Anfragen mit Status 404 und Fehlercode «NICHT_GEFUNDEN»:
      | methode | pfad                              | koerper |
      | GET     | /api/v1/wissensseiten/<unbekannt> |         |
    Und antworten für «mitarbeiter-urinet» diese Anfragen mit Status 403 und Fehlercode «NICHT_ERLAUBT»:
      | methode | pfad                               | koerper |
      | GET     | /api/v1/wissensbasis/einstellungen |         |
    Und zeigt der Einstellungskatalog für «inhaber-urinet» die Wissensbasis

  @req-22.6
  Szenario: Eine widerrufene Sitzung erhält zuerst 401, auch wenn ihre Rolle den Endpunkt nie darf
    Angenommen Wissen ist für «urinet» abgeschaltet
    Und die Sitzung von «mitarbeiter-urinet» ist widerrufen
    Dann antworten mit der widerrufenen Sitzung von «mitarbeiter-urinet» diese Anfragen mit Status 401 und Fehlercode «NICHT_ANGEMELDET»:
      | methode | pfad | koerper |
      | GET | /api/v1/wissensbasis/einstellungen | |
      | GET | /api/v1/wissensseiten | |
      | GET | /api/v1/mandant/einstellungen | |

  @req-22.6
  Szenario: Ohne Freischaltung zeigen Konfigurationsdatei und Versionen die Wissenseinstellungen nicht und ändern sie nicht
    Angenommen «inhaber-urinet» hat das Budget je Wissens-Ingest auf 234567 gesetzt
    Und Wissen ist für «urinet» abgeschaltet
    Dann zeigen Konfigurationsdatei und Versionsabbild für «inhaber-urinet» die Einstellungen der Wissensbasis nicht
    Wenn «inhaber-urinet» eine Konfigurationsdatei mit dem Budget je Wissens-Ingest 345678 einspielt
    Und «inhaber-urinet» die Einstellungen auf die Version vor dem Budget je Wissens-Ingest zurücksetzt
    Dann beträgt das Budget je Wissens-Ingest von «urinet» 234567
    Wenn Wissen für «urinet» wieder freigeschaltet wird
    Dann zeigen Konfigurationsdatei und Versionsabbild für «inhaber-urinet» die Einstellungen der Wissensbasis
    Und beträgt das Budget je Wissens-Ingest von «urinet» 234567

  @req-22.6 @req-22.7
  Szenario: Gespräche im Modus WISSEN sind ohne Freischaltung weder sichtbar noch lesbar noch fortsetzbar
    Angenommen der Chat nutzt den Agenten mit dem simulierten Sprachmodell
    Und synthetische Wissensleseproben sind angelegt
    Und das simulierte Sprachmodell ruft Index und sichtbare Wissensseite der Leseprobe auf
    Wenn «mitarbeiter-urinet» ein Gespräch im Modus «WISSEN» anlegt
    Und «mitarbeiter-urinet» im angelegten Gespräch die Frage «Welche Regel gilt?» stellt
    Dann hat das simulierte Sprachmodell 3 Anfragen erhalten
    Angenommen Wissen ist für «urinet» abgeschaltet
    Dann fehlt das angelegte Gespräch in der Liste von «mitarbeiter-urinet»
    Und antwortet der Verlauf des angelegten Gesprächs für «mitarbeiter-urinet» mit Status 403 und Fehlercode «WISSEN_NICHT_FREIGESCHALTET»
    Wenn «mitarbeiter-urinet» im angelegten Gespräch versucht, eine Frage zu stellen
    Dann antwortet das Archiv mit Status 403 und dem Fehlercode «WISSEN_NICHT_FREIGESCHALTET»
    Und hat das simulierte Sprachmodell 3 Anfragen erhalten
    Wenn Wissen für «urinet» wieder freigeschaltet wird
    Dann zeigen Liste und Verlauf von «mitarbeiter-urinet» für das Gespräch den Modus «WISSEN»

  @req-22.7
  Szenario: Der Archivchat bietet ohne Freischaltung keine Wissenswerkzeuge an und gibt frühere Wissensantworten nicht weiter
    Angenommen der Chat nutzt den Agenten mit dem simulierten Sprachmodell
    Und synthetische Wissensleseproben sind angelegt
    Und das simulierte Sprachmodell liest die Wissensseite der Leseprobe und beantwortet danach eine Folgefrage
    Wenn «mitarbeiter-urinet» ein Gespräch im Modus «ARCHIV» anlegt
    Und «mitarbeiter-urinet» im angelegten Gespräch die Frage «Welche Regel gilt?» stellt
    Dann enthalten Stream und Verlauf die Wissensseite mit ihrem direkten Quelldokument
    Angenommen Wissen ist für «urinet» abgeschaltet
    Wenn «mitarbeiter-urinet» im angelegten Gespräch die Frage «Und ohne Wissensbasis?» stellt
    Dann hat das simulierte Sprachmodell 4 Anfragen erhalten
    Und bietet die Anfrage 4 dem Sprachmodell genau die Werkzeuge «dokumente_suchen, dokument_zeigen, fristen, kennzahl, offene_posten»
    Und enthält keine Nachricht der Anfrage 4 den Text «Die Wissensseite nennt eine Aussage.»
    Und enthält die Anfrage 4 die Nachricht «Welche Regel gilt?»
    Und zeigt der Verlauf von «mitarbeiter-urinet» die Wissensquelle ohne Titel und Link, mit Quelldokument und ohne Analyse

  @req-22.8
  Szenario: Ohne Freischaltung verhält sich der öffentliche Wissenschat wie bei ausgeschaltetem Schalter
    Angenommen Wissen ist für «urinet» abgeschaltet
    Und der Chatbot-Schlüssel «frei» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Wenn die Webseite mit dem Schlüssel «frei» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann liefert der Chatbot Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten

  @req-22.9
  Szenario: Ein wartender Ingest endet nach dem Abschalten ohne KI-Aufruf und startet danach nicht von selbst
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und WireMock liefert gültige Antworten für alle vier Ingest-Schritte
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Wenn Wissen für «urinet» abgeschaltet wird
    Dann endet der Ingest-Entwurf mit «FEHLER» und dem Hinweis «WISSEN_AUSGESCHALTET»
    Und es erfolgt kein KI-Aufruf
    Wenn der Lint-Zeitplan von «urinet» jetzt fällig ist
    Dann legt der Zeitplan in «urinet» keinen Lint-Lauf an
    Wenn Wissen für «urinet» wieder freigeschaltet wird
    Dann wartet in «urinet» kein Auftrag der Wissensbasis
    Und es erfolgt kein KI-Aufruf

  @req-22.10
  Szenario: Abschalten löscht nichts, Quellenhinweise entstehen weiter und sind nach dem Einschalten sichtbar
    Angenommen eine normale Wissensseite verweist auf ein normales Quelldokument
    Und der Bestand der Wissensbasis von «urinet» ist gezählt
    Wenn Wissen für «urinet» abgeschaltet wird
    Und der Inhaber die Vertraulichkeit des Quelldokuments auf «INTERN» ändert
    Dann ist der Bestand der Wissensbasis von «urinet» unverändert
    Wenn Wissen für «urinet» wieder freigeschaltet wird
    Dann der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»
    Und ist der Bestand der Wissensbasis von «urinet» unverändert
