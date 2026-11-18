# Verfahrensbeschreibung «Erfassen und Archivieren»

> **Entwurf, ungeprüft – nicht freigegeben.** Freigabe durch Fabian und Gegenlesen durch den Treuhänder stehen aus. Gültig ab: `<…>`.

## Zweck, Geltungsbereich und Rollen

Diese Beschreibung fasst den in Spec 01 festgelegten Ablauf vom Eingang eines Belegs bis zu Ablage,
Prüfung und Wiederherstellung zusammen. Sie ist erst nach Prüfung und Freigabe verbindlich.
Das Archiv speichert eine digitale Kopie; Papieroriginale werden nach dem Scan aufbewahrt und nicht
vernichtet (Req 16.6; Konzeptentscheid E5).
Dokumente der Art «WISSENSQUELLE» und Wissensseiten sind keine Buchungsbelege oder Geschäftsbücher nach GeBüV und
nicht aufbewahrungspflichtig; sie werden ohne Sperre abgelegt und gehören nicht in den Jahresexport (Spec 04, 1.3, 1.6).

| Rolle | Aufgabe und Grenze |
| --- | --- |
| Inhaber | Erfasst, prüft, bestätigt, korrigiert und verwirft; alleinige Einsicht ins Zugriffsprotokoll (Req 2.6, A1–A14). |
| Mitarbeitende | Erfassen, sehen eigene unbestätigte Dokumente und lesen bestätigte Dokumente der Vertraulichkeit NORMAL; ändern, bestätigen und verwerfen nicht (Req 2.6, A1–A9). |
| Geschäftsstelle | Bearbeitet Dokumente im eigenen Mandanten gemäss A1–A10. Die Rechte A11–A14 sind offen; bis zum Entscheid 03-zweiter-mandant gilt 403 (Req 2.6). |
| Treuhänder | Liest Dokumente, ändert sie nicht. Notfallablauf (Vorschlag, zu klären): Meldung an `<Kontakt/Kontaktweg>`, Wiederherstellung durch `<beauftragte Rolle>`. Die Spec legt die Rollenverteilung nicht fest (Req 2.4: Treuhänder schreibt nicht; Req 16.6: Notfallablauf für den Treuhänder). |
| Revisionsstelle | In Req 2 nicht als Systemrolle definiert; Zugang und Umfang legt `<Inhaber>` fest. |

## Ablauf und Nachweis

| Schritt | Wer und was | Nachweis im Archiv |
| --- | --- | --- |
| 1. Eingang | Berechtigte Person lädt Dateien hoch oder erfasst Seiten. Scan: ganze Seite, lesbar, mindestens 300 dpi; Papieroriginal bleibt erhalten. | SHA-256 und Zeitpunkt werden vor weiterer Verarbeitung gespeichert; Dokument-ID und Eingangsstatus (Req 3, 4, 6.1, 16.6). |
| 2. Verarbeitung | Worker erzeugt PDF/A und Volltext. KI-Vorschläge sind zu prüfen, keine Bestätigung; unsichere Felder sind markiert. | Status, Vorschläge, Volltext und gegebenenfalls Fehlertext (Req 5, 7, 8.1). |
| 3. Prüfung | Berechtigte Person prüft und korrigiert Metadaten, Vertraulichkeit und Pflichtfelder und bestätigt. Solange die Art nicht bestätigt ist, gilt das Dokument als PERSONAL und ist nur für Rollen mit Bestätigungsrecht (A7) und den Erfasser sichtbar (Req 2.21). | Pflichtfelder und Aufbewahrung werden geprüft; Status ARCHIVIERUNG. Ohne Pflichtfelder keine Bestätigung (Req 2.6, A5–A8; Req 8.1–8.3, 9.3). |
| 4. Archivierung | Archivauftrag legt Original, Arbeitsfassung und PDF/A ab; bei `gesetzliche_pflicht = ja` mit Sperre bis `aufbewahren_bis`; ohne gesetzliche Pflicht liegt die Datei ohne Sperre unter `archiv/` (Req 6.12). | Prüfsummenvergleich und S3-Versions-IDs; Status ARCHIVIERT erst nach erfolgreicher Ablage und, falls verlangt, Sperre (Req 6.3–6.5, 6.12). |
| 5. Korrektur | Berechtigte Person ändert nur Metadaten; bei archivierten Dokumenten ist ein Grund erforderlich. | Vorherige Fassung mit Person, Zeit und Grund in `dokument_version`; Sperren werden nicht verkürzt (Req 9.7, 9.10, 10.1). |

## Zugriffe, Kontrollen und Aufbewahrung

> Zielpfad und Umfang: Req 16.6 verlangt `docs/verfahrensbeschreibung.md` mit höchstens 2 Seiten. Beides ist bei der Freigabe herzustellen; dieser Abschnitt trägt den in tasks.md 4.9 genannten Abschnitt «Zugriffe».

**Grenzen der Unveränderbarkeit.** Eine Sperre besteht nur bei gesetzlicher Pflicht (Req 6.12). Dokumente unter `archiv/` sind im Modus GOVERNANCE gesperrt; das schützt vor Anwendungsfehlern und Versehen, nicht vor einem Eingriff mit dem Schlüssel der Anwendung, der das Bypass-Recht hat. Nur Manifeste sind im Modus COMPLIANCE gesperrt (Req 6.5, 6.11). Ohne Sperre oder bei Umgehung fällt eine Manipulation nur über Manifest und SHA-256-Prüfsumme im Prüflauf auf (Req 6.11, 15.5, 15.10).

