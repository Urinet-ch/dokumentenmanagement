# Spike 0.2: Infomaniak Object Storage mit Object Lock

**Stand:** 08.11.2026. Vorabklärung aus Dokumentation und Ceph-Quellcode abgeschlossen, Live-Tests stehen aus.
**Aufgabe:** 0.2 (Req 0.4, 6.2, 6.3, 6.5, 6.11). **Betroffene Entscheide:** ADR-004, ADR-007, Notfallrückfall P2.

Kennzeichnung: **[D]** dokumentiert (Quelle), **[A]** abgeleitet und nicht getestet, **[T]** im Live-Test bestätigt.

## Projekt

| Angabe | Wert |
|---|---|
| Public Cloud | 26369 |
| Projekt | 51447, OpenStack `PCP-GJOB4BN` |
| Dienst | Object Storage (Ceph RadosGW «tentacle», v20) [D 1][7] |
| S3-Endpunkt | `https://s3.gva2.infomaniak.cloud`, Region `gva2` (Genf) [D 1][2] |
| Keystone | `https://api.pub1.infomaniak.cloud/identity/v3` [D 6], gegen die heruntergeladene clouds.yaml prüfen |

Der ältere Dienst «S3 over Swift» (`s3.pub1`/`s3.pub2.infomaniak.cloud`) gilt als veraltet und kennt weder Object Lock noch Bucket Policies [D 7][8]. Das Archiv nutzt nur den Ceph-Dienst.

## Befund der Vorabklärung

| Prüfpunkt (Aufgabe 0.2) | Stand |
|---|---|
| Bucket mit Object Lock und Versionierung | geht; Object Lock schaltet die Versionierung ein, sie lässt sich nie mehr abschalten [D 3] |
| `PutObjectRetention` pro Objekt | unterstützt, ebenso Legal Hold [D 3] |
| GOVERNANCE | **widersprüchlich:** die Object-Lock-Seite sagt «Not supported» [D 3], die Feature-Liste sagt «supported» [D 4]; Ceph selbst kann es [D 15]. Erster Live-Test |
| COMPLIANCE | verfügbar; verkürzen und vorzeitig löschen kann niemand [D 3] |
| Objekte ohne Retention (`eingang/`) | bleiben endgültig löschbar, solange der Bucket keine Default-Retention hat [D 3]. Darum keine Default-Retention setzen [A] |
| App-Schlüssel ohne `s3:BypassGovernanceRetention` | **in einem Projekt nicht möglich [A]:** alle Schlüssel eines Projekts sind für Ceph derselbe Besitzer mit vollem Recht und können Bucket Policies selbst löschen [D 5][A 18][19]. Getrennte Rechte nur über ein zweites Projekt mit Zugriff über die Projektgrenze (Bucket als `<tenant>:<bucket>`) [D 5] |

Folge für Aufgabe 0.2: Gibt es keinen Schlüssel ohne Bypass-Recht, gilt nach tasks.md COMPLIANCE mindestens für `manifest/` (Req 6.11). Nach der Vorabklärung ist das der wahrscheinliche Ausgang.

## Optionen

| Option | Schutz vor kompromittiertem App-Schlüssel | Kosten und Risiko |
|---|---|---|
| A: ein Projekt, COMPLIANCE für gesetzlich aufzubewahrende Arten | voll: niemand kann vorzeitig löschen | eine falsch gesetzte Frist bleibt bis zum Ablauf; Frist im Code prüfen (z. B. höchstens Aufbewahrungsfrist + 1 Jahr) |
| B: ein Projekt, GOVERNANCE | keiner: jeder Schlüssel des Projekts darf umgehen [A] | einfach, erfüllt Req 6.5 nicht |
| C: zwei Projekte, Policy mit Deny auf Bypass für das App-Projekt | auf dem Papier ja [A] | AWS-Werkzeuge lehnen `<tenant>:<bucket>` ab [D 5], mit dem Java SDK v2 ungetestet; Löschen über Projektgrenzen bei Infomaniak «Not tested» [D 5] |

