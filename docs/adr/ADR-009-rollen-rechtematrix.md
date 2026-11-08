# ADR-009: Rollen eng nach Rechtematrix, 404 vor 403

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6)

**Datum:** 08.11.2026

## Kontext
- Im Archiv liegen Lohnabrechnungen, Arbeitsverträge und interne Unterlagen neben gewöhnlichen
  Belegen. Laut User Story von Req 2 sollen nur berechtigte Personen Dokumente sehen, damit Lohn-
  und Vertragsdaten geschützt sind.
- Im Mandanten `urinet` arbeiten der Inhaber, ein Mitarbeiter, dessen eigene Lohn- und
  Vertragsdaten im Archiv liegen, und ein externer Treuhänder, der für den Abschluss auch Lohnabrechnungen lesen muss.
  Mandant 2 (`vhf-gsk`) bringt später die Rolle `geschaeftsstelle` dazu.
- Die Sichtbarkeit hängt nicht nur an der Rolle, sondern auch am Dokument (Vertraulichkeit,
  `person_benutzer`, Erfasser, Bestätigungsstand). `@RolesAllowed` allein reicht dafür nicht.
- Eine Antwort 403 auf ein fremdes oder unsichtbares Dokument verrät, dass es existiert.
- Prüfentscheid P3 legt die Rollen bewusst «eng» fest. Laut `product.md` ist jede Zeile der Matrix
  Rolle × Aktion ein testbares Kriterium.

## Entscheid
1. **Rollen:** Die Realm-Rollen `inhaber`, `mitarbeiter`, `treuhaender` und `geschaeftsstelle`
   gehören zum Realm `urinet-archiv`. TOTP ist für alle Pflicht (Req 2.1). Der Mandant kommt nur
   aus dem Claim `mandant` (Regel 1, ADR-002).
2. **Verbindlich ist die Rechtematrix A1–A14 aus Req 2.6.** Sie wird als eine einzige Domain-Regel
   Rolle × Aktion umgesetzt, die alle Endpunkte abfragen (Aufgabe 1.4). In `application` setzt
   `Berechtigung` sie durch.
3. **Eng heisst:**
   - `mitarbeiter` erfasst (A1). Er liest nur `NORMAL` und die eigenen `PERSONAL`-Dokumente und
     sieht im Eingangskorb nur, was er selbst erfasst hat (Req 2.24). A6–A14 sind ihm verwehrt.
   - Im Mandanten `urinet` darf nur `inhaber` Metadaten ändern, bestätigen, die Vertraulichkeit
     setzen, verwerfen und wiederholen (A6–A10). Auch A11–A14 hat nur er.
   - `INTERN` sehen im Mandanten `urinet` nur `inhaber` und `treuhaender`.
   - `treuhaender` liest nur, und nur bestätigte Dokumente: `NORMAL`, `INTERN` und `PERSONAL`.
     Jede schreibende Aktion und der Eingangskorb ergeben 403 (Req 2.4, 2.7, 2.11).
   - `geschaeftsstelle` (Mandant 2) hat A1–A10 im eigenen Mandanten. Für A11–A14 gilt
     [offen: Rechte der Geschäftsstelle – Entscheid in `03-zweiter-mandant`], bis dahin 403.
4. **Ohne bestätigte Art gilt `PERSONAL`** (Req 2.21). Ein solches Dokument sehen nur Rollen mit A7
   und der Erfasser. Beim Bestätigen wird die bestätigte Vertraulichkeit übernommen (Req 2.22).
5. **`vertraulichkeit` und `person_benutzer`** ändern sich nur über A8 oder über die
   Generator-Metadaten (Req 2.23, 11.10). Enthält ein `PATCH` diese Felder und fehlt das Recht A8,
   antwortet das Archiv mit 403 für die ganze Anfrage.
6. **Der `VertraulichkeitFilter`** hängt die Sichtbarkeit nach A2–A4 an jede Dokumentabfrage:
   Liste, Suche, Zähler und Einzelabruf. Unsichtbares fehlt deshalb überall, nicht nur beim
   Direktzugriff (Req 2.3).
7. **Reihenfolge bei Endpunkten mit `{id}`:**
   1. Fremder Mandant oder unsichtbar: 404 `NICHT_GEFUNDEN`.
   2. Recht der Aktion fehlt: 403 `NICHT_ERLAUBT`.
   3. `If-Match` fehlt oder passt nicht: 428 bzw. 412.
   4. Status passt nicht: 409.
   5. Inhalt ungültig: 422.

   Die Fälle 1 und 2 werden mit der Aktion `VERWEIGERT` protokolliert (Req 10.7). Endpunkte ohne
   Dokumentbezug (A1, A11–A14) prüfen per `@RolesAllowed`, API-Tokens per Scope (Req 2.5).

## Betrachtete Alternativen
- **Weiter gefasste Rollen**, z. B. ein `mitarbeiter`, der bestätigt oder `INTERN` liest:
  Abgelehnt mit P3. Der Mitarbeiter ist selbst von Lohn- und Vertragsdaten betroffen, und im
  Mandanten `urinet` bestätigt nach P3 nur der Inhaber (menschliche Bestätigung: ADR-006).
- **403 statt 404 bei unsichtbaren Dokumenten:** Abgelehnt, weil 403 die Existenz des Dokuments
  verrät (Req 2.3, 2.6).
- **Nur `@RolesAllowed` je Endpunkt:** Reicht nicht. Die Sichtbarkeit hängt an Eigenschaften des
  Dokuments, und Listen, Suche und Zähler würden unsichtbare Dokumente durchsickern lassen.
- **Freie Rechte pro Dokument (ACL):** Allgemein flexibler, aber nicht als feste Matrix
  testbar. Die vier Rollen mit drei Vertraulichkeitsstufen decken den Bedarf ab.

## Folgen
- **Positiv:** Es gibt eine einzige Regel statt verstreuter Prüfungen. Die Matrix lässt sich als
  Tabelle gegen die Domain-Regel testen. Unsichtbare Dokumente verraten sich weder durch den
  Statuscode noch durch Zähler oder Suchtreffer.
- **Negativ:** Im Mandanten `urinet` hängen Bestätigen, Korrigieren und Verwerfen am Inhaber
  allein. Ist er abwesend, wächst der Eingangskorb, und der Mitarbeiter kann nicht aushelfen.
- **Umsetzung:** Jeder künftige Lesepfad muss über den `VertraulichkeitFilter` laufen, auch die von Spec 02.
  Das gilt auch für Zähler und Aggregate.
- **Tests:** Ein Scenario Outline deckt Rolle × A1–A14 ab (Req 2.6–2.20). Integrationstests prüfen
  404 vor 403 und laufen immer mit zwei Mandanten. Jede spätere Aufgabe ergänzt die Szenarien
  ihrer Matrixzeilen je Endpunkt (Aufgabe 1.4).
- **Betrieb:** Testbenutzer je Rolle liegen im Realm-Export (Aufgabe 1.2). Für den Treuhänder gibt
  es einen Notfallablauf in der Verfahrensbeschreibung, mit Notfallunterlagen statt zusätzlicher
  Rechte (P17, Req 16.6, 16.12).

## Verweise
- Req 1.4, 2.1–2.24, 10.7, 11.10
- P3, P17; `product.md` (Benutzer und Rollen)
- design.md, Abschnitt «Sicherheit (`sicherheit`)»
- Aufgaben 1.2, 1.4, 1.5
- ADR-002 (Mandant aus dem Token), ADR-006 (Bestätigung durch Menschen),
  ADR-010 (Keycloak bleibt)