Das Archiv erzeugt täglich ein Manifest mit den seit dem letzten Manifest archivierten Dateien. Es enthält
Prüfsummen, ist verkettet, signiert, zeitgestempelt und gesperrt. Der monatliche Prüflauf vergleicht Dateien,
Datenbank und Manifest, verlängert nötige Sperren unbefristeter Dokumente und Manifeste und meldet
Abweichungen dem Inhaber. Fehlt ein Manifest, löst das Archiv einen Alarm per E-Mail aus (Req 15.1–15.5, 15.8–15.10, 16.5).

Anzeigen, Herunterladen, Ändern, Bestätigen, Verwerfen und Exportieren werden mit Zeit, Benutzer, Aktion,
Dokument-ID und IP protokolliert; auch verweigerte Zugriffe (Aktion VERWEIGERT, Req 10.7). Ohne Protokolleintrag wird die Aktion nicht ausgeführt (Req 10.8). Das nur anfügende Benutzerverzeichnis ordnet die Kennung `benutzer` einer Person zu (Req 10.10). Das Zugriffsprotokoll und die Dokumentversionen sind nur anfügbar. Der
Inhaber allein darf das Zugriffsprotokoll einsehen; es dient dem Nachweis und der Datensicherheit, nicht der
Verhaltensauswertung. Ein Protokolleintrag bleibt mindestens bis zum späteren Datum aus
`aufbewahren_bis` eines bestätigten pflichtigen oder gesperrten Dokuments und dem Ende des Geschäftsjahres
des Eintrags plus zehn Jahre; bei unbefristeten Dokumenten solange das Dokument besteht. Ohne solche
Dokumentbindung gilt die Frist «Geschäftsjahresende plus zehn Jahre». Dokumentversionen bleiben so lange
wie das Dokument. In dieser Spec werden Protokolleinträge weder gelöscht noch pseudonymisiert (Req 2.6, A13;
Req 10.2–10.10).

Die Aufbewahrungsfrist wird bei der Bestätigung aus der Regel der Dokumentart und den massgeblichen Daten
berechnet. Bei bereits abgelaufener Frist entscheidet der Inhaber über Archivierung oder Verwerfung.
Nach Fristablauf löscht diese Spec kein Dokument; ein späterer Löschweg ist gesondert geregelt (Req 9.3,
9.8–9.9). Papieroriginale werden nicht vernichtet.

## Ausnahmen und Notfall

- Ungültige oder nicht unterstützte Dateien werden abgelehnt. Nach drei fehlgeschlagenen Verarbeitungsversuchen
  bleibt das Dokument mit Fehlertext im Eingangskorb. Ist keine PDF/A-Fassung möglich, entscheidet der Inhaber,
  ob das Original allein archiviert oder das Dokument verworfen wird (Req 3.3, 3.6–3.7, 5.4, 5.9).
- Verwerfen löscht die Dateien des Dokuments (Req 6.6); in den Status ARCHIVIERUNG, ARCHIVIERUNG_FEHLER und ARCHIVIERT ist es mit 409 gesperrt (Req 6.7).
- Bei einem Archivierungsfehler bleiben bestätigte Metadaten erhalten; die Archivierung kann wiederholt werden.
  Bereits archivierte Dateien werden nicht überschrieben oder durch die Anwendung gelöscht (Req 6.4–6.8).
- Bei einem Ausfall erfolgt die Meldung an `<Kontakt/Kontaktweg>`; wer wiederherstellt, ist offen (`<beauftragte Rolle>`, Vorschlag). Zunächst wird das
  Sicherungsmaterial geprüft und in einer getrennten Testumgebung wiederhergestellt. Erst nach abgeglichenem
  Objektbestand, gültigem Manifest und erfolgreichem Prüflauf wird freigegeben. Danach werden Modus und `retainUntil`
  der Sperren aus dem Inventar neu gesetzt und mit einer Sperrprüfung kontrolliert (Req 16.3, 16.27; [Wiederherstellungsanleitung](../wiederherstellung.md)).
- Ort der versiegelten Notfallunterlagen: `<…>`. Schlüssel und Zugangsdaten stehen nicht in dieser Beschreibung
  und werden nicht im Repository abgelegt (Req 6.10, 16.9, 16.12).

## Änderungen und Freigabe

Änderungen an diesem Verfahren werden als neue Fassung mit Änderungsgrund und «gültig ab» geführt. Vor der
Freigabe prüft der Inhaber den Inhalt; der Treuhänder liest gegen. Jede freigegebene Fassung wird mit «gültig ab»
als PDF/A im Archiv abgelegt. Bis dahin bleibt dieser Entwurf ungeprüft und unverbindlich (Req 16.6; tasks.md 5.7).

## Quellen

- [Spec 01 – Requirements](../../.kiro/specs/01-archiv-kern/requirements.md): Req 2, 3–10, 15 und 16.
- [Spec 01 – Design](../../.kiro/specs/01-archiv-kern/design.md): Dokument, Dokumentart und Aufbewahrung, Protokoll und Manifest, Sicherung und Wiederherstellung.
- [Wiederherstellungsanleitung](../wiederherstellung.md).
