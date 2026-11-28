# Überwachung der Erreichbarkeit

Stand 28.11.2026. Der Entscheid vom 27.11.2026 wählte eine unabhängige externe
Erreichbarkeitsprüfung des Anwendungs-Hosts sowie eine Meldung bei
fehlgeschlagenem Deploy. Am 28.11.2026 entschied Fabian, den Anmelde-Host
bewusst nicht extern zu überwachen: Die Prüfung des Anwendungs-Hosts genügt,
die externe Prüfung des Anmelde-Hosts wurde wieder entfernt. Eine Zertifikatsprüfung
wurde nicht gewählt.

## Aktueller Stand

- Die Anwendung überwacht fachliche Befunde und eigene Betriebszustände. Diese
  interne Überwachung kann einen vollständigen Ausfall der Anwendung nicht
  selbst melden.
- Fällt nur der Anmeldedienst aus, bleibt die Health der App grün. Dies ist die
  bekannte Lücke der bewusst nicht extern überwachten Anmeldung.
- Der Deploy prüft beim Ausrollen die Health und öffentlichen Antworten beider
  Hosts sowie die Health weiterer Apps. Ein fehlgeschlagener Deploy wird
  ebenfalls gemeldet. Diese Prüfungen ersetzen keine laufende Überwachung
  zwischen Deploys.
- Eine unabhängige externe HTTP-Prüfung des Anwendungs-Hosts bestand seit dem
  13.11.2026 ausserhalb der VPS-Konfiguration. Sie erfasste den Ausfall vom
  26.11.2026; er dauerte rund 75 Minuten. Ob eine Benachrichtigung ausgelöst
  oder zugestellt wurde, ist nicht belegt.

## Entscheid und offene Nachweise

| Prüfung | Entscheid und Stand | Offen |
| --- | --- | --- |
| Erreichbarkeit des Anwendungs-Hosts | Gewählt und im Infra-Repo umgesetzt. | — |
| Erreichbarkeit des Anmelde-Hosts | Bewusst nicht extern überwacht; externe Prüfung entfernt, da die Prüfung des Anwendungs-Hosts genügt. | Kein Betriebsnachweis offen. |
| Zertifikatsprüfung | Nicht gewählt. | — |
| Fehlgeschlagener Deploy | Gewählt und im Infra-Repo umgesetzt. | — |

Die Erreichbarkeitsprüfung soll nur die vorgesehenen öffentlichen Antworten
prüfen. Kennzahlen und Detail-Health bleiben intern. Diese Dokumentation enthält
keine Betriebsadressen, Werkzeugnamen oder Alarmwege.
