# Vorlage Auftragsbearbeitungsvertrag

> **ENTWURF – NICHT RECHTLICH GEPRÜFT.** Diese Vorlage ist keine Rechtsberatung. Vor Verwendung sind die
> offenen Punkte am Ende durch eine Juristin oder einen Juristen zu prüfen und mit dem Verband abzustimmen.

## 1. Parteien und Vertragsgrundlage

Zwischen

**Verantwortlicher:** `<vollständiger Name des Verbandes>`, `<Rechtsform>`, `<Sitz und Adresse>`, vertreten durch
`<Name und Funktion>`

und

**Auftragsbearbeiterin:** UriNet, `<vollständige juristische Bezeichnung>`, `<Sitz und Adresse>`, vertreten durch
`<Name und Funktion>`

wird diese Vereinbarung zur Auftragsbearbeitung («Vereinbarung») geschlossen. Sie ergänzt den
`<Hauptvertrag / Auftrag vom Datum>` und gilt ab `<Datum>`.

Die Parteien gehen davon aus, dass der Verband für die hier beschriebenen Personendaten Verantwortlicher und UriNet
Auftragsbearbeiterin ist. Die rechtliche Einordnung und die Angaben zu den Parteien sind vor Unterzeichnung zu
bestätigen. Die Vereinbarung soll die Auftragsbearbeitung nach Art. 9 DSG regeln; ob sie sämtliche gesetzlichen
Anforderungen erfüllt, ist rechtlich zu prüfen.

## 2. Gegenstand, Zweck und Dauer

Der Verantwortliche beauftragt UriNet, Dokumente des Verbandes im UriNet Archiv entgegenzunehmen, zu erfassen,
aufzubewahren, zu erschliessen und gemäss den dokumentierten Weisungen zugänglich zu machen. Dazu gehören die
technisch erforderlichen Verarbeitungsschritte wie Speicherung, Texterkennung, Metadatenvorschläge, Suche,
Bereitstellung über die vereinbarten Schnittstellen, Sicherung, Wiederherstellung sowie Rückgabe und Löschung.
KI-Ausgaben sind Vorschläge; die fachliche Bestätigung bleibt einer berechtigten Person vorbehalten.

Die Bearbeitung beginnt am `<Datum>` und dauert bis zum Ende des Hauptvertrags. Rückgabe und Löschung richten sich
nach Ziffer 10 dieser Vereinbarung.

## 3. Daten und betroffene Personen

Bearbeitet werden:

- Dokumente des Verbandes sowie die dazugehörigen Metadaten und Klassifikationen;
- Benutzer-, Rollen- und Zugriffsangaben, die für die Mandantentrennung und Berechtigungsprüfung erforderlich sind;
- Zugriffs- und Änderungseinträge;
- Chat-Fragen ohne Personenbezug (Frage, Quellen und Tokens) sowie die für den Dienst erforderlichen
  Nutzungsangaben. Aufbewahrungsfrist der Chat-Fragen: `<Frist, offener Punkt A2>`. **Offener Punkt A2:** Der
  Systemstandard beträgt 90 Tage (je Mandant einstellbar von 1 bis 365 Tagen; Spec 03, Req 6.5). Dem Verband
  VHF/GSK sind 30 Tage zugesagt (Entscheid vom 16.11.2026). Zu klären ist, ob im Vertrag 30 Tage stehen und die
  Mandantenkonfiguration entsprechend auf 30 Tage gesetzt wird. Weicht Spec 02 ab (12 Monate Löschfrist für
  gespeicherte Chat-Gespräche), ist auch diese Abweichung zu bereinigen.

Betroffene Personen können insbesondere `<zutreffende Gruppen, z. B. Mitglieder, Mitarbeitende, Funktionsträger oder
Kontaktpersonen; abschliessend festlegen>` sein. Besondere Personendaten sind kein Zweck der Bearbeitung. Falls
solche Angaben dennoch in eingereichten Dokumenten vorkommen, bleiben die Weisungen und Schutzpflichten dieser
Vereinbarung anwendbar; der Verantwortliche legt fest, ob solche Dokumente im Dienst bearbeitet werden dürfen.

Die Bearbeitung beschränkt sich auf die vorgenannten Zwecke und die dokumentierten Weisungen des Verantwortlichen.
Eine Nutzung durch UriNet für eigene Zwecke oder zum Trainieren eigener Modelle ist nicht vereinbart. Die
Vertragsbedingungen der KI-Unterauftragsbearbeiter zur Speicherung und Nutzung von Eingaben sind vor Unterzeichnung
anhand ihrer Unterlagen zu prüfen; siehe [Vertragsübersicht](./README.md).

