# ADR-018: Stapel-Original als eigenes Archivobjekt

**Status:** Vorgeschlagen – Freigabe per Merge des Spec-PR von Spec 09.

**Datum:** 28.11.2026

## Kontext
- Nach GeBüV muss nachvollziehbar sein, wie ein Dokument aus dem Scan entstand. Die Dokumente eines Stapels sind
  seitengenaue Auszüge; die Trennung ist eine Verarbeitung des Archivs.

## Entscheid
- Das empfangene PDF bleibt als Stapel-Original erhalten: zuerst ungesperrt unter `eingang/`, nach der Entscheidung
  aller Dokumente unter `archiv/`, im Manifest und im Prüflauf; gesperrt im Modus GOVERNANCE bis zum spätesten
  `aufbewahren_bis` seiner pflichtigen Dokumente. Sind alle Dokumente verworfen, wird es gelöscht.
- Jedes Dokument verweist auf Stapel und Seitenbereich; das Stapelprotokoll hält die Seitenbilanz fest.
- Das archivierte Original wird nie verändert, auch nicht, um Seiten verworfener Dokumente zu entfernen (E13).

## Folgen
- Seiten verworfener Dokumente bleiben bis zum Ende der Sperre im Original; darum sieht Stapel nur, wer alle
  Vertraulichkeitsstufen sieht (A25, A26).
- Zusätzlicher Speicher je Stapel; die Auszüge sind verlustfrei und nicht neu gerastert.

## Verweise
Spec 09, Requirement 5 und 15; ADR-004; ADR-007.
