# Wissenspaket offline prüfen

Java 25 und Maven sind zum Bauen nötig:

```sh
mvn -B -ntp -f tools/wissen_paket/pom.xml verify
java -jar tools/wissen_paket/target/wissen-paket-0.1.0-SNAPSHOT.jar validate paket.zip --json
java -jar tools/wissen_paket/target/wissen-paket-0.1.0-SNAPSHOT.jar validate okf-ordner --quellen originale
java -jar tools/wissen_paket/target/wissen-paket-0.1.0-SNAPSHOT.jar bauen okf-ordner -o paket.zip \
  --mandant urinet --erzeugt-von lokales-werkzeug --erzeugt-mit lokal --kontext-stufe NORMAL
```

`validate` prüft auch einen OKF-Ordner ohne `paket.yaml` mit einem vorläufigen Kopf im Speicher. Der Hinweis
`PAKETKOPF_NICHT_GEPRUEFT` bedeutet: Mandant, Erzeuger, Herkunft und Kontextstufe sind noch nicht geprüft;
insbesondere lässt sich die Kombination `cloud` und `INTERN` ohne diese Angaben nicht beurteilen.
Ein ZIP muss dagegen `paket.yaml` enthalten. `bauen` übernimmt eine vorhandene `paket.yaml`
unverändert; fehlt sie, erzeugt es diese nur im ZIP aus den angegebenen Optionen. Die Eingabe und das
Quellenverzeichnis bleiben unverändert. Das Ziel muss ausserhalb beider Ordner liegen und darf nur mit
`--ueberschreiben` ersetzt werden. Das ZIP wird vor dem Schreiben mit dem Import-Leser erneut geprüft.
Gleiche Eingaben ergeben gleiche ZIP-Bytes. Für einen neu erzeugten Paketkopf ist `erzeugt_am` für diese
Reproduzierbarkeit standardmässig `1970-01-01T00:00:00Z`; mit `--erzeugt-am 2026-11-20T12:00:00Z`
kann die tatsächliche Erstellungszeit ausdrücklich angegeben werden. Ein vorhandener Paketkopf behält
seine eigene Zeit.

Der Bericht enthält Codes und Paketpfade, keine Seiteninhalte; `--json` liefert `befunde`, `hinweise`
und `prozesshinweis`. Exit-Code 0 bedeutet keinen offline feststellbaren sperrenden Befund, 1 einen
Befund oder Bedienungs-/Dateifehler. Der Import prüft jedes Paket erneut und ist massgeblich: Ob eine
Quelle im richtigen Mandanten archiviert und sichtbar ist, die Dokumentseiten stimmen, ein Pfad schon
vergeben ist und welche Stufe die Hülle hat, lässt sich offline nicht bestimmen.

`--quellen` berechnet SHA-256 der lokalen Originaldateien und gleicht sie mit den Paketangaben ab.
Ein fehlender Treffer ergibt `QUELLE_NICHT_GEPRUEFT`. Bei PDFs mit lokal verfügbarem `pdftotext` prüft
es auch den Auszug im angegebenen Seitenbereich; ein fehlender Treffer ist nur der Hinweis
`AUSZUG_LOKAL_NICHT_GEFUNDEN`, da OCR und PDF/A im Archiv abweichen können. Ohne lesbaren lokalen
Volltext bleibt die Prüfung offen. Es werden keine Originaldateien ins ZIP aufgenommen.

Vor der Erarbeitung gelten die Regeln in [wissen-lokal-erarbeiten.md](../../docs/wissen-lokal-erarbeiten.md).
Das Werkzeug und der Import sind keine Kontrolle, ob Angaben zum Modell oder zur Vertraulichkeit stimmen.
