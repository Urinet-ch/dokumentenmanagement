# Messvorbereitung Stufe B: Fotoverarbeitung

Diese Vorbereitung enthält keine Messung. Ausführung und Nachweis nach Spec 01, Req 5.5 und 16.26 sowie
Aufgabe 5.11 bleiben bei Fabian. Es dürfen ausschliesslich synthetische Daten im dafür vorgesehenen,
isolierten Mess-Mandanten verwendet werden; niemals produktive Daten oder ein bestehender Test-Bucket.
Die weiteren Last- und Restore-Nachweise aus 5.11 ersetzt dieser Foto-Test nicht.

## Voraussetzungen und Eingaben

- Dienste und Limits der Stufe B, `archiv.stufe=A` und eigener Mess-Mandant gemäss 16.26; vor dem Start
  Ziel und leere Auftragsschlange prüfen. Kein Zugriff auf produktive Mandanten.
- Synthetisches 12-MP-JPEG (4032 × 3024) und dieselbe Aufnahme als HEIC; Masse, Inhalt und Format separat
  prüfen. Für den Begleitlauf einen synthetischen 20-seitigen Bildscan erzeugen:

  ```sh
  # Python-Umgebung mit Pillow und reportlab gemäss tools/golden_synth/requirements.txt
  python3 tools/last/scan_erzeugen.py /pfad/ausserhalb-des-repos/scan.pdf
  ```

  Der Generator verwendet einfache synthetische Textbilder; ihre Repräsentativität für echte Scans ist zu
  beurteilen. Er überschreibt keine Datei. `pdfinfo` (Poppler) muss installiert sein; es prüft das PDF und
  die Seitenzahl vor dem Upload. Jeder Begleitlauf braucht ein noch nicht hochgeladenes PDF.
- Das Zugriffstoken aus einer Datei mit Rechten 600 lesen (`ARCHIV_TOKEN_DATEI` enthält nur den Dateipfad).
  Es benötigt A1 und A2. Keine Tokens, echten Belege oder personenbezogenen Angaben in Berichten oder Logs.
- KI-Wartezeit darf nach Req 5.5 nicht in den Zielwert eingehen: mit geeignetem KI-Stub messen oder die
  serverseitigen Zeiten einschliesslich KI-Anteil separat erfassen und nachvollziehbar auswerten.
  `ki_verbrauch` enthält keine Aufrufdauer und erlaubt allein keinen Abzug der KI-Wartezeit.

## Ablauf

Das Skript sendet standardmässig 20 Aufwärmläufe und danach 50 Fotos nacheinander. Jeweils JPEG, HEIC und
einen Lauf mit Begleit-Scan erfassen. Beispiel mit Platzhaltern, Ausgabe ausserhalb des Repositories:

```sh
python3 tools/last/verarbeitung_foto.py --basis-url '<mess-url>' --foto /pfad/foto.jpg \
  --csv /pfad/ausserhalb-des-repos/jpeg.csv
python3 tools/last/verarbeitung_foto.py --basis-url '<mess-url>' --foto /pfad/foto.heic \
  --csv /pfad/ausserhalb-des-repos/heic.csv
python3 tools/last/verarbeitung_foto.py --basis-url '<mess-url>' --foto /pfad/foto.jpg \
  --begleit-pdf /pfad/ausserhalb-des-repos/scan.pdf --csv /pfad/ausserhalb-des-repos/begleit.csv
python3 tools/last/auswertung.py /pfad/ausserhalb-des-repos/jpeg.csv
```

Der Begleit-Scan wird vor den Fotoläufen eingereiht und danach bis `EINGANGSKORB` abgefragt. Während der
Fotoläufe zählt das Skript, wie oft es den Scan im Zustand `VERARBEITUNG` beobachtet. Ohne beobachtete
Überlappung endet der Begleitlauf mit Fehler: Das Abfrageintervall kann kurze Überlappungen übersehen.

Die CSV enthält Laufnummer, Status und Dauer; die Textauswertung gibt Anzahl, Fehlerquote sowie p50/p95
nach nächstem Rang aus. Perzentile beziehen sich nur auf erfolgreiche Läufe. Nach einem Abbruch oder
fehlgeschlagenem Begleitlauf ist die CSV allein kein vollständiger Nachweis. Das Skript misst von HTTP 202
bis zum ersten beobachteten `EINGANGSKORB`: nach der Auslegung eine obere Schranke durch Warteschlange und
Abfrageintervall. Beginnt der Worker jedoch schon vor der empfangenen 202-Antwort, kann dieser Clientwert
den serverseitigen Auftragsbeginn unterschätzen; massgeblich bleibt die serverseitige Messung.

## Abbruch und Protokoll

Bei fehlender Isolation, echten Daten, Fehlern, Dubletten, unerreichbarem Dienst, Scan-Fehler, fehlender
beobachteter Überlappung oder Ressourcenalarm abbrechen und Ursache klären. Ein p95 über 30 s verfehlt das
Ziel; eine unvollständige oder fehlerhafte Stichprobe belegt es nicht. Kein automatischer Neustart oder
Aufräumvorgang wird ausgelöst.

Notieren: Commit, Zeitpunkt, Grösse und gemessenes `MemTotal` des Zielhosts, Dienste/Limits, KI-Modus,
Fotoformat und -masse, Scan, Warm-up, Zahl der Läufe, Abfrageintervall, beobachtete Überlappung, p50, p95,
Fehlerquote und Abbrüche sowie den serverseitigen Messpunkt. Ergebnisse und Zugangsdaten getrennt und
geschützt halten; keine Betriebsadressen oder personenbezogenen Daten ins Repository schreiben. Weitere
Messungen, Restore-Probe und Rückbau nach Aufgabe 5.11 erfolgen gesondert durch Fabian.

Synthetische Tests: `python3 -m unittest tools/last/test_last.py`.
