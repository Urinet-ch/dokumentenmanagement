# language: de
@spec-02 @req-15.11
Funktionalität: Chat-Bewertungen speichern
  Benutzer dürfen eigene Chat-Antworten bewerten. Eine Bewertung verweist auf die Nachricht, wird nach 12 Monaten mit ihr
  gelöscht, und Freitext bleibt sicherer Text. Alle Inhalte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-15.2
  Szenario: Eine Bewertung kann geändert, abgerufen und entfernt werden
    Angenommen für «inhaber-urinet» existiert im Mandanten «urinet» die Chat-Antwort «EIGENE»
    Wenn «inhaber-urinet» bewertet «EIGENE» mit dem Körper «{"sterne":2,"grund":"FALSCH","freitext":"Erste Fassung"}»
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Wenn «inhaber-urinet» bewertet «EIGENE» mit dem Körper «{"sterne":4,"grund":"QUELLE","freitext":"Aktualisiert"}»
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Wenn «inhaber-urinet» ruft die Bewertung von «EIGENE» ab
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Und enthält die Antwort die Bewertung mit 4 Sternen und dem Freitext «Aktualisiert»
    Wenn «inhaber-urinet» löscht die Bewertung von «EIGENE»
    Dann ist die Bewertungsanfrage mit Status 204 beantwortet
    Und ist die Bewertung von «EIGENE» nicht mehr gespeichert

  @req-15.2
  Szenario: Bewertungen fremder Benutzer und Mandanten sind nicht zugänglich
    Angenommen für «mitarbeiter-urinet» existiert im Mandanten «urinet» die Chat-Antwort «FREMD»
    Wenn «mitarbeiter-urinet» bewertet «FREMD» mit dem Körper «{"sterne":2,"freitext":"Eigene Bewertung"}»
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Wenn «inhaber-urinet» ruft die Bewertung von «FREMD» ab
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «inhaber-urinet» bewertet «FREMD» mit dem Körper «{"sterne":5,"freitext":"Fremde Änderung"}»
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «inhaber-urinet» löscht die Bewertung von «FREMD»
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «inhaber-vhf-gsk» ruft die Bewertung von «FREMD» ab
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «inhaber-vhf-gsk» bewertet «FREMD» mit dem Körper «{"sterne":5,"freitext":"Fremde Änderung"}»
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «inhaber-vhf-gsk» löscht die Bewertung von «FREMD»
    Dann ist die Bewertungsanfrage mit Status 404 beantwortet
    Wenn «mitarbeiter-urinet» ruft die Bewertung von «FREMD» ab
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Und enthält die Antwort die Bewertung mit 2 Sternen und dem Freitext «Eigene Bewertung»

  @req-15.4
  Szenario: Eine übergrosse Chunked-Bewertung wird während des Empfangs abgelehnt
    Angenommen für «inhaber-urinet» existiert im Mandanten «urinet» die Chat-Antwort «GRENZE»
    Dann wird eine unvollständige Chunked-Bewertung von «inhaber-urinet» für «GRENZE» vor ihrem Ende mit 413 abgelehnt
    Wenn «inhaber-urinet» sendet eine Chunked-Bewertung für «GRENZE» knapp unter der Grenze
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet

  @req-15.3
  Szenario: Eine Bewertung wird mit der Antwort nach 12 Monaten gelöscht
    Angenommen für «inhaber-urinet» existiert im Mandanten «urinet» die Chat-Antwort «ALT»
    Wenn «inhaber-urinet» bewertet «ALT» mit dem Körper «{"sterne":5}»
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Wenn die Chat-Antwort «ALT» älter als 12 Monate wird
    Dann ist die Bewertung von «ALT» nicht mehr gespeichert

  @req-15.4
  Szenario: Freitext wird als Text gespeichert, Steuerzeichen werden entfernt und das Stundenlimit gilt
    Angenommen für «inhaber-urinet» existiert im Mandanten «urinet» die Chat-Antwort «TEXT»
    Wenn «inhaber-urinet» bewertet «TEXT» mit dem Körper «{"sterne":3,"grund":"ANDERES","freitext":"Eins\u0001<b>Text</b>\n weiter"}»
    Dann ist die Bewertungsanfrage mit Status 200 beantwortet
    Wenn «inhaber-urinet» ruft die Bewertung von «TEXT» ab
    Dann enthält die Antwort den gespeicherten Freitext «Eins<b>Text</b> weiter»
    Angenommen «inhaber-urinet» hat bereits 200 Bewertungsversuche innerhalb der letzten Stunde
    Wenn «inhaber-urinet» bewertet «TEXT» mit dem Körper «{"sterne":3}»
    Dann ist die Bewertungsanfrage mit Status 429 beantwortet
    Und enthält die Antwort den Fehlercode «ZU_VIELE_ANFRAGEN»
