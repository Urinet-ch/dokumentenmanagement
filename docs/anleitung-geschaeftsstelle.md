# Anleitung für die Geschäftsstelle
Die Bedienung des Archivs beschreibt die **Anleitung in der App**: Knopf «Hilfe» in der Kopfzeile oder Seite `/hilfe`
(Spec 07). Sie zeigt nur die Bereiche Ihrer Rolle und ist auch ohne Netz lesbar. Diese Datei enthält nur, was nicht in die
App gehört: Zugang und Kontaktstelle der Geschäftsstelle und, bis zum Entscheid zu Spec 07, Requirement 2.4, die Regeln
für die Webseite von VHF/GSK (Zugriffsstufe, Veröffentlichen). Bedienung wird nur in der App gepflegt
(Spec 07, Requirement 7.7).

| Thema | In der App |
| --- | --- |
| Anmelden, Navigation, Farbschema, Abmelden | `/hilfe#erste-schritte` |
| Dokumente hochladen, am Computer und am Handy, auch ohne Verbindung | `/hilfe#erfassen` |
| Eingangskorb prüfen, Angaben ergänzen und bestätigen | `/hilfe#eingangskorb` |
| Dokument suchen, öffnen und Angaben ändern | `/hilfe#suche`, `/hilfe#dokument` |
| Meldungen beim Hochladen («bereits vorhanden», Dateityp, Grösse) | `/hilfe#erfassen` |
| Sitzung abgelaufen, fehlendes Recht, kein Mandant oder keine Rolle | `/hilfe#probleme` |

## Zugang und Kontaktstelle
- Das Archiv öffnen Sie über den bereitgestellten Link: [Link zum Archiv eintragen].
- Wenn Ihrem Benutzer kein Mandant oder keine Rolle zugeordnet ist, erscheint: «Ihrem Benutzer ist kein Mandant oder keine
  Rolle im Archiv zugeordnet. Bitte wenden Sie sich an den Inhaber.» Mit «Inhaber» ist die Kontaktstelle gemeint: Wenden
  Sie sich an [Kontaktstelle Geschäftsstelle einsetzen].
- Die Kontaktstelle ist auch gemeint, wenn die Anleitung in der App auf «die Person, die Ihren Zugang eingerichtet hat»
  verweist, wenn eine Meldung bleibt und wenn unklar ist, zu welchem Verband ein Dokument gehört oder welche
  Zugriffsstufe es braucht.

## Angaben der Dokumentarten von VHF/GSK
Diese Angaben verlangt das Schema der Dokumentarten des Mandanten `vhf-gsk`; fehlt eine, meldet das Archiv beim
Bestätigen zum Beispiel «Bitte ergänzen: Verband.» und das Dokument bleibt im Eingangskorb.

| Dokumentart | Zusatzangaben laut Schema |
| --- | --- |
| Bulletin | Reihe, Ausgabe-Nr., Erscheinungsjahr, Fachgebiet, Verband |
| Jahresbericht | Erscheinungsjahr, Verband |
| Merkblatt | Verband |
| Norm oder Richtlinie | Verband |
| Vorstandsprotokoll | Verband |
| Formular | Verband |
| Sonstiges | Verband |

Beim Bulletin sind die Fachgebiete «Bad», «Eis», «Technik», «Recht» und «Allgemein».

**Verband wählen:** «Verband» ist eine Mehrfachauswahl mit den Kontrollkästchen «VHF» und «GSK». Setzen Sie ein Häkchen bei jedem Verband, zu dem das Dokument gehört; mindestens einer ist nötig (Hinweis unter dem Feld: «Mindestens einen Wert auswählen.»). Wählen Sie nicht auf Verdacht: Ist unklar, zu welchem Verband ein Dokument gehört, fragen Sie bei [Kontaktstelle Geschäftsstelle einsetzen] nach. Dasselbe Feld finden Sie bei «Metadaten bearbeiten»; dort wird ein Speichern ohne Verband ebenfalls abgelehnt.