## 4. Weisungen und Pflichten der Parteien

1. UriNet bearbeitet Personendaten nur auf dokumentierte Weisung des Verantwortlichen, soweit keine gesetzliche
   Pflicht entgegensteht. In diesem Fall informiert UriNet den Verantwortlichen, soweit dies rechtlich zulässig ist.
2. Der Verantwortliche ist für die Rechtmässigkeit der Bearbeitung, die Information der betroffenen Personen und
   die Zulässigkeit der an UriNet übermittelten Daten verantwortlich.
3. UriNet beschränkt den Zugriff auf Personen, die ihn für ihre Aufgabe benötigen, und verpflichtet diese zur
   Vertraulichkeit. Die Verpflichtung gilt über das Ende der jeweiligen Tätigkeit hinaus.
4. UriNet unterstützt den Verantwortlichen im angemessenen Umfang bei Auskunfts-, Berichtigungs- und weiteren
   Betroffenenbegehren sowie bei der Erfüllung seiner Datenschutzpflichten. UriNet beantwortet solche Begehren
   nicht eigenständig, ausser der Verantwortliche weist dies an oder eine gesetzliche Pflicht verlangt es.
5. UriNet informiert den Verantwortlichen, wenn eine Weisung nach eigener Einschätzung gegen anwendbares
   Datenschutzrecht verstösst, und klärt das weitere Vorgehen vor der betreffenden Bearbeitung.

## 5. Technische und organisatorische Massnahmen

UriNet setzt die folgenden, im Repository dokumentierten Massnahmen um und überprüft sie im Rahmen des
Systembetriebs:

| Bereich | Massnahme | Dokumentationsnachweis |
| --- | --- | --- |
| Mandantentrennung | Eigene Datenbankschemas und Buckets je Mandant. Der Mandantenkontext stammt aus dem verifizierten Zugang und nicht aus einer Benutzereingabe. | [ADR-002](../adr/ADR-002-schema-pro-mandant-liquibase.md), [Technikregeln](../../.kiro/steering/tech.md) |
| Zugriff | Rollen- und Rechteprüfung sowie Sichtbarkeit nach Vertraulichkeits- und Zugriffsstufen; Webseiten-Zugänge sind auf ihren vorgesehenen Umfang begrenzt. | [ADR-009](../adr/ADR-009-rollen-rechtematrix.md), [Spec 03](../../.kiro/specs/03-zweiter-mandant/requirements.md) |
| Integrität und Aufbewahrung | SHA-256-Prüfsummen, Objektversionierung und Object Lock nach Dokumentart. Manifeste werden im Modus COMPLIANCE gesperrt; die Anwendung setzt keinen Bypass-Header und verkürzt keine Sperren. | [ADR-007](../adr/ADR-007-unveraenderbarkeit-governance.md), [Spec 01](../../.kiro/specs/01-archiv-kern/requirements.md) |
| Protokollierung | Zugriffs- und Änderungseinträge werden append-only geführt und nach den dokumentierten Aufbewahrungsregeln aufbewahrt. | [Technikregeln](../../.kiro/steering/tech.md), [Spec 01](../../.kiro/specs/01-archiv-kern/requirements.md) |
| Zeitnachweis | Manifeste enthalten SHA-256-Werte, eine Hash-Kette, eine Signatur und einen RFC-3161-Zeitstempel. Ein zusätzlicher Zeitstempeldienst erhält nur den Hash des Manifests. | [ADR-007](../adr/ADR-007-unveraenderbarkeit-governance.md), [Spec 01](../../.kiro/specs/01-archiv-kern/requirements.md) |
| Sicherung | Sicherungsexporte werden täglich erstellt und serverseitig asymmetrisch mit `age` verschlüsselt, bevor sie den Server verlassen. GitHub Actions transportiert ausschliesslich die verschlüsselten Sicherungsdaten. | [Sicherung](../../deploy/backup/README.md), [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md) |
| Wiederherstellung | Wiederherstellung und Prüfung der Sicherungen sind dokumentiert; eine getrennte Restore-Probe prüft Datenbanken und Dateien. | [Wiederherstellung](../wiederherstellung.md), [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md) |

