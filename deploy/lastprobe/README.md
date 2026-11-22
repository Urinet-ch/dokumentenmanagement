# Lastprobe vor Stufe B

Arbeitsstand: Das Lastprobe-Paket ist noch nicht einsatzbereit. Die Messung erfolgt
ausschliesslich mit synthetischen Daten und einem eigenen Mess-Mandanten `last`.

Der isolierte Test der Suchdaten lässt sich mit
`ARCHIV_LASTPROBE_SQL_TEST=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s deploy/lastprobe -p test_suchdaten.py -v`
aus dem Repository starten. Er benötigt Docker, erzeugt einen eigenen netzlosen PostgreSQL-Container und entfernt
ihn nach dem Test. Er prüft relevante SQL-Abschnitte der Migrationen, ersetzt aber keine vollständige Provisionierung.
