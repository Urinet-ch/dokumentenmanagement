# Speicherbudget der API-JVM: Metaspace

Diese Anleitung dokumentiert eine lokale Messung für Entwickler. Sie ist kein Betriebsbericht.

## Messaufbau

Die Messung verwendet das Produktions-Jar mit den JVM-Optionen aus [`compose.yml`](../deploy/infomaniak/compose.yml) und eigene lokale PostgreSQL-, MinIO- und Mailpit-Container. MinIO und Mailpit sind in [`backend/compose-devservices.yml`](../backend/compose-devservices.yml) definiert; PostgreSQL muss ebenfalls als eigene lokale Instanz bereitstehen. Die Anwendung wird mit lokaler Konfiguration auf diese Dienste ausgerichtet. Mailpit verwendet STARTTLS mit Anmeldung und synthetischen Testkonten. Die lokale Konfiguration wird nicht eingecheckt.

Das Produktions-Jar wird im Repository gebaut:

```bash
mvn -f backend/pom.xml package
```

Die folgenden JVM-Optionen entsprechen `JAVA_OPTS_APPEND` in `compose.yml`. Native Memory Tracking und das Klassenlade-Log ergänzen die Messoptionen. Die Platzhalter in den Codeblöcken sind durch lokale Werte zu ersetzen; alle Logdateien bleiben im Repository:

```bash
java \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -Xmx112m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=32m \
  -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
  -XX:NativeMemoryTracking=summary \
  -Xlog:class+load=info:file=<repo-relativer-klassen-log> \
  -jar backend/target/quarkus-app/quarkus-run.jar
```

Nach jedem Messpunkt wird der NMT-Bericht abgefragt und die Zahl der Klassenlade-Einträge im Log verglichen:

```bash
jcmd <pid> VM.native_memory summary
grep -c '\[class,load\]' <repo-relativer-klassen-log>
```

Messfolge:

1. Nach dem Start im Leerlauf messen.
2. Einen Jahresexport (`EXPORT_JAHR`) ohne Belege auslösen und den Mailversand abwarten; danach erneut messen.
3. Sechs weitere solche Exporte nacheinander auslösen und nach jedem Export messen.

## Ergebnis

| Messpunkt | Metaspace committed | Non-Class / Class | Geladene Klassen | RSS |
| --- | ---: | ---: | ---: | ---: |
| Leerlauf nach Start | 110.8 MiB | 94 / 15 MiB | 20'750 | 383 MB |
| Erster Jahresexport mit Mail | 117.6 MiB | – | 22'420 | 401 MB |
| Nach sechs weiteren Exporten | 117.9 MiB | – | 22'431 | 413 MB |

Beim ersten Export kamen rund 1'670 Klassen hinzu: 1'161 aus dem AWS-SDK, rund 130 aus Vert.x-Mail, rund 125 aus Netty und 25 aus TLS. Mailversand allein löst den Anstieg nicht aus; die Ablage-Klassen des AWS-SDK werden beim ersten Export geladen. Danach liegt der Zuwachs bei rund 25 KiB je Export und der Verbrauch auf einem Plateau, nicht bei einem fortlaufenden Anstieg.

## Erkenntnisse und Grenze

`MaxMetaspaceSize` begrenzt Non-Class- und Class-Space zusammen. Der reservierte Class-Space von 112 MB ist Adressraum und nicht der Engpass. Mit `-XX:MaxMetaspaceSize=112m` endet die JVM beim ersten Export ohne Fehlerlog; das Suchmerkmal lautet:

```text
Terminating due to java.lang.OutOfMemoryError: Metaspace
```

Die Messungen mit `116m`, `128m` und `192m` laufen. `128m` lässt nur 10 MiB Reserve für später geladene Klassen. Gewählt sind `192m`, damit die Grenze nicht knapp am gemessenen Plateau liegt.

## Künftige Messungen

Nach Updates von Quarkus, AWS-SDK, Vert.x oder Mail-Bibliotheken die Messung wiederholen. Die Grenze soll über dem gemessenen Plateau eine Reserve haben und nicht knapp bemessen sein.

Die Messung deckt nicht ab: Anmeldung mit echten Tokens, Zeitstempeldienst, KI, OCR/PDF und Belege im Export. Sie trifft dazu keine Aussage.

## Referenzen

- JVM-Optionen: [`deploy/infomaniak/compose.yml`](../deploy/infomaniak/compose.yml)
- Messung und Entscheid: [design.md](../.kiro/specs/01-archiv-kern/design.md)
- Bauen und Testen: [README.md](../README.md#bauen-und-testen)
