# Werkzeuge

| Pfad | Zweck |
| --- | --- |
| `archiv_client.py` | Client für den Generator-Endpunkt (Spec 01, Req 11.4, 11.5, 11.9; Aufgabe 4.7) |
| `test_archiv_client.py` | Tests zum Client: `python3 -m unittest tools/test_archiv_client.py` |
| `golden_synth/` | Generator für synthetische Golden-Belege (Req 0.10) |
| `crm/` | synthetischer Beispiel-Export 21.CRM |
| `last/` | Lastskripte (Stufe B, Aufgabe 5.11) |

## Generator-Client `archiv_client.py`

Legt ein fertig erzeugtes PDF mit exakten Metadaten über `POST /api/v1/dokumente/generator` im Archiv ab. Das
Archiv verarbeitet und archiviert es ohne KI-Vorschlag. Nur Python-Standardbibliothek (ab 3.10).

### Einrichten

1. Der Inhaber stellt ein API-Token vom Scope `generator` aus (`POST /api/v1/api-tokens`, Req 2.20). Es wird nur
   einmal angezeigt.
2. Das Token in eine Datei mit Rechten 600 legen, Standard `~/.config/urinet-archiv/token`, ein anderer Pfad über
   `ARCHIV_TOKEN_DATEI`. Das Token selbst nie in den Code oder in eine Umgebungsvariable (tech.md Regel 7):
   ```sh
   mkdir -p ~/.config/urinet-archiv
   install -m 600 /dev/null ~/.config/urinet-archiv/token
   # Token mit einem Editor in die Datei schreiben
   ```
3. `tools/archiv_client.py` neben das Generator-Skript kopieren oder den Ordner `tools/` in `sys.path` aufnehmen.
   Die Adresse des Archivs ist `https://archiv.apps.urinet.ch`; zum Testen über `ARCHIV_URL` oder den Parameter `url`
   ändern. Erlaubt ist nur `https://`, `http://` nur für `localhost`, `127.0.0.1` und `::1`. Weiterleitungen (3xx)
   folgt der Client nicht, damit das Token an keine andere Adresse geht.

### Einbau in `rechnung_gen.py`

```python
from archiv_client import archivieren

archivieren(pdf_pfad, {
    "art": "AUSGANGSRECHNUNG",
    "titel": f"Rechnung {nummer} {kunde.name}",
    "datum": datum.isoformat(),                      # 2026-03-10
    "partner": {"crmId": kunde.crm_id, "name": kunde.name},
    "finanz": {"belegnummer": nummer,                # 2026-003
               "richtung": "EINNAHME",
               "betrag": str(total),                 # Decimal als Text, nie float
               "waehrung": "CHF",
               "faelligkeit": faellig.isoformat(),
               "qrReferenz": qr_referenz},
    "tags": ["Rechnung"],
})
```

### Einbau in `urinet_pdf.py`

```python
from archiv_client import archivieren

archivieren(pdf_pfad, {"art": "OFFERTE", "titel": titel, "datum": datum.isoformat(),
                       "partner": {"crmId": kunde.crm_id},
                       "finanz": {"belegnummer": nummer, "betrag": str(total), "waehrung": "CHF"},
                       "zusatz": {"angebot_gueltig_bis": gueltig_bis.isoformat()}})
```

Pflicht sind die Pflichtfelder der Art (Seed `dokumentarten.json`, Feldnamen in camelCase). Ist der Standard der
Art `PERSONAL` (z. B. `LOHNABRECHNUNG`), sind zusätzlich `vertraulichkeit` und `personBenutzer` (Keycloak-ID `sub`
des betroffenen Mitarbeiters) Pflicht. Zusatzfelder der Art stehen unter `zusatz`.

### Verhalten

| Antwort | Client | Rückgabe `zustand` |
| --- | --- | --- |
| 202 | angenommen, das Archiv verarbeitet und archiviert | `ANGENOMMEN` |
| 200 | dieselbe Datei liegt schon im Archiv | `VORHANDEN` |
| nicht erreichbar, Zeitüberschreitung, 5xx | Kopie von PDF und Metadaten nach `archiv-ausstehend/`; der nächste Aufruf sendet sie zuerst | `AUSSTEHEND` |
| 409 (Belegnummer vergeben, Frist abgelaufen) | PDF und Metadaten nach `archiv-konflikt/`, kein erneuter Versuch; von Hand klären | `KONFLIKT` |
| andere 4xx (z. B. 422 Pflichtfeld fehlt), 3xx | Ausnahme `ArchivFehler` mit `status`, `code` (Feld `fehlercode` der Problem Details, z. B. `PFLICHTFELD_FEHLT`), `felder`; das PDF bleibt liegen | – |

Grenzen: PDF bis 50 MB, Metadaten (JSON-Text) bis 4 KB; darüber antwortet das Archiv mit 413 `DATEI_ZU_GROSS`.

`archiv-ausstehend/` und `archiv-konflikt/` liegen im Ordner des PDFs (oder im Parameter `ordner`). Ausstehende
PDFs lassen sich auch ohne neues PDF senden: `python3 tools/archiv_client.py --nachsenden <ordner>`.

Von der Kommandozeile: `python3 tools/archiv_client.py <pdf> <metadaten.json>`; Exit-Code 0 bei 200/202, 1 bei
Ablehnung, 3 ausstehend, 4 Konflikt.