Die Massnahmenbeschreibung ersetzt keine Prüfung, ob zusätzliche Massnahmen für die konkrete Bearbeitung oder
besondere Risiken erforderlich sind. Änderungen an den Massnahmen, die den Schutz wesentlich vermindern, werden
dem Verantwortlichen vor ihrer Umsetzung mitgeteilt.

## 6. Unterauftragsbearbeiter und weitere Dienstleister

UriNet setzt für die vereinbarten Leistungen folgende Dienstleister ein. Die Angaben in spitzen Klammern sind vor
Unterzeichnung zu vervollständigen. Es werden keine Zugangsdaten oder Schlüssel in dieser Vereinbarung festgehalten.

| Dienstleister | Leistung | Bearbeitungsort und Einschränkung |
| --- | --- | --- |
| Infomaniak, `<zuständige Vertragsgesellschaft>` | Hosting, Object Storage und KI-Dienste | Schweiz; genaue Gesellschaft und Orte gemäss den massgeblichen Vertragsunterlagen eintragen. |
| Hostpoint AG, `<vollständige Vertragsangaben>` | Vorgesehene Zweitkopie der Sicherungen | Schweiz; die Sicherungen werden vor dem Transport mit `age` verschlüsselt. Einsatz und Vertragsangaben sind vor Unterzeichnung zu bestätigen; ein allfälliger Wechsel ist vor dessen Einsatz zu dokumentieren. |
| Swiss Government PKI (BIT), `<Vertragsangaben>` | Haupt-Zeitstempeldienst | Schweiz; erhält Zeitstempelanfragen für Manifeste. |
| FreeTSA oder `<tatsächlich konfigurierter Zweitdienst und Vertragspartei>` | Vorgesehener zweiter Zeitstempeldienst | Kann ausserhalb der Schweiz liegen und erhält ausschliesslich den Hash eines Manifests, keine Dokumente oder sonstigen Inhalte. Dienst und Vertragspartei sind vor Unterzeichnung zu bestätigen. |
| GitHub Actions, `<Vertragsangaben>` | Transport der Sicherungsdaten im Sicherungsworkflow | Der Runner kann ausserhalb der Schweiz betrieben werden und erhält ausschliesslich bereits verschlüsselte Sicherungsdaten. |

UriNet stellt dem Verantwortlichen vor der Unterzeichnung die verfügbaren Vertrags- und Datenschutzunterlagen der
Dienstleister zur Prüfung bereit. Die Parteien vereinbaren, wie UriNet Änderungen oder den Ersatz eines
Unterauftragsbearbeiters ankündigt und wie der Verantwortliche Einwände erheben kann: `<Frist und Verfahren>`.

## 7. Datenstandort und Übermittlungen

Echte Archivdaten werden in der Schweiz gespeichert und verarbeitet. Die KI-Dienste und der primäre Object Storage
werden über Infomaniak erbracht; die Zweitkopie wird bei einem zweiten Schweizer Anbieter geführt. Die Parteien
halten folgende eng begrenzten Ausnahmen fest:

- GitHub Actions kann ausserhalb der Schweiz betrieben werden, transportiert aber nur Daten, die der Server vorher
  asymmetrisch verschlüsselt hat. Der Runner erhält keinen Schlüssel zur Entschlüsselung.
- Ein zweiter Zeitstempeldienst kann ausserhalb der Schweiz liegen, weil er ausschliesslich einen Manifest-Hash
  erhält und keine Dokumentinhalte.

Weitere Bekanntgaben oder Übermittlungen ausserhalb dieser Ausnahmen bedürfen einer dokumentierten Weisung und
einer vorgängigen Prüfung der anwendbaren Datenschutzanforderungen: `<Zuständigkeit und Verfahren>`.

## 8. Datenschutzvorfälle

UriNet meldet dem Verantwortlichen einen bestätigten oder begründet vermuteten Vorfall, der Personendaten dieser
Bearbeitung betrifft, ohne unangemessene Verzögerung und spätestens innert `<vereinbarte Frist, z. B. 24 Stunden
ab Kenntnisnahme>` nach Kenntnisnahme. Diese Frist ist ein Vertragsvorschlag und keine Aussage über eine gesetzliche
Frist.

Die Meldung enthält, soweit zu diesem Zeitpunkt bekannt, Art und Umfang des Vorfalls, betroffene Daten und
Bearbeitungen, mögliche Folgen, bereits getroffene Massnahmen und eine Kontaktstelle `<Kontaktweg>`. UriNet
aktualisiert die Angaben, sobald wesentliche neue Informationen vorliegen, und unterstützt den Verantwortlichen
bei der Untersuchung und den erforderlichen Meldungen. UriNet dokumentiert den Vorfall und die ergriffenen
Massnahmen.

