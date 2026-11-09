# ADR-004: Zweistufige Ablage – `eingang/` ohne Sperre, `archiv/` mit Sperre nur bei gesetzlicher Pflicht

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026). Vorbehalt: Spike 0.2 (Object Lock pro Objekt, Löschbarkeit ungesperrter Objekte im selben Bucket) – durchgeführt 08.11.2026, Sperre pro Objekt und Löschbarkeit bestätigt [T 3a, 5] (`docs/spikes/s3-object-lock.md`); Rückfall Punkt 8 nicht nötig. Der Modus der Sperre ist im Nachtrag 09.11.2026 von ADR-007 entschieden: Option B (GOVERNANCE für `archiv/`, COMPLIANCE für `manifest/`; Fabian, 09.11.2026).

**Datum:** 08.11.2026

## Kontext
- Art. 958f OR und GeBüV verlangen, dass archivierte Belege unveränderbar abgelegt sind (Req 6). Gleichzeitig müssen Fehlerfassungen vor der Bestätigung löschbar bleiben (Req 6.2, 6.6).
- Die Frist `aufbewahren_bis` steht erst nach der Bestätigung im Eingangskorb fest (Art, Belegdatum, Geschäftsjahr, Req 9.3). Eine Sperre beim Eingang hätte noch kein richtiges Ablaufdatum.
- Nicht jede Dokumentart unterliegt einer gesetzlichen Aufbewahrungspflicht (P12: Attribut `gesetzliche_pflicht`, WORM nur bei «ja»; z. B. `PROJEKTDOKUMENT`, `KORRESPONDENZ`). Solche Dokumente sollen über die spätere Löschfunktion mit Protokoll löschbar bleiben (tech.md Regel 2).
- Der Bucket je Mandant (`urinet-archiv-<code>`) ist versioniert. Eine Standard-Aufbewahrung des Buckets würde jedes neue Objekt sperren, also auch den Eingang.
- Ob Infomaniak Object Lock pro Objekt unterstützt und ungesperrte Objekte im selben Bucket löschbar bleiben, hat der Spike Object Storage (Aufgabe 0.2, Req 0.4) geklärt: beides ja [T 3a, 5].

## Entscheid
1. **Ein Bucket je Mandant** mit `ObjectLockEnabledForBucket=true` und Versionierung, **ohne Standard-Aufbewahrung**; gesperrt wird nur pro Objekt.
2. **Präfixe:** `eingang/` (ohne Sperre, bis zur Archivierung), `archiv/` (archivierte Dateien), `vorschau/` (ohne Sperre, regenerierbar), `importe/`, `manifest/` (Sperre nach ADR-007 und Req 15.4). Schlüssel nach design.md, Abschnitt «Ablage».
3. **Archivieren** (Auftrag `ARCHIVIEREN`, Req 6.3), je Datei (Originale, Arbeitsfassungen, PDF/A):
   CopyObject `eingang/…` → `archiv/…` und Versions-ID speichern → SHA-256 vergleichen (`x-amz-checksum-sha256`, sonst Kopie über die Versions-ID neu lesen) → nur bei `gesetzliche_pflicht` Sperre setzen und per `GetObjectRetention` kontrollieren (Modus nach ADR-007).
   Erst wenn alle Dateien kopiert, geprüft und gegebenenfalls gesperrt sind: Status `ARCHIVIERT`; danach die `eingang/`-Objekte mit **allen Versionen** löschen.
4. **Ablaufdatum der Sperre** (`retainUntil`), in dieser Reihenfolge: Art ohne `gesetzliche_pflicht` → keine Sperre; «Frist abgelaufen» → keine Sperre (Req 9.8); unbefristet → heute + 10 Jahre, der Prüflauf verlängert (Req 6.9, 15.9); sonst Ende des Tages `aufbewahren_bis` (Europe/Zurich, als UTC).
5. **Arten ohne gesetzliche Pflicht** liegen ebenfalls unter `archiv/`, ohne Sperre; Prüfsummen, Manifest und das Verbot von Überschreiben und Löschen über die Schnittstellen gelten trotzdem (Req 6.5, 6.12).
6. **Fehler:** abweichende Prüfsumme oder nicht gesetzte Sperre → sofort `ARCHIVIERUNG_FEHLER`, bestätigte Metadaten bleiben, Inhaber wird benachrichtigt (Req 6.4). Ein neuer Versuch übernimmt eine korrekte Kopie und löscht eine fehlerhafte, ungesperrte Kopie über ihre Versions-ID (Req 15.10).
7. **Verwerfen** nur aus `EINGANGSKORB` und `FEHLER`: alle Versionen unter `eingang/` und `vorschau/` löschen, SHA-256 und Grund protokollieren (Req 6.6). Ab `ARCHIVIERUNG` antwortet das Archiv 409 (Req 6.7).
8. **Rückfall**, falls der Spike keine Sperre pro Objekt bestätigt: getrennte Buckets `…-eingang` (ohne Sperre), `…-archiv` (Standard-Aufbewahrung 10 Jahre) und `…-ablage` für Arten ohne `gesetzliche_pflicht`. Der `AblagePort` (ADR-011) bliebe gleich, nur der Adapter änderte sich. **Nicht nötig:** Spike 0.2 hat die Sperre pro Objekt bestätigt [T 3a, 5]; der Rückfall entfällt (design.md, Open Points).

