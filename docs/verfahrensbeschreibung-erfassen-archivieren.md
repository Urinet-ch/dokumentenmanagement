# Verfahrensbeschreibung «Erfassen und Archivieren»

**Entwurf, von Fabian zu prüfen und freizugeben.** Noch nicht verbindlich; Gegenlesen durch den Treuhänder
und Festlegung «gültig ab» stehen aus. Dieser Entwurf ist keine rechtliche Zusicherung. Die vorgeschriebene
freigegebene Fassung soll höchstens zwei Seiten umfassen und als PDF/A im Archiv liegen
([Spec 01, Req 16.6](../.kiro/specs/01-archiv-kern/requirements.md#requirement-16-betrieb-sicherung-und-dokumentation-ohne-konzept-id-nicht-funktional),
[Aufgabe 5.7](../.kiro/specs/01-archiv-kern/tasks.md)).

## Eingänge und Erfassen

Berechtigte Personen laden digitale Dateien hoch oder erfassen Papier mit dem Handy. Der Scan soll die ganze Seite
lesbar und mit mindestens 300 dpi zeigen. Papieroriginale werden aufbewahrt und nicht vernichtet; das Archiv ist
eine Kopie. Für jede empfangene Datei sind SHA-256 und Empfangszeit festzuhalten; Dubletten werden je Mandant
geprüft ([Spec 01, Req 3.1–3.4, 4.2–4.7, 6.1, 16.6](../.kiro/specs/01-archiv-kern/requirements.md),
[Upload-Code](../backend/src/main/java/ch/urinet/archiv/dokument/adapter/in/rest/DokumentUploadResource.java)).

## Verarbeitung und Prüfung

Der Worker verarbeitet die Dateien zu PDF/A und Volltext. Kann keine PDF/A-Fassung erstellt werden, entscheidet
der Inhaber über Archivierung des Originals oder Verwerfen. Bei Verarbeitungsfehlern sind bis zu drei Versuche
vorgesehen; das Original bleibt erhalten ([Spec 01, Req 5.1–5.4, 5.9](../.kiro/specs/01-archiv-kern/requirements.md),
[Verarbeitungs-Code](../backend/src/main/java/ch/urinet/archiv/verarbeitung/application/VerarbeitenHandler.java)).

Im Eingangskorb prüft eine berechtigte Person Vorschläge, Dokumentart und Pflichtfelder. Bestätigen berechnet die
Aufbewahrungsfrist und reiht die Archivierung ein; fehlende Pflichtfelder verhindern die Bestätigung. Solange die
Art nicht bestätigt ist, gilt das Dokument als `PERSONAL` und ist nur nach der dafür geltenden Sichtbarkeitsregel
zugänglich ([Spec 01, Req 2.21, 8.1–8.3, 9.3](../.kiro/specs/01-archiv-kern/requirements.md)).

## Archivieren und Aufbewahren

Der Archivierungsauftrag legt die vorgesehenen Fassungen ab; `ARCHIVIERT` wird erst nach erfolgreicher Ablage
und gegebenenfalls Sperre gesetzt. Für gesetzlich aufbewahrungspflichtige Arten gelten Sperren; andere Arten
werden ohne diese Pflichtsperre abgelegt. Bestehende Sperren werden nicht verkürzt
([Spec 01, Req 6.3–6.5, 6.12, 9.7](../.kiro/specs/01-archiv-kern/requirements.md),
[Archivierungs-Code](../backend/src/main/java/ch/urinet/archiv/verarbeitung/application/ArchivierenHandler.java)).

Die Frist hängt von Dokumentart und massgeblichen Daten ab; unbefristete Dokumente erhalten kein Enddatum.
Nach Fristablauf löscht diese Spec nicht automatisch. Wissensquellen und Wissensseiten sind nach Spec 04 keine
Buchungsbelege oder Geschäftsbücher nach GeBüV und nicht aufbewahrungspflichtig
([Spec 01, Req 9.3 und 9.9](../.kiro/specs/01-archiv-kern/requirements.md),
[Spec 04, Req 1.6](../.kiro/specs/04-wissensbasis/requirements.md)).

## Rollen und Protokollierung

Die Rechtematrix unterscheidet Inhaber, Mitarbeitende, Treuhänder und Geschäftsstelle. Entscheidend sind Mandant,
Vertraulichkeit und konkrete Aktion; Treuhänder dürfen lesen, aber nicht bestätigen oder ändern. Details und Ausnahmen
stehen in der verbindlichen Matrix ([Spec 01, Req 2.3 und 2.6](../.kiro/specs/01-archiv-kern/requirements.md)).

Anzeigen, Herunterladen, Ändern, Bestätigen, Verwerfen, Export und verweigerte Zugriffe werden nach der Spec
protokolliert. Das Protokoll dient Nachweis und Datensicherheit, nicht der Verhaltensauswertung; der Inhaber
sieht es ein. Protokoll und Metadatenversionen sind nur anfügbar. Für das Zugriffsprotokoll gilt mindestens
Geschäftsjahresende des Eintrags plus zehn Jahre; bei einem bestätigten pflichtigen oder gesperrten Dokument
gegebenenfalls dessen längere Frist. Versionen bleiben so lange wie das Dokument
([Spec 01, Req 2.6 A13, 10.1–10.3, 10.7–10.9](../.kiro/specs/01-archiv-kern/requirements.md),
[Aufgabe 5.7](../.kiro/specs/01-archiv-kern/tasks.md)).

## Manifest, Sicherung und Wiederherstellung

Das tägliche Manifest enthält unter anderem Prüfsummen, ist verkettet, signiert, zeitgestempelt und gesperrt.
Der monatliche Prüflauf vergleicht Archivdateien, Datenbank und Manifest und meldet Abweichungen
([Spec 01, Req 15.1–15.5](../.kiro/specs/01-archiv-kern/requirements.md)).

Die Spec sieht eine tägliche verschlüsselte Sicherung und eine wiederkehrende Restore-Probe vor; der
Betriebsnachweis steht noch aus. Eine Wiederherstellung wird zuerst in einer getrennten Testumgebung geprüft,
einschliesslich Objektbestand und Manifest. Schlüssel und Zugänge gehören nicht in diesen Entwurf
([Spec 01, Req 16.2–16.3, 16.8](../.kiro/specs/01-archiv-kern/requirements.md),
[Sicherungsanleitung](../deploy/backup/README.md), [Wiederherstellung](wiederherstellung.md)).

## Offene Fragen an Fabian

- Welcher Kontaktweg und welche verantwortliche Person gelten im Notfall für den Treuhänder?
- Wo werden die versiegelten Notfallunterlagen verwahrt, ohne Zugangsdaten im Repository festzuhalten?
- Wer prüft den Entwurf und das Gegenlesen des Treuhänders, und ab wann gilt die freigegebene Fassung?
