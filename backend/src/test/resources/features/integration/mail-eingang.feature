# language: de
@spec-02
Funktionalität: E-Mail-Eingang
  Das Archiv ruft das Postfach jedes Mandanten per IMAP über TLS ab, erfasst Anhänge zugelassener Absender wie einen
  Upload und legt Mails ohne Anhang als Korrespondenz ab. Die Szenarien laufen gegen GreenMail (IMAP über TLS mit
  Test-CA) mit synthetischen Nachrichten, keine echten Mails oder Belege. Abgerufen wird direkt in einem eigenen Thread;
  ein Szenario ruft wie im Betrieb über den Takt im Thread des Schedulers ab.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und für «urinet» ist ein Postfach mit den zugelassenen Absendern «inhaber@example.invalid» und «Buero@Example.invalid» eingerichtet
    Und für «vhf-gsk» ist kein Postfach eingerichtet

  @req-1.1
  Szenario: Das Archiv ruft das Postfach über TLS ab, Zugangsdaten stehen weder in der Datenbank noch im Log
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Abruf über TLS» und einem PDF «rechnung.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer
    Und das Passwort steht in «integration_mail» von «urinet» nur verschlüsselt
    Und enthält das Log weder Benutzer noch Passwort des Postfachs

  @req-1.1
  Szenario: Ein falsches Passwort zählt als gescheiterter Abruf, das Log nennt nur die Ursachen-Klassen
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Und das Passwort des Postfachs von «urinet» ist falsch eingerichtet
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann zählt «integration_mail» von «urinet» 1 gescheiterten Abruf in Folge
    Und enthält das Log weder Benutzer noch Passwort des Postfachs

  @req-1.1
  Szenario: Ohne eingerichtetes Postfach ruht der Mail-Eingang und sagt das einmal im Log, ohne Fehler
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Wenn der Mail-Eingang das Postfach von «vhf-gsk» zweimal abruft
    Dann meldet das Log genau einmal, dass der Mail-Eingang für «vhf-gsk» ruht
    Und das Log enthält zum Mail-Eingang keine Warnung und keinen Fehler

  @req-1.2 @req-1.6
  Szenario: Jeder unterstützte Anhang wird ein eigenes Dokument im Eingang, mit Absender, Datum und Betreff als Hinweis
    Angenommen im Postfach liegt eine Mail von «Buero@example.invalid» vom «13.11.2026 10:15» mit dem Betreff «Belege November» und diesen Anhängen:
      | name             | typ  |
      | Rechnung_42.pdf  | pdf  |
      | Quittung.png     | png  |
      | notiz.txt        | text |
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail diese Dokumente mit Quelle «EMAIL» angelegt:
      | dateiname       | hinweis                                                                                 |
      | Rechnung_42.pdf | E-Mail; Von: Buero@example.invalid; Datum: 13.11.2026 10:15; Betreff: Belege November |
      | Quittung.png    | E-Mail; Von: Buero@example.invalid; Datum: 13.11.2026 10:15; Betreff: Belege November |
    Und jedes dieser Dokumente steht unbestätigt als «PERSONAL» in «NEU» mit einem Auftrag «VERARBEITEN»
    Und «mail_eingang» von «urinet» führt die Mail als «ERFASST» mit diesen Dokumenten
    Und liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer
    Und im Mandanten «vhf-gsk» ist zur Mail nichts entstanden

  @req-1.2
  Szenario: Ein in den Text eingefügtes Foto einer Quittung wird ein Bild-Dokument, keine Korrespondenz
    Angenommen im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Quittung» und einem in den Text eingefügten Foto «IMG_0042.jpg» von 300 KiB
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument angelegt
    Und das Dokument der Mail ist das Foto «IMG_0042.jpg» als «image/jpeg», nicht Korrespondenz

  @req-1.3 @req-1.6
  Szenario: Eine Mail ohne unterstützten Anhang wird Korrespondenz mit .eml und PDF/A aus dem Text im Eingangskorb
    Angenommen die Verarbeitung ist bereit
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Anfrage Offerte» und dem Text «Bitte offerieren Sie zwanzig Trikots» ohne Anhang
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument der Art «KORRESPONDENZ» mit Quelle «EMAIL» angelegt
    Und das Dokument hat die Nachricht als Original 1 «message/rfc822» und ihren Text als Original 2 «application/pdf»
    Wenn der Worker die Aufträge bearbeitet
    Dann steht das Dokument der Mail unbestätigt im «EINGANGSKORB» mit PDF/A und dem Text «zwanzig Trikots» im Volltext

  @req-1.3
  Szenario: Eine HTML-Mail mit eingebettetem Logo und ohne Anhang wird Korrespondenz, auch beim zweiten Mal
    Angenommen im Postfach liegt eine HTML-Mail von «inhaber@example.invalid» mit dem Betreff «Anfrage» und einem eingebetteten Logo
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument der Art «KORRESPONDENZ» mit Quelle «EMAIL» angelegt
    Angenommen im Postfach liegt eine zweite HTML-Mail von «inhaber@example.invalid» mit demselben eingebetteten Logo
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument der Art «KORRESPONDENZ» mit Quelle «EMAIL» angelegt

  @req-1.3 @req-1.6
  Szenario: Wird kein unterstützter Anhang angenommen, wird die Mail Korrespondenz
    Angenommen im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Defekt» und einem abgeschnittenen PDF «rechnung.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument der Art «KORRESPONDENZ» mit Quelle «EMAIL» angelegt
    Und «mail_eingang» von «urinet» führt die Mail als «ERFASST» mit diesem Dokument

  @req-1.4
  Szenario: Eine Mail eines nicht zugelassenen Absenders kommt in die Quarantäne, das Archiv erfasst nichts
    Angenommen im Postfach liegt eine Mail von «fremd@example.invalid» mit dem Betreff «Ihre Rechnung» und einem PDF «rechnung.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann liegt die Mail im Ordner «Quarantaene» und der Posteingang ist leer
    Und hat das Archiv im Mandanten «urinet» zur Mail kein Dokument angelegt
    Und «mail_eingang» von «urinet» führt die Mail als «QUARANTAENE» ohne Dokument

  @req-1.4 @req-1.5
  Szenario: Kopfwerte mit Steuerzeichen oder überlanger Message-ID kommen bereinigt in die Quarantäne
    Angenommen im Postfach liegt eine Mail von «fremd@example.invalid» mit Steuerzeichen im Betreff und einer Message-ID aus 4000 Zeichen
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann liegt die Mail im Ordner «Quarantaene» und der Posteingang ist leer
    Und «mail_eingang» von «urinet» führt die Mail unter dem Hash ihrer Message-ID als «QUARANTAENE» mit bereinigtem Betreff

  @req-1.4 @req-1.5
  Szenario: Ist der Absender später zugelassen, erfasst das Archiv die Mail aus der Quarantäne, wenn sie wieder im Posteingang liegt
    Angenommen im Postfach liegt eine Mail von «spaet@example.invalid» mit dem Betreff «Vergessen» und einem PDF «beleg.pdf»
    Und der Mail-Eingang hat das Postfach von «urinet» abgerufen
    Und für «urinet» ist «spaet@example.invalid» zusätzlich zugelassen
    Und die Mail liegt wieder im Posteingang
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument angelegt
    Und «mail_eingang» von «urinet» führt die Mail als «ERFASST» mit diesem Dokument
    Und liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer

  @req-1.5
  Szenario: Scheitert die Erfassung, bleibt nichts eingetragen und die Mail im Posteingang, der nächste Abruf erfasst sie
    Angenommen im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Ablage gestört» und einem PDF «beleg.pdf»
    Und der Object Storage nimmt keine Datei mehr an
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann führt «mail_eingang» von «urinet» die Mail nicht, zur Mail gibt es kein Dokument, und sie liegt noch im Posteingang
    Angenommen der Object Storage ist wieder erreichbar
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument angelegt
    Und «mail_eingang» von «urinet» führt die Mail als «ERFASST» mit diesem Dokument
    Und liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer

  @req-1.4 @req-1.5
  Szenario: Fehlen die Ordner, legt das Archiv sie an
    Angenommen das Postfach von «urinet» hat weder «Archiviert» noch «Quarantaene»
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Erster Abruf» und einem PDF «beleg.pdf»
    Und im Postfach liegt eine Mail von «fremd@example.invalid» mit dem Betreff «Werbung» und einem PDF «flyer.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann gibt es im Postfach die Ordner «Archiviert» und «Quarantaene» mit je einer Mail
    Und die Ordner «Archiviert» und «Quarantaene» sind abonniert

  @req-1.5
  Szenario: Ein vorhandener, nicht abonnierter Zielordner wird abonniert
    Angenommen der vorhandene Ordner «Archiviert» ist nicht abonniert
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Vorhandener Ordner» und einem PDF «beleg.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer
    Und der Ordner «Archiviert» ist abonniert

  @req-1.5
  Szenario: Scheitert das Abonnieren, wird die Mail trotzdem verschoben
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Und das Abonnieren von «Archiviert» schlägt fehl
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Abonnieren fehlgeschlagen» und einem PDF «beleg.pdf»
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer
    Und das Abonnieren von «Archiviert» wird nur als Warnung protokolliert

  @req-1.5
  Szenario: Dieselbe Message-ID erfasst das Archiv nie zweimal
    Angenommen im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Zweimal gesendet» und einem PDF «beleg.pdf»
    Und der Mail-Eingang hat das Postfach von «urinet» abgerufen
    Und dieselbe Mail liegt mit derselben Message-ID und einem anderen PDF noch einmal im Posteingang
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument angelegt
    Und liegen beide Nachrichten im Ordner «Archiviert» und der Posteingang ist leer

  @req-1.6
  Szenario: Ein Anhang, der schon im Archiv liegt, ist eine Dublette und wird nicht noch einmal erfasst
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Und im Postfach liegt eine Mail von «inhaber@example.invalid» mit dem Betreff «Original» und einem PDF «beleg.pdf»
    Und der Mail-Eingang hat das Postfach von «urinet» abgerufen
    Und im Postfach liegt eine zweite Mail von «inhaber@example.invalid» mit demselben PDF
    Wenn der Mail-Eingang das Postfach von «urinet» abruft
    Dann hat das Archiv im Mandanten «urinet» zur zweiten Mail kein Dokument angelegt, auch keine Korrespondenz
    Und «mail_eingang» von «urinet» führt die zweite Mail als «ERFASST» ohne Dokument
    Und das Log meldet zur Mail kein neues Dokument

  # Der Takt ruft alle Mandanten ab und meldet dabei einmal, dass der Eingang für «vhf-gsk» ruht; darum zuletzt.
  @req-1.1 @req-1.2
  Szenario: Im Takt des Schedulers erfasst das Archiv eine Mail aus Gmail mit PDF-Anhang wie im Betrieb
    Angenommen das Log des Mail-Eingangs wird mitgeschnitten
    Und im Postfach liegt eine Mail aus Gmail von «inhaber@example.invalid» mit einem PDF «Testbeleg 2026-11.pdf»
    Wenn der Takt des Workers das Postfach im Scheduler abruft
    Dann hat das Archiv im Mandanten «urinet» zur Mail genau ein Dokument angelegt
    Und das Dokument der Mail ist das PDF «Testbeleg 2026-11.pdf» aus dem Anhang
    Und «mail_eingang» von «urinet» führt die Mail als «ERFASST» mit diesem Dokument
    Und liegt die Mail im Ordner «Archiviert» und der Posteingang ist leer
    Und das Log meldet keine Nachricht als nicht verarbeitet
