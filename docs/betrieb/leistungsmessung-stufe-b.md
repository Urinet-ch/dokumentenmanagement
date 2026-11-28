# Leistungsmessung Stufe B: Vorbereitungsstand

**Noch nicht ausführungsbereit.** Dieser Stand ist weder eine Freigabe zur Messung noch ein
Betriebsnachweis. Aufgabe 31.6 bleibt offen; Fabian führt Messung und Nachweis durch.
Die bestehende [Foto-Vorbereitung](stufe-b-messung.md) und die Lastskripte sind nur Bausteine.

## Vor der Messung zu ergänzen

- Ein isoliertes Compose-Projekt und sein Deploy über den Workflow fehlen. Es braucht eigene
  Datenbank, Keycloak, Volumes, Netze, Zugang und den Mandanten `last`; es darf weder auf
  Ressourcen des laufenden Archiv-Projekts noch auf dessen Test-Buckets zeigen. Ein manuelles
  `docker compose up` auf der VPS ist nicht zulässig. Der Archiv-Stack ruht während der Messung.
- Der Mess-Bucket `urinet-archiv-a-last` muss separat und löschbar sein; `archiv.stufe=A`,
  Höchstfristen für Archiv und Manifest höchstens ein Tag. Dienste und Limits entsprechen
  Stufe B: insgesamt höchstens 2144 MiB und 1.6 CPU einschliesslich `sftp` (Req 16.30).
  Die tatsächlichen Limits und Dienste der freigegebenen Stufe B sind vor dem Lauf zu prüfen.
- Die Befüllung mit 50'000 synthetischen Dokumenten gemäss Req 14.3 und die prüfbare
  Verteilung von Volltext, Finanzdaten, Vertraulichkeit und Dateinamen fehlen. Das lokale
  Werkzeug [Leistungsbasis](leistungsbasis.md) erzeugt nur 5'000 Dokumente mit anderen
  Bedingungen und ist kein Ersatz. Nach Befüllung `VACUUM ANALYZE` ausführen und die
  Verteilung prüfen.
- Ein nachprüfbarer serverseitiger Messpunkt für Upload, Suche und Verarbeitung fehlt.
  `upload.py` beginnt nach dem Senden des Bodys; der Server kann bereits vorher begonnen
  haben. `suche.py` misst die Clientzeit. `verarbeitung_foto.py` beginnt nach Empfang der
  Upload-Antwort; der Auftrag kann dann bereits laufen. Keine dieser Zeiten ist deshalb ein
  sicherer Ersatz für den geforderten serverseitigen Messpunkt.
- Für Req 5.5 muss die KI-Wartezeit ausgeschlossen werden. `ki_verbrauch` enthält keine Dauer.
  Ein geeigneter KI-Stub oder eine nachprüfbare serverseitige Messung des KI-Anteils ist
  vorzubereiten. Die OCR muss echt laufen. Ein synthetisches HEIC derselben Aufnahme sowie
  der dreiseitige farbige Bildweg nach Aufgabe 5.11 müssen vorbereitet und geprüft werden.
- Der Nachweis, dass bei den Suchläufen jeweils tatsächlich ein OCR-Auftrag läuft, fehlt.
  Ebenso fehlt der automatisierte Bildweg-Nachweis (Seitenbild ja/nein und Renderdauer).

## Messbedingungen für die spätere Durchführung

Nur synthetische Daten verwenden, nie echte Belege, produktive Mandanten oder einen bestehenden
Test-Bucket. Messung vor dem Stichtag auf derselben VPS im eigenen Projekt; Ergebnisse und
Zugangsdaten geschützt ausserhalb des Repositories aufbewahren. Keine Betriebsadressen oder
Geheimnisse in das Repository schreiben.

- **Upload, Req 3.2:** 200 PDFs zu 200'000 Bytes mit vier parallelen Uploads und 50 PDFs zu
  10'000'000 Bytes mit zwei parallelen Uploads, jeweils 20 Aufwärm-Uploads; der Worker
  verarbeitet parallel. Ziel: HTTP 202 innert 2 Sekunden, gemessen serverseitig ab vollständig
  empfangener Anfrage. `tools/last/upload.py` liefert Clientwerte, nicht den Servernachweis.
- **Foto, Req 5.5:** synthetisches einseitiges JPEG mit 4032 × 3024 Pixeln, 50 aufeinanderfolgende
  Läufe bei sonst leerer Auftragsschlange, zusätzlich dieselbe Aufnahme als HEIC und Läufe neben
  einem 20-seitigen Scan; jeweils 20 Aufwärmläufe. Ziel: p95 höchstens 30 Sekunden ab
  Auftragsstart bis `EINGANGSKORB`, ohne KI-Wartezeit.
- **Volltextsuche, Req 14.3:** 50'000 Dokumente, im Mittel rund 1'500 Zeichen Volltext, 5 %
  mit über 20'000 Zeichen, ein Drittel mit Finanzdaten, nach `VACUUM ANALYZE`. Je Rolle
  Inhaber, Mitarbeiter und Treuhänder 500 Suchen mit vier gleichzeitigen Anfragen, einmal ohne
  und einmal mit laufendem OCR-Auftrag; jeweils 20 Aufwärmsuchen. Ziel: p95 höchstens eine
  Sekunde am Server. Suchbegriffe müssen auch ausschliesslich Dateinamen treffen.
- **Hybrid:** Aufgabe 5.11 verlangt dieselben Rollen- und OCR-Bedingungen auch für die
  Hybridsuche, Ziel p95 höchstens zwei Sekunden (Spec 02, Req 2.6).

Die Werkzeuge `upload.py`, `suche.py` und `verarbeitung_foto.py` erzeugen Last und werten
Clientzeiten aus; ein fehlgeschlagener Lauf oder unbestätigte Messbedingungen sind kein Nachweis.
Die Spec nennt keinen eigenen Grenzwert für Durchsatz oder isolierte OCR-Dauer; diese Zahlen
dürfen dokumentiert, aber nicht als zusätzliche Bestehenskriterien erfunden werden.

## Überwachung, Entscheid und Rückbau

Vor und während der Messung Limits, Speicherabbrüche auf der VPS und Health der anderen Apps
prüfen. Bei Budgetüberschreitung, nicht manuell verursachtem Speicherabbruch oder kranker anderer
App gilt Req 16.18: Archiv auf Stufe A stoppen und Fabian informieren; der Wechsel ist erst nach
seinem Entscheid über Budget oder Plattform zulässig. Ein durch manuellen Eingriff verursachter
Speicherabbruch wird mit Datum dokumentiert, löst das Kriterium aber nicht aus. Skripte ersetzen
diese Überwachung nicht.

Ein **verfehltes Leistungsziel allein** löst keinen automatischen Stopp und keine Aufrüstung aus:
Ergebnis Fabian als Entscheid vorlegen (Req 16.17, 16.26). Messbericht: Commit, VPS-Grösse,
gemessenes `MemTotal`, Dienste und Limits, Messbedingungen, Zahl und Verteilung der Dokumente,
Zeiten, Fehler und Abbrüche; keine Inhalte oder Zugangsdaten.

Nach dem Lauf nur das eindeutig identifizierte Messprojekt und seine Messdaten entfernen.
Den Mess-Bucket frühestens einen Tag nach dem letzten Upload mit dem App-Schlüssel und ohne
Bypass-Header von allen Versionen leeren und löschen; Sperren nicht verkürzen. Keine Volumes
oder Buckets anderer Projekte entfernen. Fabian dokumentiert den Rückbau und den Messnachweis.
