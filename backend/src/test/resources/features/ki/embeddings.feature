# language: de
@spec-02
Funktionalität: Abschnitte und Embeddings archivierter Dokumente
  Spec 02, Aufgabe 6.3: Jedes archivierte Dokument wird in Abschnitte von höchstens 3'000 Zeichen mit 400 Zeichen
  Überlappung zerlegt; jeder Abschnitt trägt die Kopfzeile «Titel – Art – Partner» und erhält das Embedding von
  bge_multilingual_gemma2 (Req 2.1). Ein Kommando berechnet die Embeddings eines Mandanten neu (Req 2.4).
  Abschnitte liegen nur im Schema des Mandanten; ohne Vektoren (Dev-Stub, kein Token) bleiben vorhandene Vektoren.
  Das Embedding-Modell ist ein WireMock; alle Texte sind synthetisch.

  Hintergrund:
    Angenommen das Embedding-Modell ist bereit

  @req-2.1
  Szenario: Ein archiviertes Dokument erhält Abschnitte mit Seitenbereich und Embedding
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 3 Seiten zu je 1500 Zeichen
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat das Dokument «A» diese Abschnitte:
      | nr | seite_von | seite_bis |
      | 1  | 1         | 1         |
      | 2  | 1         | 2         |
      | 3  | 2         | 3         |
    Und beginnt jeder Abschnitt von «A» mit der Kopfzeile "Probe-Offerte A – Offerte – Muster AG" und hat höchstens 3000 Zeichen Inhalt
    Und beginnt jeder Abschnitt von «A» ab dem zweiten mit 350 bis 400 Zeichen vom Ende des vorigen
    Und hat jeder Abschnitt von «A» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und ging jeder Aufruf an das Embedding-Modell mit dem Modell «bge_multilingual_gemma2», ohne «dimensions» und mit höchstens 16 Texten
    Und hält «ki_verbrauch» je Aufruf eine Zeile «EMBEDDING» mit dem Modell «bge_multilingual_gemma2» und 10 Tokens je Text fest

  @req-2.1
  Szenario: Nach dem Bestätigen und Archivieren hat das Dokument Abschnitte mit Embedding
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG        |
      | titel    | Quittung Papier |
      | datum    | 2026-03-10      |
      | richtung | AUSGABE         |
      | betrag   | 1234.50         |
      | waehrung | CHF             |
    Dann meldet das Archiv Status 202
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «Q» im Status «ARCHIVIERT»
    Und hat das archivierte Dokument «Q» Abschnitte mit Embedding

  @req-2.1
  Szenario: Ein langes Dokument wird in Teilen zu höchstens 16 Abschnitten eingebettet
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «Lang» mit 20 Seiten zu je 3000 Zeichen
    Wenn für das Dokument «Lang» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann beginnt jeder Abschnitt von «Lang» mit der Kopfzeile "Probe-Offerte Lang – Offerte – Muster AG" und hat höchstens 3000 Zeichen Inhalt
    Und beginnt jeder Abschnitt von «Lang» ab dem zweiten mit 350 bis 400 Zeichen vom Ende des vorigen
    Und hat jeder Abschnitt von «Lang» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und gingen mindestens 2 Aufrufe an das Embedding-Modell, zusammen mit einem Text je Abschnitt von «Lang»
    Und ging jeder Aufruf an das Embedding-Modell mit dem Modell «bge_multilingual_gemma2», ohne «dimensions» und mit höchstens 16 Texten
    Und hält «ki_verbrauch» je Aufruf eine Zeile «EMBEDDING» mit dem Modell «bge_multilingual_gemma2» und 10 Tokens je Text fest

  @req-2.1
  Szenario: Antwortet das Embedding-Modell mit einem Fehler, ist das ein Fehlversuch ohne Abschnitte
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 1 Seite zu je 800 Zeichen
    Und das Embedding-Modell antwortet mit Status 500
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat der Auftrag «EMBEDDING» 1 Fehlversuch und wartet auf den nächsten Versuch
    Und hat das Dokument «A» 0 Abschnitte
    Und ist das Dokument «A» im Status «ARCHIVIERT» geblieben

  @req-2.1
  Szenario: Ein Fehlversuch lässt vorhandene Abschnitte unverändert
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 2 Seiten zu je 2000 Zeichen
    Und für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Und die Abschnitte von «A» sind gemerkt
    Und das Embedding-Modell antwortet mit Status 500
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat der Auftrag «EMBEDDING» 1 Fehlversuch und wartet auf den nächsten Versuch
    Und sind die Abschnitte von «A» unverändert
    Und ist das Dokument «A» im Status «ARCHIVIERT» geblieben

  @req-2.1
  Szenariogrundriss: Ein Wert ausserhalb von halfvec oder ein nicht endlicher Wert ist ein Fehlversuch ohne Abschnitte
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 1 Seite zu je 800 Zeichen
    Und das Embedding-Modell antwortet mit einem Vektor mit dem Wert <wert>
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat der Auftrag «EMBEDDING» 1 Fehlversuch und wartet auf den nächsten Versuch
    Und hat das Dokument «A» 0 Abschnitte

    Beispiele:
      | wert   |
      | 70000  |
      | -65505 |
      | 1e39   |

  @req-2.1
  Szenario: Ohne Embedding-Modell (Dev-Stub) entstehen die Abschnitte ohne Vektor
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 2 Seiten zu je 2000 Zeichen
    Und das Embedding-Modell liefert keine Vektoren wie der Dev-Stub
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann ist der Auftrag «EMBEDDING» fertig
    Und hat das Dokument «A» mindestens 2 Abschnitte
    Und hat kein Abschnitt von «A» einen Vektor
    Und ging kein Aufruf an das Embedding-Modell

  @req-2.1
  Szenario: Ohne Token bleiben vorhandene Abschnitte mit Vektor unverändert
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 2 Seiten zu je 2000 Zeichen
    Und für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Und die Abschnitte von «A» sind gemerkt
    Und das Embedding-Modell hat kein Token
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann ist der Auftrag «EMBEDDING» fertig
    Und sind die Abschnitte von «A» unverändert
    Und hat jeder Abschnitt von «A» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten

  @req-2.1
  Szenario: Ein Dokument mit 500 Abschnitten wird in kleinen Teilen geschrieben, ohne den Auftrag zu sperren
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «Gross» mit 500 Seiten zu je 2500 Zeichen aus wenigen Wörtern
    Wenn für das Dokument «Gross» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet, während das Lebenszeichen gemessen wird
    Dann ist der Auftrag «EMBEDDING» fertig
    Und hat das Dokument «Gross» mindestens 500 Abschnitte
    Und hat jeder Abschnitt von «Gross» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und blieb das Lebenszeichen des Auftrags höchstens 800 ms aus

  @req-2.1
  Szenario: Die Abschnitte eines Dokuments liegen nur im Schema seines Mandanten
    Angenommen im Mandanten «vhf-gsk» liegt das archivierte Dokument «V» mit 2 Seiten zu je 2000 Zeichen
    Wenn für das Dokument «V» der Auftrag «EMBEDDING» im Mandanten «vhf-gsk» eingereiht ist und der Worker ihn abarbeitet
    Dann hat jeder Abschnitt von «V» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und liegt im Mandanten «urinet» kein Abschnitt von «V»
    Und die Abschnitte von «V» sind gemerkt
    Wenn für das Dokument «V» der Auftrag «EMBEDDING» im Mandanten «urinet» eingereiht ist und der Worker ihn abarbeitet
    Dann hat der Auftrag «EMBEDDING» 1 Fehlversuch und wartet auf den nächsten Versuch
    Und sind die Abschnitte von «V» unverändert
    Und liegt im Mandanten «urinet» kein Abschnitt von «V»

  @req-2.1
  Szenario: Ändern sich Titel und Partner eines archivierten Dokuments, entstehen die Abschnitte mit der neuen Kopfzeile
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» gibt es den Kontakt «Probe-Garage»
    Und im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG        |
      | titel    | Quittung Papier |
      | datum    | 2026-03-10      |
      | richtung | AUSGABE         |
      | betrag   | 1234.50         |
      | waehrung | CHF             |
    Und der Worker die Archivierung ausführt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | titel   | Quittung Werkstatt |
      | partner | Probe-Garage       |
      | grund   | Partner ergänzt    |
    Dann meldet das Archiv Status 200
    Und ist für das Dokument «Q» ein Auftrag «EMBEDDING» eingereiht
    Wenn der Worker die Archivierung ausführt
    Dann beginnt jeder Abschnitt des archivierten Dokuments «Q» mit dem Titel "Quittung Werkstatt" und endet seine Kopfzeile mit dem Partner "Probe-Garage"
    Und hat das archivierte Dokument «Q» Abschnitte mit Embedding
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | tags  | Werkstatt   |
      | grund | Tag ergänzt |
    Dann meldet das Archiv Status 200
    Und ist für das Dokument «Q» kein Auftrag «EMBEDDING» eingereiht

  @req-2.1
  Szenario: Ein Vektor mit falscher Zahl von Werten ist ein Fehlversuch ohne Abschnitte
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 1 Seite zu je 800 Zeichen
    Und das Embedding-Modell antwortet mit einem Vektor aus 1024 Werten
    Wenn für das Dokument «A» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat der Auftrag «EMBEDDING» 1 Fehlversuch und wartet auf den nächsten Versuch
    Und hat das Dokument «A» 0 Abschnitte

  @req-2.1
  Szenario: Ein Dokument, das nicht mehr archiviert ist, erhält keine Abschnitte
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB» mit Text
    Wenn für das Dokument «K» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Dann hat das Dokument «K» 0 Abschnitte

  @req-2.4
  Szenario: Die Nachberechnung holt fehlende Embeddings nach und berechnet auf Wunsch alle neu
    Angenommen im Mandanten «urinet» liegt das archivierte Dokument «A» mit 1 Seite zu je 800 Zeichen
    Und im Mandanten «urinet» liegt das archivierte Dokument «B» mit 1 Seite zu je 800 Zeichen
    Und das Dokument «B» hat einen Abschnitt ohne Vektor
    Und im Mandanten «urinet» liegt das archivierte Dokument «C» mit 2 Seiten zu je 2000 Zeichen
    Und für das Dokument «C» der Auftrag «EMBEDDING» eingereiht ist und der Worker ihn abarbeitet
    Und die Abschnitte von «C» sind gemerkt
    Und im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB» mit Text
    Und im Mandanten «vhf-gsk» liegt das archivierte Dokument «V» mit 1 Seite zu je 800 Zeichen
    Wenn der Betrieb "embedding nachberechnen urinet --nur-fehlende" aufruft
    Und der Worker alle Aufträge abarbeitet
    Dann endet das Kommando mit Exit-Code 0 und der Ausgabe "Nachberechnung der Embeddings für Mandant urinet eingereiht"
    Und hat jeder Abschnitt von «A» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und hat jeder Abschnitt von «B» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und sind die Abschnitte von «C» unverändert
    Und hat das Dokument «K» 0 Abschnitte
    Wenn der Betrieb "embedding nachberechnen urinet" aufruft
    Und der Worker alle Aufträge abarbeitet
    Dann endet das Kommando mit Exit-Code 0 und der Ausgabe "alle archivierten Dokumente"
    Und sind die Abschnitte von «C» neu berechnet
    Und hat jeder Abschnitt von «C» den Vektor des Embedding-Modells zu seinem Text mit 3584 Werten
    Und hat das Dokument «K» 0 Abschnitte
    Und hat das Dokument «V» 0 Abschnitte

  @req-2.4
  Szenario: Läuft ein Teil der Nachberechnung nach seinem Commit erneut, reiht er nichts doppelt ein
    Angenommen im Mandanten «urinet» liegen 501 archivierte Dokumente
    Wenn der Betrieb "embedding nachberechnen urinet" aufruft
    Und der erste Teil der Nachberechnung zweimal läuft, ohne abgeschlossen zu werden, und nach einer Freigabe ein drittes Mal
    Dann hat der erste Teil 500 Aufträge «EMBEDDING» für verschiedene Dokumente und 1 Folgeteil eingereiht
    Wenn der Betrieb "embedding nachberechnen urinet --nur-fehlende" aufruft
    Dann endet das Kommando mit Exit-Code 0 und der Ausgabe "Nachberechnung der Embeddings für Mandant urinet läuft bereits"
    Und ist genau 1 Nachberechnung gestartet worden

  @req-2.4
  Szenario: Die Nachberechnung lehnt einen unbekannten Mandanten und einen falschen Aufruf ab
    Wenn der Betrieb "embedding nachberechnen unbekannt" aufruft
    Dann endet das Kommando mit Exit-Code 1 und der Ausgabe "Mandant «unbekannt» ist nicht angelegt oder nicht aktiv."
    Wenn der Betrieb "embedding nachberechnen" aufruft
    Dann endet das Kommando mit Exit-Code 2 und der Ausgabe "Aufruf: archiv embedding nachberechnen <code> [--nur-fehlende]"
