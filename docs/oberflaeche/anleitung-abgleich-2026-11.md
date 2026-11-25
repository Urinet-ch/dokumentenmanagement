# Abgleich Benutzeranleitung und Oberfläche

Stand: 25.11.2026

## Geprüft

Die 26 Markdown-Dateien unter `frontend/public/anleitung/` und der Hilfe-Katalog wurden gegen die aktuellen Routen, die
Rechtematrix und die Beschriftungen der Oberfläche geprüft. Die Anleitung verwendet die Namen der Bereiche und Aktionen
aus der App; der Katalog ordnet die Abschnitte den passenden Routen und Rollen zu. Die gezielten Hilfe-Tests bestanden:
11 Testdateien, 259 Tests.

Bei den In-App-Anleitungen wurde keine weitere bestätigte Abweichung gefunden.

## Offen

`docs/anleitung-verwaltung.md` enthält noch Hinweise, dass die Benutzeraktionen aus Aufgabe 20.7 nicht auf `main` seien
und Teile der Bedienung erst in die Hilfe der App gehörten. Das stimmt mit dem aktuellen Stand der Oberfläche und der
Anleitung nicht überein. PR #595 ändert diese Datei bereits; sie blieb in diesem Abgleich unangetastet, um parallele
Arbeit nicht zu überschreiben. Nach dessen Merge ist zu prüfen, ob der Widerspruch beseitigt ist.

Die Passkey-Anleitung (`frontend/public/anleitung/erste-schritte-passkey.md`) wurde nicht als auf iPhone oder Mac geprüft
gekennzeichnet. Ein manueller Gerätetest steht weiterhin aus; die Texte bleiben bis dahin unverändert.
