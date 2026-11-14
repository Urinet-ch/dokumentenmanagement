// Attrappe des Ports für die Vitests der Kontakte-Seiten: hält fest, was die Seite sendet, und antwortet über Subjects.
import { Observable, Subject, of } from 'rxjs';
import {
  Dokumentart,
  Kontakt,
  KontaktAngaben,
  KontaktDetail,
  KontaktListe,
  KontaktVerweise,
  KontaktZusammengefuehrt,
  Suchergebnis,
} from '../gemeinsam/api';
import { KontaktePort, ListenAnfrage } from './kontakte-port';

export const DETAIL: KontaktDetail = {
  id: 'k1',
  crmId: '4711',
  typ: 'ORGANISATION',
  name: 'Muster Garage AG',
  vorname: null,
  strasse: 'Teststrasse 1',
  plz: '6460',
  ort: 'Altdorf',
  email: 'info@muster-garage.example',
  telefon: '041 000 00 00',
  uid: 'CHE-000.000.000',
  iban: 'CH0000000000000000000',
  kategorie: 'Kunde',
  quelle: 'CSV_IMPORT',
  nichtImExport: false,
  aktiv: true,
  inaktivSeit: null,
  zusammengefuehrtIn: null,
  zusammengefuehrtAus: [],
  erstellt: '2026-11-10T08:00:00Z',
  geaendert: '2026-11-12T08:00:00Z',
  version: 3,
};

export class KontakteAttrappe extends KontaktePort {
  listeAnfragen: ListenAnfrage[] = [];
  listeAntworten: Subject<KontaktListe>[] = [];
  lesenAnfragen: string[] = [];
  lesenAntworten: Subject<KontaktDetail>[] = [];
  suchenAnfragen: string[] = [];
  suchenTreffer: Kontakt[] = [];
  dokumentAnfragen: { id: string; seite: number; groesse: number }[] = [];
  dokumentAntworten: Subject<Suchergebnis>[] = [];
  verweiseAntwort: Observable<KontaktVerweise> = of({
    dokumente: 7,
    verkaufschancen: 2,
    korrekturbeispiele: 1,
  });
  aktionen: { art: string; id: string; version: number; daten?: unknown }[] = [];
  aktionAntwort: () => Observable<unknown> = () => of({});

  liste(a: ListenAnfrage): Observable<KontaktListe> {
    this.listeAnfragen.push(a);
    const s = new Subject<KontaktListe>();
    this.listeAntworten.push(s);
    return s;
  }
  lesen(id: string): Observable<KontaktDetail> {
    this.lesenAnfragen.push(id);
    const s = new Subject<KontaktDetail>();
    this.lesenAntworten.push(s);
    return s;
  }
  verweise(): Observable<KontaktVerweise> {
    return this.verweiseAntwort;
  }
  suchen(q: string): Observable<Kontakt[]> {
    this.suchenAnfragen.push(q);
    return of(this.suchenTreffer);
  }
  dokumente(id: string, seite: number, groesse: number): Observable<Suchergebnis> {
    this.dokumentAnfragen.push({ id, seite, groesse });
    const s = new Subject<Suchergebnis>();
    this.dokumentAntworten.push(s);
    return s;
  }
  dokumentarten(): Observable<Dokumentart[]> {
    return of([{ code: 'AUSGANGSRECHNUNG', name: 'Ausgangsrechnung' } as Dokumentart]);
  }
  aendern(id: string, version: number, daten: KontaktAngaben): Observable<Kontakt> {
    this.aktionen.push({ art: 'aendern', id, version, daten });
    return this.aktionAntwort() as Observable<Kontakt>;
  }
  zusammenfuehren(
    id: string,
    version: number,
    ziel: string,
    grund: string,
  ): Observable<KontaktZusammengefuehrt> {
    this.aktionen.push({ art: 'zusammenfuehren', id, version, daten: { ziel, grund } });
    return this.aktionAntwort() as Observable<KontaktZusammengefuehrt>;
  }
  inaktivMarkieren(id: string, version: number, grund: string | null): Observable<Kontakt> {
    this.aktionen.push({ art: 'inaktiv', id, version, daten: { grund } });
    return this.aktionAntwort() as Observable<Kontakt>;
  }
  aktivieren(id: string, version: number): Observable<Kontakt> {
    this.aktionen.push({ art: 'aktiv', id, version });
    return this.aktionAntwort() as Observable<Kontakt>;
  }
}
