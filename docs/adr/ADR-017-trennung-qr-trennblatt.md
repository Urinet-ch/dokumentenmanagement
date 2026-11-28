# ADR-017: Stapel per QR-Trennblatt trennen

**Status:** Vorgeschlagen – Freigabe per Merge des Spec-PR von Spec 09.

**Datum:** 28.11.2026

## Kontext
- Ein Stapel enthält bis 50 Blatt Post, beidseitig gescannt. Das Archiv muss daraus seitengenau Dokumente bilden.
- Leerseiten-Trennung verwechselt leere Rückseiten mit Dokumentgrenzen; Patch-Code-Blätter hängen vom Gerät ab;
  KI-Trennung ist nicht prüfbar und verstösst gegen den Grundsatz, dass die KI nur vorschlägt (ADR-006).

## Entscheid
- Ein Trennblatt trägt beidseitig einen QR-Code `URINET-TRENN;v=1[;art=<CODE>]`, ein Ordner-Deckblatt
  `URINET-ORDNER;v=1;titel=<Text>`. Das Archiv erzeugt beide selbst (Trennblatt-Generator).
- Die Trennung ist eine reine Funktion über die Seitentypen mit Seitenbilanz; geht die Bilanz nicht auf, wird nichts
  getrennt und der Stapel angehalten.
- Andere QR-Codes (Swiss QR) machen eine Seite nie zum Trennblatt.

## Folgen
- Trennblätter sind wiederverwendbar; eine Art auf dem Trennblatt belegt die Art vor, ersetzt aber die Bestätigung nicht.
- Seiten vor dem ersten Trennblatt bilden ein eigenes Dokument mit Vermerk.

## Verweise
Spec 09, Requirement 3, 4 und 6.
