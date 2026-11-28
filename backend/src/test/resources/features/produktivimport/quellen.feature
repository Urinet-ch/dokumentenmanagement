# language: de
@spec-09 @quellenregister
Funktionalität: Quellenregister, Quellläufe und Prüfsummen-Abfrage
  Register und Läufe je Mandant (Spec 09, Requirement 10, Aufgabe 27.6), Rechte A27 und A28 (Requirement 15).
  Alle Dateien sind synthetisch; Werkzeuge nutzen ein Token vom Scope «import».

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-10.1 @req-10.2
  Szenario: urinet startet mit den allgemeinen Quellen, andere Mandanten nicht
    Wenn «inhaber-urinet» das Quellenregister abfragt
    Dann antwortet das Quellenregister mit Status 200
    Und enthält das Quellenregister diese Quellen:
      | code        | typ         | status | geheimnis |
      | crm-csv     | CRM_CSV     | OFFEN  | nein      |
      | drive       | DRIVE       | OFFEN  | nein      |
      | generatoren | GENERATOR   | OFFEN  | nein      |
      | gmail       | GMAIL_LABEL | OFFEN  | nein      |
      | mac-ordner  | MAC_EINGANG | OFFEN  | nein      |
      | papier      | SCAN        | OFFEN  | nein      |
    Wenn «inhaber-vhf-gsk» das Quellenregister abfragt
    Dann antwortet das Quellenregister mit Status 200
    Und enthält das Quellenregister keine der allgemeinen Quellen von urinet

  @req-10.1
  Szenario: Der Inhaber legt eine Quelle an und pflegt Status, Bemerkung und Geheimnis
    Wenn «inhaber-urinet» die Quelle «probe-ordner» vom Typ «ORDNER» anlegt
    Dann antwortet das Quellenregister mit Status 201
    Wenn «inhaber-urinet» die Quelle «probe-ordner» vom Typ «ORDNER» anlegt
    Dann antwortet das Quellenregister mit Status 409 und Fehlercode «QUELLE_EXISTIERT»
    Wenn «inhaber-urinet» die Quelle «probe-ordner» auf Status «ABGESCHLOSSEN» mit Bemerkung «synthetisch geprüft» setzt
    Dann antwortet das Quellenregister mit Status 200
    Wenn «inhaber-urinet» die Quelle «probe-ordner» mit einer veralteten Version ändert
    Dann antwortet das Quellenregister mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «inhaber-urinet» das Geheimnis der Quelle «probe-ordner» setzt
    Dann antwortet das Quellenregister mit Status 204
    Wenn «inhaber-urinet» das Quellenregister abfragt
    Dann enthält das Quellenregister diese Quellen:
      | code         | typ    | status        | geheimnis |
      | probe-ordner | ORDNER | ABGESCHLOSSEN | ja        |
    Und die Antwort des Quellenregisters enthält das Geheimnis nicht

  @req-10.3 @req-10.4 @req-10.9
  Szenario: Ein echter Lauf zählt Uploads serverseitig, und jedes neue Dokument verweist auf ihn
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn das Werkzeug einen Lauf «ECHT» der Quelle «drive» startet
    Dann antwortet das Quellenregister mit Status 201
    Wenn das Werkzeug einen Lauf «TROCKEN» der Quelle «drive» startet
    Dann antwortet das Quellenregister mit Status 409 und Fehlercode «QUELLE_LAEUFT»
    Wenn das Werkzeug die Datei «A» in den Lauf hochlädt
    Dann ist die Datei im Upload «NEU»
    Wenn das Werkzeug die Datei «A» in den Lauf hochlädt
    Dann ist die Datei im Upload «DUBLETTE»
    Wenn das Werkzeug die Datei «B» in den Lauf hochlädt
    Dann ist die Datei im Upload «NEU»
    Wenn das Werkzeug den Lauf mit 2 übersprungenen Dateien abschliesst
    Dann antwortet das Quellenregister mit Status 200
    Und hat der Lauf die Zähler gefunden 5, neu 2, Dublette nach Prüfsumme 1, übersprungen 2, Fehler 0, und sie gehen auf
    Und verweisen die Dokumente der Dateien «A, B» auf den Lauf mit der Quelle «DRIVE»

  @req-10.4
  Szenario: Mit dem Code des Mac-Eingangs legt der Server einen automatischen Lauf an und setzt die Quelle ORDNER
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn das Werkzeug die Datei «C» mit der Quelle «mac-ordner» hochlädt
    Dann ist die Datei im Upload «NEU»
    Und verweist das Dokument der Datei «C» auf den automatischen Lauf von «mac-ordner» mit der Quelle «ORDNER»

  @req-10.5 @req-10.9
  Szenario: Ein Trockenlauf legt nichts an, seine Zähler meldet das Werkzeug und sie müssen aufgehen
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn das Werkzeug einen Lauf «TROCKEN» der Quelle «generatoren» startet
    Dann antwortet das Quellenregister mit Status 201
    Wenn das Werkzeug die Datei «D» in den Lauf hochlädt
    Dann lehnt das Archiv den Upload in den Quelllauf mit Status 409 und Fehlercode «STATUS_KONFLIKT» ab
    Wenn das Werkzeug die Datei «D» mit der Quelle «generatoren» hochlädt
    Dann lehnt das Archiv den Upload in den Quelllauf mit Status 409 und Fehlercode «QUELLE_LAEUFT» ab
    Und hat der Trockenlauf kein Dokument angelegt
    Wenn das Werkzeug den Lauf mit den Zählern gefunden 3, neu 1, Dublette nach Prüfsumme 1, Fehler 0 abschliesst
    Dann antwortet das Quellenregister mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn das Werkzeug den Lauf mit den Zählern gefunden 3, neu 2, Dublette nach Prüfsumme 1, Fehler 0 abschliesst
    Dann antwortet das Quellenregister mit Status 200
    Und hat der Lauf die Zähler gefunden 3, neu 2, Dublette nach Prüfsumme 1, übersprungen 0, Fehler 0, und sie gehen auf

  @req-10.6 @req-10.9
  Szenario: Die Prüfsummen-Abfrage meldet nur vorhandene Prüfsummen, ohne IDs
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Und das Werkzeug hat die Datei «E» ohne Lauf hochgeladen
    Wenn das Werkzeug die Prüfsummen der Dateien «E, F» abfragt
    Dann antwortet die Prüfsummen-Abfrage mit Status 200
    Und meldet die Prüfsummen-Abfrage nur die Datei «E» als vorhanden, ohne IDs
    Angenommen ein Werkzeug von «vhf-gsk» hat ein Token vom Scope «import»
    Wenn das Werkzeug die Prüfsummen der Dateien «E» abfragt
    Dann meldet die Prüfsummen-Abfrage keine Datei als vorhanden
    Wenn das Werkzeug 1001 Prüfsummen abfragt
    Dann antwortet die Prüfsummen-Abfrage mit Status 400

  @req-10.6
  Szenario: Die Prüfsummen-Abfrage verrät Benutzern keine Dokumente, die sie nicht sehen
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Und das Werkzeug hat die Datei «G» ohne Lauf hochgeladen
    Und das Werkzeug hat die Datei «H» ohne Lauf hochgeladen
    Und das Werkzeug hat die Datei «P» ohne Lauf hochgeladen
    Und das Werkzeug hat die Datei «Q» ohne Lauf hochgeladen
    Und das Dokument der Datei «H» ist bestätigt mit der Vertraulichkeit «NORMAL»
    Und das Dokument der Datei «P» ist bestätigt als «PERSONAL» von «inhaber-urinet»
    Und das Dokument der Datei «Q» ist bestätigt als «PERSONAL» von «mitarbeiter-urinet»
    Wenn «mitarbeiter-urinet» die Prüfsummen der Dateien «G, H, P, Q» abfragt
    Dann meldet die Prüfsummen-Abfrage genau die Dateien «H, Q» als vorhanden
    Wenn «inhaber-urinet» die Prüfsummen der Dateien «G, H, P, Q» abfragt
    Dann meldet die Prüfsummen-Abfrage genau die Dateien «G, H, P, Q» als vorhanden
    Wenn das Werkzeug die Prüfsummen der Dateien «G, H, P, Q» abfragt
    Dann meldet die Prüfsummen-Abfrage genau die Dateien «G, H, P, Q» als vorhanden

  @req-10.6
  Szenario: Stapel-Prüfsummen sind nur für Berechtigte sichtbar
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Und das Stapel-Original der Datei «R» liegt im Status «EMPFANGEN»
    Und das Stapel-Original der Datei «S» liegt im Status «VERWORFEN»
    Wenn «mitarbeiter-urinet» die Prüfsummen der Dateien «R, S» abfragt
    Dann meldet die Prüfsummen-Abfrage keine Datei als vorhanden
    Wenn «inhaber-urinet» die Prüfsummen der Dateien «R, S» abfragt
    Dann meldet die Prüfsummen-Abfrage nur die Datei «R» als vorhanden, ohne IDs
    Wenn das Werkzeug die Prüfsummen der Dateien «R, S» abfragt
    Dann meldet die Prüfsummen-Abfrage nur die Datei «R» als vorhanden, ohne IDs
    Angenommen ein Werkzeug von «vhf-gsk» hat ein Token vom Scope «import»
    Und das Stapel-Original der Datei «T» liegt im Status «EMPFANGEN»
    Wenn «geschaeftsstelle-vhf-gsk» die Prüfsummen der Dateien «R, T» abfragt
    Dann meldet die Prüfsummen-Abfrage nur die Datei «T» als vorhanden, ohne IDs
    Wenn das Werkzeug die Prüfsummen der Dateien «R, T» abfragt
    Dann meldet die Prüfsummen-Abfrage nur die Datei «T» als vorhanden, ohne IDs

  @req-10.1
  Szenario: Quellen und Läufe sind je Mandant getrennt
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn das Werkzeug einen Lauf «ECHT» der Quelle «drive» startet
    Dann antwortet das Quellenregister mit Status 201
    Und bleiben Quellen und Läufe von urinet für vhf-gsk unbekannt

  @req-10.9
  Szenario: Ein Token vom Scope import erreicht das Register selbst nicht
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn das Werkzeug das Quellenregister abfragt
    Dann antwortet das Quellenregister mit Status 403
    Wenn das Werkzeug die Quelle «drive» auf Status «LAUFEND» setzt
    Dann antwortet das Quellenregister mit Status 403

  @req-15.2
  Szenariogrundriss: A27 Quellenregister ansehen je Rolle
    Angenommen der Stand der Verweigerungen von «<mandant>» ist gemerkt
    Wenn «<benutzer>» das Quellenregister abfragt
    Dann antwortet das Quellenregister mit Status <status>
    Und hat das Zugriffsprotokoll von «<mandant>» für «<benutzer>» <verweigert> Verweigerung «A27»

    Beispiele:
      | benutzer                 | mandant | status | verweigert |
      | inhaber-urinet           | urinet  | 200    | keine      |
      | treuhaender-urinet       | urinet  | 200    | keine      |
      | mitarbeiter-urinet       | urinet  | 403    | eine       |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | 200    | keine      |

  @req-15.2
  Szenariogrundriss: A28 Quellen pflegen je Rolle
    Angenommen der Stand der Verweigerungen von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Quelle «<quelle>» auf Status «OFFEN» setzt
    Dann antwortet das Quellenregister mit Status <status>
    Und hat das Zugriffsprotokoll von «<mandant>» für «<benutzer>» <verweigert> Verweigerung «A28»

    Beispiele:
      | benutzer                 | mandant | quelle | status | verweigert |
      | inhaber-urinet           | urinet  | papier | 200    | keine      |
      | treuhaender-urinet       | urinet  | papier | 403    | eine       |
      | mitarbeiter-urinet       | urinet  | papier | 403    | eine       |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | papier | 403    | eine       |

  @req-7.1 @req-7.8
  Szenario: Eine Gmail-Quelle startet einen begrenzten Quelllauf als Auftrag
    Angenommen ein Werkzeug von «urinet» hat ein Token vom Scope «import»
    Wenn «inhaber-urinet» die Gmail-Quelle «gmail» mit synthetischer Konfiguration setzt
    Dann antwortet das Quellenregister mit Status 200
    Wenn «inhaber-urinet» das Geheimnis der Quelle «gmail» setzt
    Dann antwortet das Quellenregister mit Status 204
    Wenn das Werkzeug einen Lauf «ECHT» der Quelle «gmail» startet
    Dann antwortet das Quellenregister mit Status 201
    Und ist für den Gmail-Quelllauf ein Auftrag «QUELLLAUF_GMAIL» eingereiht

  @req-7.2
  Szenario: Ein Gmail-Label bleibt beim lesenden Abruf unverändert
    Angenommen ein Gmail-Label mit einer synthetischen Nachricht existiert
    Wenn das Archiv das Gmail-Label nur lesend abruft
    Dann bleibt die Gmail-Nachricht ungelesen und unverändert im Label

  @req-7.3 @req-7.4 @gmail-verarbeitung
  Szenario: Gmail-Anhänge werden einzeln mit Hinweis erfasst und Kleinstbilder übersprungen
    Angenommen eine Gmail-Nachricht mit unterstützten und nicht unterstützten Anhängen vorliegt
    Wenn das Archiv die Gmail-Nachricht erfasst
    Dann werden nur unterstützte Dateien einzeln erfasst und kleine Bilder übersprungen

  @req-7.5 @gmail-verarbeitung
  Szenario: Nachrichten ohne Anhang werden nur auf Wunsch Korrespondenz
    Angenommen eine Gmail-Nachricht ohne Anhänge vorliegt
    Wenn das Archiv die Gmail-Nachricht ohne Anhang mit beiden Optionen erfasst
    Dann wird die Nachricht ohne Anhang nur mit aktivierter Option als Korrespondenz erfasst

  @req-7.6 @gmail-verarbeitung
  Szenario: Bereits bekannte Message-IDs werden bei Wiederholung und Herkunftsmix übersprungen
    Angenommen eine Message-ID ist bereits im E-Mail-Eingang erfasst
    Wenn das Archiv dieselbe Gmail-Nachricht erneut erfasst
    Dann erzeugt die wiederholte oder bereits bekannte Message-ID keine neuen Dokumente

  @req-7.7
  Szenario: Ein falsches App-Passwort wird weder angezeigt noch protokolliert
    Angenommen ein Gmail-Label mit einer synthetischen Nachricht existiert
    Wenn das Archiv das Gmail-Label mit falschem App-Passwort abruft
    Dann meldet Gmail einen sicheren Anmeldefehler ohne das Passwort

  @req-7.8
  Szenario: Gmail-Läufe beachten Startdatum und Nachrichtengrenze
    Angenommen ein Gmail-Label mit zwei synthetischen Nachrichten existiert
    Wenn das Archiv das Gmail-Label je Lauf begrenzt abruft
    Dann liest der Gmail-Import höchstens die konfigurierte Anzahl ab dem Startdatum