## 9. Rechte betroffener Personen und Kontrolle

Der Verantwortliche bearbeitet Anfragen betroffener Personen. UriNet leitet an ihn gerichtete Anfragen, die diese
Bearbeitung betreffen, unverzüglich an den Verantwortlichen weiter und leistet auf Weisung angemessene
Unterstützung. Die Parteien vereinbaren eine Kontaktstelle und ein Verfahren: `<Kontakt und Verfahren>`.

UriNet stellt dem Verantwortlichen die zum Nachweis der Einhaltung dieser Vereinbarung erforderlichen
Informationen zur Verfügung und ermöglicht verhältnismässige Kontrollen. Umfang, Vorankündigung, Häufigkeit,
Vertraulichkeit und Kostentragung werden wie folgt vereinbart: `<Auditverfahren und Kosten>`. Kontrollen dürfen
andere Mandanten und deren Daten nicht offenlegen und die Sicherheit des Archivs nicht beeinträchtigen.

## 10. Rückgabe und Löschung bei Vertragsende

1. Auf Weisung des Verantwortlichen erstellt UriNet einen Vollexport als ZIP. Er umfasst die Originale,
   vorhandene Arbeitsfassungen und PDF/A-Dateien, Metadaten als JSON und CSV, die Manifeste, je Dokument die
   Klassifikationen mit Code und Name samt Konfigurationsversion, die aktuelle Konfigurationsdatei und alle
   Konfigurationsversionen des Mandanten sowie den OKF-Export der Wissensbasis (Spec 03, Req 8.2). Die Übergabe
   erfolgt an `<Empfänger und sicherer Übergabeweg>`. Der Verantwortliche bestätigt die vollständige Übernahme
   schriftlich.
2. Nach bestätigter Übergabe wird der Mandant deaktiviert. Nach Ablauf von 30 Tagen werden, vorbehältlich Absatz 3,
   das Mandantenschema und die Bucket-Inhalte gelöscht, soweit keine Object-Lock-Sperre entgegensteht. Zugleich
   wird der Mandantenzugang entfernt: Die Zuordnung von Benutzern und Rollen im Archiv besteht nicht mehr. Die
   gemeinsamen Keycloak-Clients sind nicht mandantenspezifisch und bleiben bestehen. Gesperrte Objekte bleiben bis
   zum Ablauf ihrer Sperre bestehen und werden im Abbau-Bericht mit dem jeweiligen Fristende aufgeführt.
3. Datenbank-, Realm- und Manifest-Schlüssel-Sicherungsstände werden nach der festgelegten Aufbewahrung
   ausgedünnt: 30 Tagesstände, 12 Monatsstände und 10 Jahresstände. Die Zuwächse der Bucket-Sicherung werden nicht ausgedünnt; sie bleiben
   gemäss der aktuellen Systemregel unbefristet erhalten, bis eine Löschfunktion einen geregelten Weg dafür
   festlegt. Der Abbau-Bericht hält fest, bis wann Datenbankdaten noch in Sicherungsständen enthalten sind und
   weist für Bucket-Zuwächse auf die unbefristete Aufbewahrung hin.
4. Der Abbau-Bericht dokumentiert die Deaktivierung, den Vollexport, die Löschschritte, verbleibende gesperrte
   Objekte und die Aufbewahrung in der Zweitkopie. Der Verantwortliche erhält eine Kopie des Berichts.

Die Parteien legen den Zeitpunkt für die Bestätigung des Vollexports und die sichere Übergabe fest:
`<Verfahren>`. Die Frist von 30 Tagen beginnt mit der bestätigten Übergabe, sofern die Parteien nicht schriftlich
einen anderen rechtlich geprüften Auslöser festlegen.

Bestätigt der Verantwortliche die Übernahme nicht oder erteilt er nach Vertragsende keine Weisung zum Vollexport, fragt
UriNet nach und setzt eine Nachfrist von `<Nachfrist>`. Nach deren Ablauf `<Vorgehen, z. B. Abbau auch ohne
Bestätigung oder erneute Nachfrage; rechtlich zu klären>`. Ohne eine solche Regelung bliebe der Mandant
unbefristet bestehen.

## 11. Haftung

Die Haftung richtet sich nach dem Hauptvertrag und dem anwendbaren Recht. Besondere Haftungsregelungen, Obergrenzen
und Ausnahmen: `<durch die Parteien und ihre Rechtsberatung festzulegen>`.