## Zugriffsstufe und Webseite
Gilt bis zum Entscheid zu Spec 07, Requirement 2.4 (Satz zur Webseite im Mandanten `vhf-gsk`). Die Texte der App sind für
alle Mandanten gleich; einen eigenen Satz zur Webseite brächte nach 2.4 die Konfiguration des Mandanten, nicht die
Anleitung der App.

### Zugriffsstufe beim Bestätigen
Die «Zugriffsstufe» wählen Sie im Eingangskorb, bevor Sie «Bestätigen» wählen. «Veröffentlicht» steht dort nicht zur Wahl: Neue Dokumente sind nach dem Bestätigen immer unveröffentlicht und erhalten die Einstellung erst unter «Metadaten bearbeiten».

| Stufe | Bedeutung |
| --- | --- |
| Öffentlich | Für alle Besucher der Webseite |
| Mitglieder | Für Mitglieder, einschliesslich Vorstand |
| Vorstand | Nur für den Vorstand |

Das Feld kann vorbelegt sein. Verlassen Sie sich nicht darauf: Prüfen Sie die Stufe bei jedem Dokument und wählen Sie sie bewusst, auch beim «Vorstandsprotokoll».

**Achtung:** «Öffentlich» zusammen mit «Veröffentlicht» = «Ja» macht ein Dokument für alle Besucher der Webseite sichtbar. Wählen Sie im Zweifel die engere Stufe und fragen Sie bei [Kontaktstelle Geschäftsstelle einsetzen] nach.

Nach «Bestätigen» meldet das Archiv «[Titel]» bestätigt, wird archiviert. Das Dokument ist danach noch nicht veröffentlicht.

### Veröffentlichen oder zurückziehen
1. Suchen Sie das archivierte Dokument über «Suche» und öffnen Sie es. Kontrollieren Sie vorher die Zugriffsstufe: Eine falsche Stufe wirkt sofort, sobald «Veröffentlicht» auf «Ja» steht.
2. Wählen Sie «Metadaten bearbeiten».
3. Setzen Sie «Veröffentlicht» auf «Ja», um es für die Web-Schnittstelle freizugeben. Zum Zurückziehen setzen Sie das Feld auf «Nein».
4. Geben Sie bei «Grund der Änderung» einen kurzen Grund ein und wählen Sie «Änderungen speichern».

Die Web-Schnittstelle liefert nur archivierte Dokumente mit «Veröffentlicht» = «Ja» und Vertraulichkeit «Normal». Zusätzlich muss die Zugriffsstufe für die Webseite zugelassen sein. Ein «Vorstandsprotokoll» hat standardmässig die Vertraulichkeit «Intern» und wird deshalb nicht über die Web-Schnittstelle ausgegeben. Ändern Sie die Vertraulichkeit nur bewusst: Mit «Normal» wäre das Dokument bei «Veröffentlicht» = «Ja» und zugelassener Stufe abrufbar.

Nach dem Zurückziehen liefert die Schnittstelle das Dokument nicht mehr aus. Wann eine Webseite die Änderung abruft und anzeigt, ist nicht festgelegt. Eine bestimmte Aktualisierungszeit kann daher nicht zugesichert werden.

**«Ein Zusatzfeld passt nicht zur Art. Bitte die Zusatzfelder prüfen.»:** Eine Zusatzangabe passt nicht zur Dokumentart. Prüfen Sie die Angaben bei «Metadaten bearbeiten». Wenn die Meldung bleibt, setzen Sie «Veröffentlicht» nicht auf «Ja» und melden Sie es an [Kontaktstelle Geschäftsstelle einsetzen].

[Bildschirmfoto: «Veröffentlicht» und «Grund der Änderung» bei einem Testdokument]

## Bildschirmfotos ergänzen
Die markierte Stelle benötigt noch ein Bildschirmfoto mit synthetischen Testdaten. Es werden keine echten Dokumente oder Personendaten verwendet. Die Anleitung in der App kommt ohne Bildschirmfotos aus (Spec 07, Entscheid E9).