Empfehlung: zuerst die Live-Tests 1–5. Bestätigen sie den Befund, Option A per ADR-007-Nachtrag festlegen; Option C nur, wenn GOVERNANCE geht **und** der Test mit dem Java SDK und `<tenant>:<bucket>` grün ist.

## Live-Tests (ausstehend)

Wegwerf-Bucket `archiv-spike-<datum>`, Sperrfristen von wenigen Minuten. Zwei Schlüssel im Projekt: `archiv-owner` und `archiv-apptest`.

1. Bucket mit `--object-lock-enabled-for-bucket` anlegen; Versionierung und Lock-Konfiguration auslesen.
2. GOVERNANCE (10 min) per `put-object-retention` setzen. Fehler → GOVERNANCE nicht verfügbar.
3. Mit `archiv-apptest`: Version löschen ohne Bypass (erwartet AccessDenied), Frist verkürzen ohne Bypass (erwartet Fehler), löschen **mit** Bypass (Erfolg bestätigt: kein Schlüssel ohne Bypass-Recht im selben Projekt).
4. COMPLIANCE (3 min): löschen mit Bypass muss scheitern, nach Ablauf gelingen.
5. `eingang/` ohne Retention: Version endgültig löschen muss gelingen.
6. Nur bei Option C: zweites Projekt, Policy aus der Vorabklärung, Zugriff mit dem AWS SDK for Java v2 (Quarkus) auf `<tenant>:<bucket>`.

Ergebnisse, Fehlermeldungen und Antwortzeiten werden hier nachgetragen. Schlüssel liegen nur unter `~/.config/urinet-archiv/` (chmod 600), nie im Repo; nach dem Spike wird `archiv-apptest` widerrufen.

## Quarkus

```properties
quarkus.s3.endpoint-override=https://s3.gva2.infomaniak.cloud
quarkus.s3.path-style-access=true
quarkus.s3.aws.region=gva2
quarkus.s3.aws.credentials.type=static
```

Schlüssel nur über Secret-Dateien bzw. Umgebungsvariablen (Req 16.9) [D 25].

## Offene Punkte

- Ausgehender Traffic über 10 TB/Monat: Preisseite und Pressemitteilung sagen kostenpflichtig, die Feature-Seite nicht [D 4][29][30][31]. Für das Archiv unerheblich (einige GB pro Jahr).
- Ob der Support eine irrtümlich zu lange COMPLIANCE-Frist aufheben kann, ist nicht dokumentiert; bei Option A vor Welle 2 bei Infomaniak nachfragen.
- Der Schutz greift auf API-Ebene; der Betreiber des Clusters könnte Daten entfernen [D 35]. Das deckt der AVV, nicht die Technik.

## Quellen

[1] https://docs.infomaniak.cloud/s3aas/ · [2] https://docs.infomaniak.cloud/s3aas/quickstart/ · [3] https://docs.infomaniak.cloud/s3aas/object_lock/ · [4] https://docs.infomaniak.cloud/s3aas/features/ · [5] https://docs.infomaniak.cloud/s3aas/acls/ · [6] https://docs.infomaniak.cloud/s3aas/auth/ · [7] https://docs.infomaniak.cloud/object_storage/comparison_table/ · [8] https://docs.infomaniak.cloud/object_storage/s3/ · [15] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_op.cc · [18] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_auth_keystone.cc · [19] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_auth.cc · [25] https://docs.quarkiverse.io/quarkus-amazon-services/dev/amazon-s3.html · [29] https://www.infomaniak.com/en/hosting/public-cloud/prices · [30] https://www.infomaniak.com/en/hosting/public-cloud/object-storage · [31] https://www.presseportal.ch/de/pm/100057093/100943444 · [35] https://ceph.io/en/news/blog/2025/rgw-deep-dive-3/
