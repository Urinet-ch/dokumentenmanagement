# language: de
@spec-04
Funktionalität: Wissen je Mandant freischalten – Oberfläche
  Die App richtet sich nach «wissenVerfuegbar» aus GET /api/v1/mandant/funktionen (Requirement 22.4, 22.5). Die
  Attrappe des Archivs schaltet Wissen in den Testdaten für jeden Mandanten ein; die Szenarien schalten es für einen
  Mandanten aus oder lassen die Abfrage scheitern. Die Sperre im Archiv selbst (22.6) belegen die Backend-Szenarien.

  Grundlage:
    Angenommen eine synthetische Wissensquelle für die Markierung ist verfügbar
    Und die Chat-Schnittstelle ist eine Attrappe
    Und die Einstellungen enthalten die Gruppe der Wissensbasis

  @browser @req-22.5
  Szenario: Mit freigeschaltetem Wissen zeigt die App den Bereich Wissen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann zeigt das Menü die Punkte «Wissen» und «Entwürfe»
    Und zeigt die Dokumentansicht die Aktion «Als Wissensquelle verwenden»
    Und zeigt der Chat die Wahl des Modus
    Und zeigt die Hilfe den Abschnitt «Wissen»
    Und zeigen die Einstellungen die Gruppe «Wissensbasis»

  @browser @req-22.5
  Szenario: Ohne Freischaltung ist der Bereich Wissen im zweiten Mandanten vollständig ausgeblendet
    Angenommen Wissen ist für den Mandanten «vhf-gsk» nicht freigeschaltet
    Und ich bin als «geschaeftsstelle» im Mandanten «vhf-gsk» angemeldet
    Dann zeigt das Menü keine Punkte «Wissen» und «Entwürfe»
    Und zeigt die Dokumentansicht keine Aktion «Als Wissensquelle verwenden»
    Und zeigt der Chat keine Wahl des Modus
    Und weist die App nirgends auf Wissen als Zusatzleistung hin
    Und zeigt die Hilfe keinen Abschnitt «Wissen»
    Und ruft die PWA keine Schnittstelle der Wissensbasis auf
    Und sind Dokumentansicht und Chat ohne Wissen bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund

  @browser @req-22.5
  Szenario: Ohne Freischaltung sieht auch der Inhaber keine Einstellungen der Wissensbasis
    Angenommen Wissen ist für den Mandanten «urinet» nicht freigeschaltet
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann zeigt das Menü keine Punkte «Wissen» und «Entwürfe»
    Und zeigen die Einstellungen keine Gruppe «Wissensbasis»
    Und ruft die PWA keine Schnittstelle der Wissensbasis auf

  @browser @req-22.5
  Szenariogrundriss: Der Direktaufruf einer Wissensroute zeigt ohne Freischaltung «Seite nicht gefunden»
    Angenommen Wissen ist für den Mandanten «urinet» nicht freigeschaltet
    Und ich öffne die Seite «<pfad>» vor der Anmeldung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann bin ich auf der Seite «/nicht-gefunden»
    Und sehe ich die Überschrift «404 – Seite nicht gefunden»
    Und ruft die PWA keine Schnittstelle der Wissensbasis auf
    Und ist die Fehleransicht bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund

    Beispiele:
      | pfad                       |
      | /wissen                    |
      | /wissen/entwuerfe          |
      | /wissen/konzepte/probe     |
      | /wissensbasis/einstellungen |

  @browser @req-22.5
  Szenario: Scheitert die Abfrage der Funktionen, bleibt der Bereich Wissen ausgeblendet
    Angenommen die Abfrage der Mandantenfunktionen scheitert
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann zeigt das Menü keine Punkte «Wissen» und «Entwürfe»
    Und zeigt die Dokumentansicht keine Aktion «Als Wissensquelle verwenden»
    Und zeigt der Chat keine Wahl des Modus
    Und ruft die PWA keine Schnittstelle der Wissensbasis auf