## Betrachtete Alternativen
- **Sofort gesperrt ablegen beim Eingang:** abgelehnt. Fehlerfassungen wären nicht mehr löschbar (Req 6.2), und das Ablaufdatum der Sperre ist vor der Bestätigung unbekannt.
- **Bucket mit Standard-Aufbewahrung:** abgelehnt. Damit wäre auch `eingang/` gesperrt (design.md, «Ablage»). Der Rückfall dafür entfällt laut Spike 0.2 [T 3a, 5].
- **Sperre für alle Arten:** abgelehnt nach P12. Dokumente ohne gesetzliche Pflicht wären bis zum Ablauf nicht löschbar, auch nicht über die spätere Löschfunktion.
- **Eingang ausserhalb des Object Storage** (Dateisystem der VPS): abgelehnt. API und Worker laufen in Stufe B in getrennten Containern und bräuchten einen gemeinsamen Speicher. Die VPS der Stufe A hat wenig freie Disk, und `archivieren` wäre keine Kopie innerhalb eines Speichers mit Prüfsumme mehr.
- **Getrennte Buckets von Anfang an:** nicht gewählt. Mehr Buckets je Mandant für Provisionierung, Rechte und Sicherung, ohne Mehrwert, solange die Sperre pro Objekt funktioniert. Der Rückfall entfällt laut Spike 0.2 [T 3a, 5].

## Folgen
- **Positiv:** Fehlerfassungen bleiben löschbar; die Grenze zwischen «noch korrigierbar» und «unveränderbar» ist eindeutig am Präfix und am Status `ARCHIVIERT` erkennbar. `ARCHIVIERT` heisst immer: kopiert, geprüft und, bei Pflicht, gesperrt.
- **Positiv:** Ein einziger Bucket je Mandant passt zur Mandantentrennung (ADR-002) und hält Provisionierung und Sicherung (ADR-008) einfach.
- **Negativ:** Jede Datei wird einmal kopiert. Wegen der Versionierung muss das Löschen unter `eingang/` alle Versionen erfassen, sonst bleiben Altversionen liegen.
- **Negativ:** Unter `archiv/` dürfen keine fremden Versionen entstehen. Der Prüflauf meldet fremde Versionen und Löschmarken als Abweichung (Req 15.10).
- **Umsetzung:** `AblageService` mit Port `AblagePort`, S3-Adapter und Dev-Stub (Aufgabe 2.3); Bestätigen, Archivieren, Verwerfen und Wiederholen in Aufgabe 3.4. Sperre bei Metadatenänderung nur verlängern (Req 9.7, ADR-007).
- **Tests:** Testcontainers MinIO mit Object Lock und Versionierung: archivierte Datei nicht löschbar (unter Option B: ohne Bypass-Header, siehe ADR-007, Nachtrag 09.11.2026), Abweichung der Prüfsumme bricht ab, Art ohne Pflicht ohne Sperre, Verwerfen löscht alle Versionen. Cucumber-Szenarien zu Req 6.x.
- **Betrieb:** Gesperrte Testobjekte der Stufe A lassen sich mit dem App-Schlüssel nicht löschen (ohne Bypass-Header; mit Option B (entschieden 09.11.2026, ADR-007) hat der App-Schlüssel technisch das Bypass-Recht, Spike 0.2 [T 6b–7g]) (siehe ADR-007, Nachtrag 09.11.2026: die Anwendung setzt den Header nie, Req 6.5; Manifeste in COMPLIANCE sind mit keinem Schlüssel des Projekts löschbar [T 4b/4c], darum darf der Sperrmodus von `manifest/` auf Stufe A per Konfiguration abweichen, Req 6.11, Aufgabe 2.3): [offen: Bucket-Namen der Stufe A und Umgang mit gesperrten Testobjekten beim Wechsel – Entscheid vor Welle 1].

## Verweise
- Spike 0.2 (Vorbehalt – durchgeführt 08.11.2026): `docs/spikes/s3-object-lock.md` (Sperre pro Objekt, Löschbarkeit ungesperrter Objekte), Modus der Sperre im Nachtrag 09.11.2026 von ADR-007
- Req 0.4, 6.1–6.9, 6.12, 6.13, 9.3, 9.7, 9.8, 15.10
- P2, P12
- Aufgaben 0.2, 2.3, 3.4, 5.3
- design.md: «Ablage», «Statusmodell», «Open Points»; tech.md Regel 2
- ADR-002 (Mandantentrennung), ADR-007 (Modus der Sperre, Schlüssel), ADR-008 (Sicherung des Buckets), ADR-011 (Driven Ports)
