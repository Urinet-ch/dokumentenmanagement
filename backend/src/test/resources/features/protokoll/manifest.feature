# language: de
@spec-01
Funktionalität: Tages-Manifest (Aufgabe 5.2)
  Der Auftrag MANIFEST erzeugt je Mandant und Tag ein signiertes Manifest mit Hash-Kette, stempelt es bei zwei
  synthetischen Zeitstempeldiensten «haupt» (OCSP) und «zweit» (Sperrliste) über WireMock und legt alles gesperrt unter
  «manifest/» ab. Jedes Szenario nutzt einen eigenen Mandanten; die Tests reihen den Auftrag selbst ein (kein Planer in test).

  @req-15.1 @req-15.2 @req-15.3 @req-15.11 @req-15.12
  Szenario: Drei Tage ergeben eine signierte und doppelt gestempelte Hash-Kette, die das Prüfwerkzeug als gültig meldet
    Angenommen der Mandant «mf-kette» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-kette» wurde das Dokument «D1» am «11.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und das Dokument «D1» von «mf-kette» hat eine PDF/A-Fassung
    Und im Mandanten «mf-kette» steht am «11.01.2026» um «10:00» ein Eintrag im Zugriffsprotokoll
    Und im Mandanten «mf-kette» steht am «11.01.2026» um «10:05» ein Eintrag im Zugriffsprotokoll zum Dokument «D1» mit IP und Details
    Und im Mandanten «mf-kette» steht am «12.01.2026» um «08:30» eine Metadaten-Version des Dokuments «D1»
    Und im Mandanten «mf-kette» steht am «12.01.2026» um «23:59» ein Eintrag im Zugriffsprotokoll
    Wenn der Manifest-Lauf für «mf-kette» bis «12.01.2026» läuft
    Dann hat «mf-kette» genau je ein Manifest für die Tage vom «10.01.2026» bis «12.01.2026»
    Und das Manifest «10.01.2026» von «mf-kette» beginnt die Kette mit «vorher_sha256» null
    Und das Manifest «11.01.2026» von «mf-kette» enthält das Dokument «D1» mit den Varianten «original,arbeitsfassung,pdfa» samt SHA-256 und Versions-ID
    Und das Manifest «11.01.2026» von «mf-kette» enthält den Hash von 2 Zeile(n) aus «zugriff_protokoll» und 0 Zeile(n) aus «dokument_version»
    Und das Manifest «12.01.2026» von «mf-kette» enthält den Hash von 1 Zeile(n) aus «zugriff_protokoll» und 1 Zeile(n) aus «dokument_version»
    Und das Blatt jeder Protokollzeile mit IP, Details oder Metadaten von «mf-kette» ist der SHA-256 ihrer kanonischen Form über alle Spalten
    Und die Manifeste von «mf-kette» bilden eine Hash-Kette, die Signatur, Zeitstempel und Prüfdaten des Vortags erneuert
    Und jedes Manifest von «mf-kette» ist mit dem öffentlichen Manifest-Schlüssel gültig signiert
    Und jedes Manifest von «mf-kette» trägt je einen gültigen Zeitstempel von «haupt» und «zweit» über SHA-256 von Manifest und Signatur
    Und das Prüfwerkzeug meldet für «mf-kette» vom «10.01.2026» bis «12.01.2026» «Ergebnis: gültig» mit Exit-Code 0
    Und das Prüfwerkzeug ohne Datenbank meldet für «mf-kette» vom «10.01.2026» bis «12.01.2026» «Ergebnis: gültig» mit Exit-Code 0
    Und die Ausgabe des Prüfwerkzeugs nennt «MELDUNG 2026-01-12: festgehaltene Zeitstempel ohne Datenbank nicht bestimmbar (kein Folgemanifest)»

  @req-3.12
  Szenario: Das Manifest nennt den Dateinamen eines Dokuments nicht
    Angenommen der Mandant «mf-name» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-name» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und das Dokument «D1» von «mf-name» heisst «Scan_Rechnung-2026.pdf»
    Wenn der Manifest-Lauf für «mf-name» bis «10.01.2026» läuft
    Dann das Manifest «10.01.2026» von «mf-name» nennt den Dateinamen «Scan_Rechnung-2026.pdf» nicht, seine Einträge tragen nur Dokument, Variante, Nummer, SHA-256 und Versions-ID

  @req-16.7
  Szenario: An die Zeitstempeldienste geht nur der Hash von Manifest und Signatur
    Angenommen der Mandant «mf-anfrage» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-anfrage» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Wenn der Manifest-Lauf für «mf-anfrage» bis «10.01.2026» läuft
    Dann enthält die Anfrage an «zweit» für das Manifest «10.01.2026» von «mf-anfrage» nur den Hash, Nonce und «certReq»
    Und enthält die Anfrage an «haupt» für das Manifest «10.01.2026» von «mf-anfrage» nur den Hash, Nonce und «certReq»

  @req-15.4 @req-6.11
  Szenario: Manifest, Signatur, Zeitstempel und Prüfdaten liegen gesperrt unter «manifest/»
    Angenommen der Mandant «mf-sperre» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-sperre» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Wenn der Manifest-Lauf für «mf-sperre» bis «10.01.2026» läuft
    Dann liegen für das Manifest «10.01.2026» von «mf-sperre» 8 Dateien unter «manifest/», jede im Modus «GOVERNANCE» gesperrt
    Und die Datenbank von «mf-sperre» referenziert das Manifest «10.01.2026» mit Pfad, Versions-ID und Sperre

  @req-15.6
  Szenario: Verpasste Läufe werden in Datumsreihenfolge nachgeholt, ein zweiter Lauf erzeugt nichts Neues
    Angenommen der Mandant «mf-nachholen» ist am «01.02.2026» angelegt
    Wenn der Manifest-Lauf für «mf-nachholen» bis «10.02.2026» läuft
    Dann hat «mf-nachholen» genau je ein Manifest für die Tage vom «01.02.2026» bis «10.02.2026»
    Und die Manifeste von «mf-nachholen» wurden in Datumsreihenfolge erzeugt
    Wenn der Manifest-Lauf für «mf-nachholen» bis «10.02.2026» nochmals läuft
    Dann hat «mf-nachholen» genau je ein Manifest für die Tage vom «01.02.2026» bis «10.02.2026»
    Und unter «manifest/» von «mf-nachholen» ist keine Datei dazugekommen

  @req-15.7
  Szenario: Am Tag der Umstellung auf Sommerzeit entsteht genau ein Manifest über 23 Stunden
    Angenommen der Mandant «mf-sommer» ist am «28.03.2026» angelegt
    Und im Mandanten «mf-sommer» wurde das Dokument «D1» am «29.03.2026» um «03:30» archiviert, aufzubewahren bis «31.12.2036»
    Wenn der Manifest-Lauf für «mf-sommer» bis «30.03.2026» läuft
    Dann hat «mf-sommer» genau je ein Manifest für die Tage vom «28.03.2026» bis «30.03.2026»
    Und das Fenster des Manifests «29.03.2026» von «mf-sommer» umfasst 23 Stunden
    Und das Manifest «29.03.2026» von «mf-sommer» enthält das Dokument «D1» mit den Varianten «original,arbeitsfassung» samt SHA-256 und Versions-ID

  @req-15.7
  Szenario: Am Tag der Umstellung auf Winterzeit entsteht genau ein Manifest über 25 Stunden
    Angenommen der Mandant «mf-winter» ist am «24.10.2026» angelegt
    Und im Mandanten «mf-winter» wurde das Dokument «D1» am «25.10.2026» um «02:30» archiviert, aufzubewahren bis «31.12.2036»
    Wenn der Manifest-Lauf für «mf-winter» bis «26.10.2026» läuft
    Dann hat «mf-winter» genau je ein Manifest für die Tage vom «24.10.2026» bis «26.10.2026»
    Und das Fenster des Manifests «25.10.2026» von «mf-winter» umfasst 25 Stunden
    Und das Manifest «25.10.2026» von «mf-winter» enthält das Dokument «D1» mit den Varianten «original,arbeitsfassung» samt SHA-256 und Versions-ID

  @req-15.11
  Szenario: Fällt der zweite Dienst aus, gilt das Manifest nach dem letzten Versuch mit dem Zeitstempel des Hauptdiensts
    Angenommen der Mandant «mf-zweit» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-zweit» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und der Zeitstempeldienst «zweit» antwortet mit HTTP 503
    Wenn der Manifest-Lauf für «mf-zweit» bis «11.01.2026» bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FERTIG» nach 3 Versuchen
    Und hat «mf-zweit» genau je ein Manifest für die Tage vom «10.01.2026» bis «10.01.2026»
    Und den Tag «11.01.2026» holt für «mf-zweit» ein Folgeauftrag mit neuen Versuchen
    Und das Manifest «10.01.2026» von «mf-zweit» trägt nur einen Zeitstempel von «haupt»
    Und das Prüfwerkzeug meldet für «mf-zweit» vom «10.01.2026» bis «10.01.2026» «Ergebnis: gültig» mit Exit-Code 0
    Und die Ausgabe des Prüfwerkzeugs nennt «MELDUNG 2026-01-10: Zeitstempel von zweit fehlt»

  @req-15.8 @req-15.11 @req-16.5 @req-16.7
  Szenario: Ohne jeden Zeitstempel fehlt das Manifest, und der Inhaber wird alarmiert
    Angenommen der Mandant «mf-alarm» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-alarm» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und der Zeitstempeldienst «haupt» antwortet mit HTTP 503
    Und der Zeitstempeldienst «zweit» antwortet mit HTTP 503
    Wenn der Manifest-Lauf für «mf-alarm» bis «10.01.2026» bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FEHLER» nach 3 Versuchen
    Und «mf-alarm» hat kein Manifest
    Wenn die Überwachung läuft
    Und die Überwachung läuft
    Dann hat der Inhaber genau eine Mail zum fehlenden Manifest «10.01.2026» von «mf-alarm» erhalten, die nur Mandant, Datum und Status nennt
    Und das Prüfwerkzeug meldet für «mf-alarm» vom «10.01.2026» bis «10.01.2026» «Ergebnis: Fehler gefunden» mit Exit-Code 1

  @req-15.1
  Szenario: Zwei Mandanten am selben Tag erhalten getrennte Manifeste ohne fremde Dokumente
    Angenommen der Mandant «mf-trenn-a» ist am «10.01.2026» angelegt
    Und der Mandant «mf-trenn-b» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-trenn-a» wurde das Dokument «A1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und im Mandanten «mf-trenn-b» wurde das Dokument «B1» am «10.01.2026» um «09:20» archiviert, aufzubewahren bis «31.12.2036»
    Und im Mandanten «mf-trenn-b» wurde das Dokument «B2» am «10.01.2026» um «11:00» archiviert, aufzubewahren bis «31.12.2036»
    Und im Mandanten «mf-trenn-b» steht am «10.01.2026» um «10:00» ein Eintrag im Zugriffsprotokoll
    Wenn der Manifest-Lauf für «mf-trenn-a» bis «10.01.2026» läuft
    Und der Manifest-Lauf für «mf-trenn-b» bis «10.01.2026» läuft
    Dann das Manifest «10.01.2026» von «mf-trenn-a» nennt nur den eigenen Mandanten und genau seine Dokumente
    Und das Manifest «10.01.2026» von «mf-trenn-b» nennt nur den eigenen Mandanten und genau seine Dokumente
    Und das Manifest «10.01.2026» von «mf-trenn-a» enthält den Hash von 0 Zeile(n) aus «zugriff_protokoll» und 0 Zeile(n) aus «dokument_version»
    Und das Manifest «10.01.2026» von «mf-trenn-b» enthält den Hash von 1 Zeile(n) aus «zugriff_protokoll» und 0 Zeile(n) aus «dokument_version»

  @req-15.11 @req-15.12
  Szenario: Fehlen nach dem letzten Versuch nur die Prüfdaten des zweiten Diensts, gilt das Manifest mit dem Hauptdienst
    Angenommen der Mandant «mf-pruefdaten» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-pruefdaten» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Und der Pfad «/zweit/crl» des Zeitstempeldiensts antwortet mit HTTP 503
    Wenn der Manifest-Lauf für «mf-pruefdaten» bis «10.01.2026» bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FERTIG» nach 3 Versuchen
    Und das Manifest «10.01.2026» von «mf-pruefdaten» hält nur den Zeitstempel von «haupt» fest, unter «manifest/» liegt «zweit.tsr», aber kein «zweit.crl.der»
    Und das Prüfwerkzeug meldet für «mf-pruefdaten» vom «10.01.2026» bis «10.01.2026» «Ergebnis: gültig» mit Exit-Code 0
    Und die Ausgabe des Prüfwerkzeugs nennt «MELDUNG 2026-01-10: Zeitstempel von zweit unvollständig (Prüfdaten fehlen), nicht festgehalten»

  @req-15.12 @req-6.11
  Szenario: Eine weitere Version unter einem Manifest-Schlüssel meldet das Prüfwerkzeug als Fehler
    Angenommen der Mandant «mf-version» ist am «10.01.2026» angelegt
    Und im Mandanten «mf-version» wurde das Dokument «D1» am «10.01.2026» um «09:15» archiviert, aufzubewahren bis «31.12.2036»
    Wenn der Manifest-Lauf für «mf-version» bis «10.01.2026» läuft
    Und unter «manifest/2026-01-10.json» von «mf-version» eine weitere Version abgelegt wird
    Dann das Prüfwerkzeug meldet für «mf-version» vom «10.01.2026» bis «10.01.2026» «Ergebnis: Fehler gefunden» mit Exit-Code 1
    Und die Ausgabe des Prüfwerkzeugs nennt «weitere Version(en) oder Löschmarke(n); geprüft wird die älteste»
