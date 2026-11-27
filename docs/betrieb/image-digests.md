# Image-Digests: Vorschlag

Stand: 27.11.2026. Diese Notiz beschreibt nur den Ist-Zustand und einen möglichen
Änderungsweg. Sie pinnt keine Images und ändert weder Compose noch Workflow.

## Ist-Zustand

Die drei Service-Images in `deploy/infomaniak/compose.yml` verwenden Tag-Aliase
für `services.api`, `services.db` und `services.kc`. Die Compose-Prüfung meldet
für genau diese Stellen `IMAGE_DIGEST`; ihre CI-Zulassung lässt keine anderen
Befunde zu.

Der Deploy-Workflow baut die Images mit versionsbezogenen Tags, legt sie in
einem Bundle ab und der Deploy-Befehl setzt daraus die `current`-Aliase. Die
Basis-Images in den Dockerfiles sind davon getrennt zu betrachten: Ihre
Digest-Pins belegen nicht, dass die fertigen Service-Images in Compose ebenfalls
per Digest ausgewählt werden.

## Risiko

Ein Tag ist veränderlich. Compose allein weist deshalb nicht nach, welche
konkreten Image-Bytes ein gleich benannter Tag bezeichnet. Die versionierten
Build-Artefakte im Deploy-Bundle begrenzen dieses Risiko im regulären
Pipeline-Ablauf, aber die Compose-Prüfung kann die Zuordnung zwischen Alias und
Artefakt nicht belegen. Ein Digest-Pin würde die Referenz expliziter machen,
setzt jedoch voraus, dass Workflow, Bundle und Rollback dieselbe unveränderliche
Referenz verwenden können.

## Lesende Ermittlung

Für ein registry-qualifiziertes Image kann der Digest ohne Änderung des Images
ermittelt werden:

```sh
docker buildx imagetools inspect <image>:<tag>
```

Der Befehl liest den Registry-Eintrag; er baut, lädt oder veröffentlicht kein
Image. Die Ausgabe und ermittelte Digests bleiben ausserhalb des Repositories.
Für lokal gebaute Bundle-Images ist zuerst zu klären, welche Registry-Referenz
überhaupt massgeblich ist; ein lokaler Tag ist nicht automatisch ein
registry-qualifiziertes Image.

## Möglicher Wechselweg

1. Fabian entscheidet, ob und wann das Pinning umgesetzt werden soll. Vorher
   bleiben die bestehenden Referenzen und die enge CI-Zulassung unverändert.
2. In einem separaten Workflow-PR wird festgelegt und getestet, wie die
   unveränderlichen Image-Referenzen gebaut oder bezogen und vollständig ins
   Deploy-Bundle aufgenommen werden. Der Workflow muss auch den Vorgänger für
   den Rollback eindeutig verfügbar halten.
3. Erst danach ändert ein separater Compose-PR die drei Service-Referenzen auf
   die vom Workflow bereitgestellten Digests. Die Compose-Prüfung wird dabei
   angepasst, sodass diese drei Befunde nicht mehr zugelassen sind; andere
   Befunde bleiben Fehler.
4. Vor einer produktiven Anwendung müssen Bundle-Aufbau, Compose-Auflösung und
   Rollback mit den neuen Referenzen erfolgreich geprüft sein. Diese Notiz
   veranlasst keinen Deploy.

## Rückfallweg und Folgen für die Prüfung

Der bestehende Rollback verwendet die aktuelle Compose-Datei und die
`current`-/`previous`-Tags; er setzt nicht automatisch die vorherige
Compose-Datei zurück. Bei Digest-Referenzen muss der Vorgänger-Digest daher
weiter verfügbar und vom Rollback erreichbar sein. Falls das nicht gewährleistet
ist, muss der Wechsel vor dem Deploy abgebrochen werden. Ein Rückfall darf nicht
allein daraus abgeleitet werden, dass ältere Images vorhanden sind.

Bis beide getrennten Änderungen umgesetzt und geprüft sind, bleiben die drei
`IMAGE_DIGEST`-Befunde die einzige zugelassene Ausnahme. Nach erfolgreicher
Umstellung muss die Prüfung für alle drei Services ohne diese Ausnahme grün
werden; `MIDDLEWARE_FREMDER_DIENST` und jeder andere Befund müssen weiterhin
rot bleiben.

## Offen für Fabian

Soll das Pinning der Service-Images umgesetzt werden (ja/nein, wann)?
