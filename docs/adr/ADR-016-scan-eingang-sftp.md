# ADR-016: Scan-Eingang per SFTP in eigenem, gehärtetem Dienst

**Status:** Vorgeschlagen – Freigabe per Merge des Spec-PR von Spec 09.

**Datum:** 28.11.2026

## Kontext
- Papier kommt über einen Netzwerkscanner im Heimnetz ins Archiv (Spec 09, Requirement 1 und 2). Das Gerät kann Dateien
  per SFTP, E-Mail oder in einen Netzwerkordner liefern; die Logik (Trennen, OCR, Erkennung) soll im Archiv liegen.
- Alle Dienste laufen mit `cap_drop: ALL`, `no-new-privileges`, numerischer uid ungleich 0 und `read_only`;
  `cap_add`, `privileged` und `ports` sind verboten (`DeployHaertungTest`, `tools/pruefe_compose_konsistenz.py`,
  Compose-Policy der VPS). Die Compose-Policy erlaubt nur die EntryPoints `web` und `websecure`.
- OpenSSH braucht für `ChrootDirectory` und den Benutzerwechsel Root und Capabilities.
- Der E-Mail-Eingang (Spec 02, Requirement 1) besteht schon.

## Entscheid
- Der Scan-Eingang ist ein eigener kleiner Dienst `sftp` auf Basis Apache MINA SSHD (Java, eigenes Image nach dem
  Muster von `db` und `kc`): nur SFTP, nur Schlüssel, keine Shell, kein Befehl, keine Weiterleitung, virtuelle Wurzel je
  Mandant, nur Schreiben neuer Dateien, kein Löschen, kein Lesen fremder Dateien, Sperre je Quelladresse.
- Der Dienst hat keine Secrets der API, hängt am internen Netz (für E10 zusätzlich am Netz «web», siehe unten) und
  teilt mit `api` nur das flüchtige Volume `archiv_eingang`. Den Mandanten bestimmt die Zuordnung
  Benutzerverzeichnis → Mandant des Deploys.
- Ältere SSH-Verfahren, die das Gerät braucht, gibt es nur in diesem Dienst und nur in der Liste aus dem Spike.
- Der Weg nach aussen (E10) wird im Infra-Repo der VPS eingerichtet, als TCP-Eingang von Traefik mit
  IP-Freigabe für den Heimanschluss; die App-Compose veröffentlicht keinen Port (entschieden von Fabian am
  28.11.2026, Variante (a)). Dafür hängt der Dienst zusätzlich am Netz «web», nur für den TCP-Router von Traefik.

## Alternativen
- OpenSSH mit Chroot: verletzt die Härtung. SFTPGo: läuft ohne Root, bringt aber viele abzuschaltende Funktionen und ein
  Fremd-Image. Nur E-Mail: kein neuer Eingang, aber Grössengrenze des Postfachs und Abhängigkeit vom Mailserver.
  Netzwerkordner auf dem Mac: kein neuer Eingang, aber der Mac muss laufen.

## Folgen
- Neue Angriffsfläche von aussen; Bedrohungsmodell in Spec 09, design.md. Ohne Freigabe E10 bleibt der Rückfall E6.
- Speicherbudget Stufe A muss den Dienst aufnehmen (E11, entschieden von Fabian am 28.11.2026: +96 MiB und
  +0.1 CPU, neu 2144 MiB und 1.6 CPU).
- Der Deploy-Workflow baut ein weiteres Image; die Änderung übernimmt Fabian.

## Verweise
Spec 09, Requirement 0 bis 2, design.md Abschnitt 1; ADR-010; ADR-011.
