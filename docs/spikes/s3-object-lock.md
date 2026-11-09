# Spike 0.2: Infomaniak Object Storage mit Object Lock

**Stand:** 08.11.2026. Vorabklärung und Live-Tests abgeschlossen (Wegwerf-Buckets, Sperrfristen 3–10 Minuten, danach gelöscht).

**Ergebnis:** Object Lock, GOVERNANCE und COMPLIANCE funktionieren wie bei AWS. Einen App-Schlüssel ohne Bypass-Recht gibt es in einem Projekt nicht: Jeder Schlüssel des Projekts kann eine schützende Bucket Policy löschen und danach die Sperre umgehen [T 6b–7g]. Damit gilt der in tasks.md vorgesehene Rückfall: COMPLIANCE mindestens für `manifest/` (Req 6.11, P2).
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

| Prüfpunkt (Aufgabe 0.2) | Ergebnis |
|---|---|
| Bucket mit Object Lock und Versionierung | geht; `--object-lock-enabled-for-bucket` schaltet die Versionierung ein (`Enabled`) [T 1] |
| `PutObjectRetention` GOVERNANCE pro Objekt | geht, auch als Header beim Upload [T 3a, 6a]. Die Doku-Angabe «Not supported» [D 3] ist veraltet |
| GOVERNANCE ohne Bypass-Header | Löschen der Version: `AccessDenied: forbidden by object lock`; Verkürzen: `… governance bypass check failed` [T 3b, 3c] |
| GOVERNANCE mit `x-amz-bypass-governance-retention` | Verkürzen und Löschen gelingen mit **jedem** Schlüssel des Projekts [T 3c', 3d] |
| COMPLIANCE | geht; Löschen und Verkürzen scheitern auch mit Bypass, Wechsel zu GOVERNANCE ist verboten, Verlängern geht [T 4a–4e] |
| `DELETE` ohne VersionId | legt nur einen Delete-Marker an, die gesperrte Version bleibt [T 4f] |
| Objekte ohne Retention (`eingang/`) | Version endgültig löschbar [T 5]. Keine Default-Retention setzen, sie gälte für den ganzen Bucket [A] |
| App-Schlüssel ohne `s3:BypassGovernanceRetention` | **nicht möglich in einem Projekt:** Eine Deny-Policy auf `s3:BypassGovernanceRetention` blockiert den Bypass zwar [T 6c], aber jeder Schlüssel des Projekts darf die Policy löschen oder überschreiben, auch wenn sie `s3:PutBucketPolicy`/`s3:DeleteBucketPolicy` verbietet [T 6b, 6d, 7d–7g]. Getrennte Rechte gäbe es nur mit einem zweiten Projekt (Option C) |
| Antwortzeiten (vom Mac, Internet) | 240–950 ms je Aufruf, Löschen am langsamsten [T] |

Folge für Aufgabe 0.2: Es gibt keinen Schlüssel ohne Bypass-Recht. Nach tasks.md gilt COMPLIANCE für `manifest/` (Req 6.11).

## Optionen

| Option | Schutz vor kompromittiertem App-Schlüssel | Kosten und Risiko |
|---|---|---|
| A: ein Projekt, COMPLIANCE für gesetzlich aufzubewahrende Arten | voll: niemand kann vorzeitig löschen [T 4] | eine falsch gesetzte Frist bleibt bis zum Ablauf; Frist im Code begrenzen (z. B. höchstens Aufbewahrungsfrist + 1 Jahr) |
| B: ein Projekt, GOVERNANCE für Dokumente, COMPLIANCE für `manifest/` (Rückfall laut tasks.md, P2) | Dokumente nur gegen Fehler der App und Versehen; Manipulation fällt über das COMPLIANCE-Manifest auf | erfüllt Req 6.5 nur eingeschränkt: ein gestohlener App-Schlüssel kann Policy löschen und Dokumente endgültig entfernen [T 7] |
| C: zwei Projekte, Policy mit Deny auf Bypass für das App-Projekt | auf dem Papier ja [A] | AWS-Werkzeuge lehnen `<tenant>:<bucket>` ab [D 5], mit dem Java SDK v2 ungetestet; Löschen über Projektgrenzen bei Infomaniak «Not tested» [D 5] |

Empfehlung: **Option B** (der in tasks.md vorgesehene Rückfall) für Welle 2 festhalten; ADR-007 entsprechend nachtragen. Der Unterschied zu Option A ist klein, weil Dokumente mit gesetzlicher Pflicht ohnehin nie vorzeitig gelöscht werden sollen; wer den stärkeren Schutz will, wählt A und begrenzt die Frist im Code. Option C nur prüfen, wenn Fabian Schutz gegen gestohlene App-Schlüssel ohne COMPLIANCE verlangt (eigener Spike mit zweitem Projekt und Java SDK).

## Live-Tests (08.11.2026, AWS CLI 2.39.6)

Wegwerf-Buckets `archiv-spike-20261108` und `archiv-spike-policy-20261108`, danach gelöscht. Zwei OpenStack-Benutzer im selben Projekt mit je einem EC2-Schlüssel: `archiv-owner` und `archiv-apptest` (gleiche Tenant ID).

| Nr. | Test | Ergebnis |
|---|---|---|
| 1 | Bucket mit Object Lock anlegen, Versionierung und Lock-Konfiguration lesen | ok, `Enabled` |
| 3a | GOVERNANCE +10 min setzen und lesen | ok |
| 3b | Version löschen ohne Bypass (apptest) | `AccessDenied: forbidden by object lock` |
| 3c | Frist verkürzen ohne Bypass (apptest) | `proposed retain-until date shortens an existing retention period and governance bypass check failed` |
| 3c' | Frist verkürzen mit Bypass (apptest) | ok |
| 3d | Version löschen mit Bypass (apptest) | ok, Version weg (404) |
| 4a | COMPLIANCE +3 min setzen | ok |
| 4b/4c | löschen bzw. verkürzen mit Bypass (owner) | `AccessDenied` |
| 4d | COMPLIANCE → GOVERNANCE | `can't change retention mode from COMPLIANCE to GOVERNANCE` |
| 4e | COMPLIANCE verlängern | ok |
| 4f | `DELETE` ohne VersionId | Delete-Marker, Version bleibt |
| 5 | `eingang/` ohne Retention: Version löschen (apptest) | ok, endgültig weg |
| 6a | Upload mit Lock-Headern (GOVERNANCE) | ok, Antwort mit `ChecksumCRC64NVME` |
| 6b | Bucket Policy setzen (apptest) | ok |
| 6c | Deny `s3:BypassGovernanceRetention` für `*` aktiv: Bypass-Löschen (apptest) | `AccessDenied` – Deny greift |
| 6d | Policy löschen (apptest) | ok |
| 7b–7g | Policy verbietet zusätzlich `s3:PutBucketPolicy`/`s3:DeleteBucketPolicy`: apptest löscht und überschreibt sie trotzdem, danach Bypass-Löschen ok | Policy schützt sich nicht selbst |

Nicht getestet: Zugriff mit dem AWS SDK for Java v2 (folgt in Aufgabe 2.3 gegen denselben Endpunkt), Option C. Schlüssel liegen nur unter `~/.config/urinet-archiv/` (chmod 600), nie im Repo; der Schlüssel von `archiv-apptest` wird nach dem Spike widerrufen.

## Quarkus

```properties
quarkus.s3.endpoint-override=https://s3.gva2.infomaniak.cloud
quarkus.s3.path-style-access=true
quarkus.s3.aws.region=gva2
quarkus.s3.aws.credentials.type=static
```

Schlüssel nur über Secret-Dateien unter `/run/secrets/`, nie über Umgebungsvariablen (Req 16.9) [D 25] (korrigiert 09.11.2026).

## Offene Punkte

- Ausgehender Traffic über 10 TB/Monat: Preisseite und Pressemitteilung sagen kostenpflichtig, die Feature-Seite nicht [D 4][29][30][31]. Für das Archiv unerheblich (einige GB pro Jahr).
- Ob der Support eine irrtümlich zu lange COMPLIANCE-Frist aufheben kann, ist nicht dokumentiert; bei Option A vor Welle 2 bei Infomaniak nachfragen.
- Der Schutz greift auf API-Ebene; der Betreiber des Clusters könnte Daten entfernen [D 35]. Das deckt der AVV, nicht die Technik.

## Quellen

[1] https://docs.infomaniak.cloud/s3aas/ · [2] https://docs.infomaniak.cloud/s3aas/quickstart/ · [3] https://docs.infomaniak.cloud/s3aas/object_lock/ · [4] https://docs.infomaniak.cloud/s3aas/features/ · [5] https://docs.infomaniak.cloud/s3aas/acls/ · [6] https://docs.infomaniak.cloud/s3aas/auth/ · [7] https://docs.infomaniak.cloud/object_storage/comparison_table/ · [8] https://docs.infomaniak.cloud/object_storage/s3/ · [15] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_op.cc · [18] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_auth_keystone.cc · [19] https://github.com/ceph/ceph/blob/tentacle/src/rgw/rgw_auth.cc · [25] https://docs.quarkiverse.io/quarkus-amazon-services/dev/amazon-s3.html · [29] https://www.infomaniak.com/en/hosting/public-cloud/prices · [30] https://www.infomaniak.com/en/hosting/public-cloud/object-storage · [31] https://www.presseportal.ch/de/pm/100057093/100943444 · [35] https://ceph.io/en/news/blog/2025/rgw-deep-dive-3/