## 12. Schlussbestimmungen

Änderungen und Ergänzungen dieser Vereinbarung bedürfen der Textform, soweit nicht zwingendes Recht eine andere
Form verlangt. Sollte eine Bestimmung unwirksam sein, bleiben die übrigen Bestimmungen bestehen; die Parteien
ersetzen die betroffene Bestimmung durch eine wirksame Regelung, die ihrem Zweck möglichst nahekommt.

Es gilt Schweizer Recht. Gerichtsstand: `<Ort, rechtlich prüfen>`.

| Für den Verantwortlichen | Für UriNet |
| --- | --- |
| Ort/Datum: `<...>` | Ort/Datum: `<...>` |
| Name/Funktion: `<...>` | Name/Funktion: `<...>` |
| Unterschrift: `<...>` | Unterschrift: `<...>` |

## Beiblatt: Fragen an die Juristin oder den Juristen

1. Sind Parteibezeichnungen, Rechtsformen, Vertretungsbefugnisse und die Rollen als Verantwortlicher und
   Auftragsbearbeiterin für diesen Auftrag zutreffend?
2. Sind Datenarten und Gruppen betroffener Personen vollständig? Wie ist mit besonderen Personendaten umzugehen,
   die entgegen dem vorgesehenen Zweck in Verbandsdokumenten vorkommen könnten?
3. Sind die genannten Unterauftragsbearbeiter, Vertragsgesellschaften, Bearbeitungsorte und Vertragsunterlagen
   vollständig und aktuell? Sind Ankündigungsfrist, Einwendungsrecht und ein allfälliger Anbieterwechsel
   ausreichend geregelt?
4. Genügen die beschriebenen Ausnahmen für den verschlüsselten Transport über GitHub Actions und den
   hash-basierten zweiten Zeitstempeldienst den anwendbaren Anforderungen?
5. Ist die vorgeschlagene Vorfallfrist angemessen, und welche Kontakt- und Eskalationswege sollen gelten?
6. Sind Vollexport, schriftliche Übernahmebestätigung, Beginn der 30-Tage-Frist, Object-Lock-Ausnahmen und
   Abbau-Bericht rechtlich und praktisch eindeutig geregelt?
7. Wie sind die unterschiedlichen Sicherungsfristen zu formulieren, insbesondere die unbefristete Aufbewahrung
   der Bucket-Zuwächse bis zur geregelten Löschfunktion? Ist sie mit der Löschpflicht und der Weisung des
   Verantwortlichen vereinbar, obwohl Ziffer 10 Absatz 2 «gelöscht» zusagt (Vorbehalt Absatz 3)?
8. Sind Auditverfahren, Kostenregelung, Haftung, anwendbares Recht und Gerichtsstand mit dem Hauptvertrag
   abgestimmt?
9. Offener Punkt A2: Welche Aufbewahrungsfrist der Chat-Fragen soll im Vertrag stehen (Systemstandard 90 Tage,
   dem Verband zugesagt 30 Tage), und ist die Zusage verbindlich, sodass die Mandantenkonfiguration auf 30 Tage
   zu setzen ist?
10. Wie ist der Fall zu regeln, dass nach Vertragsende keine Weisung oder keine Übernahmebestätigung erfolgt
   (Nachfrist, Abbau von Amtes wegen, Löschgrundsatz bei Vertragsende)?
11. Welche weiteren TOM, Nachweise oder vertraglichen Anpassungen sind angesichts der konkreten Daten und
   Bearbeitungszwecke erforderlich?

### Verwendete Repository-Grundlagen

- [Spec 03, Requirements 6.5 und 8.2–8.5](../../.kiro/specs/03-zweiter-mandant/requirements.md)
- [Spec 01, Requirement 16](../../.kiro/specs/01-archiv-kern/requirements.md)
- [Technikregeln](../../.kiro/steering/tech.md)
- [ADR-002: Schema pro Mandant](../adr/ADR-002-schema-pro-mandant-liquibase.md)
- [ADR-007: Unveränderbarkeit und Object Lock](../adr/ADR-007-unveraenderbarkeit-governance.md)
- [ADR-008: Sicherung und Zweitkopie](../adr/ADR-008-sicherung-zweitkopie.md)
- [ADR-009: Rollen und Rechtematrix](../adr/ADR-009-rollen-rechtematrix.md)
- [Wiederherstellungsanleitung](../wiederherstellung.md)
- [Alarme und Kennzahlen](../alarme.md)
