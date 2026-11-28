# language: de
@spec-09
Funktionalität: Scan-Eingang per SFTP (Prototyp des Dienstes «sftp»)
  Der Dienst aus deploy/infomaniak/sftp läuft in-process an einem freien Port, mit Schlüsseln, die nur für den Test
  erzeugt werden (Aufgabe 27.3). Die Rechte im Eingang (1.4), die Sperre je Quelladresse (1.6) und die
  Secret-Dateien im Deploy (1.10) folgen mit Aufgabe 28.2; Agent- und X11-Weiterleitung prüft 28.2 mit.

  Hintergrund:
    Angenommen der Scan-Eingang läuft mit den Benutzern «scan-urinet» und «scan-vhf-gsk»

  @req-1.2
  Szenario: Anmeldung nur mit dem eigenen öffentlichen Schlüssel
    Wenn sich «scan-urinet» mit seinem Schlüssel anmeldet
    Dann ist die Anmeldung angenommen
    Wenn sich «scan-urinet» mit dem Schlüssel von «scan-vhf-gsk» anmeldet
    Dann ist die Anmeldung abgelehnt
    Wenn sich «scan-urinet» mit einem Passwort anmeldet
    Dann ist die Anmeldung abgelehnt
    Wenn sich «scan-urinet» mit Keyboard-Interactive anmeldet
    Dann ist die Anmeldung abgelehnt
    Und das Log des Scan-Eingangs nennt für «scan-urinet» eine angenommene und eine abgelehnte Anmeldung

  @req-1.3
  Szenario: Nur das Subsystem SFTP, keine Shell, kein Befehl, keine Weiterleitung
    Wenn sich «scan-urinet» mit seinem Schlüssel anmeldet
    Dann lehnt der Scan-Eingang eine Shell ab
    Und lehnt der Scan-Eingang den Befehl «ls /» ab
    Und lehnt der Scan-Eingang eine Weiterleitung von Ports ab

  @req-1.3
  Szenario: Upload nur in die eigene Wurzel mit den Unterordnern des Scanners
    Wenn sich «scan-urinet» mit seinem Schlüssel anmeldet
    Dann zeigt die Wurzel von «scan-urinet» nur die Ordner «einzeln, glas, stapel»
    Wenn «scan-urinet» die Datei «/stapel/scan-0001.pdf» hochlädt
    Dann liegt die Datei «scan-0001.pdf» im Ordner «stapel» von «scan-urinet»
    Und das Log des Scan-Eingangs nennt die Datei «scan-0001.pdf» im Unterordner «stapel» mit ihrer Grösse
    Wenn «scan-urinet» die Datei «/../scan-vhf-gsk/stapel/fremd.pdf» hochzuladen versucht
    Dann liegt im Eingang von «scan-vhf-gsk» keine Datei
