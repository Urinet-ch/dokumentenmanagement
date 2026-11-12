import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { Dokumentart, Kontakt, Suchergebnis, Suchtreffer } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Suche } from './suche';
import { Suchanfrage, SuchePort } from './suche-port';

class SucheAttrappe extends SuchePort {
  anfragen: Suchanfrage[] = [];
  antworten: Subject<Suchergebnis>[] = [];
  kontaktAnfragen: string[] = [];
  suchen(anfrage: Suchanfrage): Observable<Suchergebnis> {
    this.anfragen.push(anfrage);
    const antwort = new Subject<Suchergebnis>();
    this.antworten.push(antwort);
    return antwort;
  }
  dokumentarten(): Observable<Dokumentart[]> {
    return of([{ code: 'AUSGANGSRECHNUNG', name: 'Ausgangsrechnung' } as Dokumentart]);
  }
  kontakte(q: string): Observable<Kontakt[]> {
    this.kontaktAnfragen.push(q);
    return of([]);
  }
}

const treffer = (werte: Partial<Suchtreffer>): Suchtreffer => ({
  id: 'd1',
  eingang: '2026-11-08T08:00:00Z',
  status: 'ARCHIVIERT',
  vertraulichkeit: 'NORMAL',
  tags: [],
  auszug: [],
  ...werte,
});

const ergebnis = (...liste: Suchtreffer[]): Suchergebnis => ({ treffer: liste, gesamt: liste.length, seite: 0, groesse: 20 });

describe('Suche (Req 14.1, 14.2, 14.4, 14.7)', () => {
  let fixture: ComponentFixture<Suche>;
  let port: SucheAttrappe;
  let element: HTMLElement;
  const benutzer = signal({ id: 'u1', name: 'Joel', mandant: 'urinet', rolle: 'mitarbeiter' });

  async function oeffnen() {
    fixture = TestBed.createComponent(Suche);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  beforeEach(async () => {
    port = new SucheAttrappe();
    benutzer.set({ id: 'u1', name: 'Joel', mandant: 'urinet', rolle: 'mitarbeiter' });
    TestBed.configureTestingModule({
      imports: [Suche],
      providers: [
        provideRouter([]),
        { provide: SuchePort, useValue: port },
        {
          provide: AnmeldungService,
          useValue: { benutzer, darf: (aktion: string) => aktion === 'A6' && benutzer().rolle === 'inhaber' },
        },
      ],
    });
    await oeffnen();
  });

  async function antworten(e: Suchergebnis) {
    port.antworten.at(-1)!.next(e);
    await fixture.whenStable();
  }

  function eingeben(name: string, wert: string, ereignis = 'input') {
    const feld = element.querySelector<HTMLInputElement | HTMLSelectElement>(`[name=${name}]`)!;
    feld.value = wert;
    feld.dispatchEvent(new Event(ereignis));
  }

  async function absenden() {
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('sucht beim Öffnen ohne Filter und zeigt die Treffer', async () => {
    expect(port.anfragen).toEqual([{ seite: 0, groesse: 20 }]);
    await antworten(
      ergebnis(
        treffer({ titel: 'Rechnung', art: 'AUSGANGSRECHNUNG', belegnummer: '2026-003', betrag: 1234.5, waehrung: 'CHF' }),
      ),
    );
    const eintrag = element.querySelector('[data-test=treffer]')!;
    expect(eintrag.textContent).toContain('Ausgangsrechnung');
    expect(eintrag.textContent).toContain("CHF 1'234.50");
    expect(eintrag.querySelector('[data-test=belegnummer]')!.textContent).toBe('2026-003');
    expect(eintrag.querySelector('a')!.getAttribute('href')).toBe('/dokument/d1');
  });

  it('hebt die Fundstelle als <mark> hervor, ohne HTML aus dem Text zu übernehmen (Req 14.1)', async () => {
    await antworten(
      ergebnis(treffer({ auszug: [{ text: 'Miete <b>Mai</b> ', treffer: false }, { text: 'Seedorf', treffer: true }] })),
    );
    const auszug = element.querySelector('[data-test=auszug]')!;
    expect([...auszug.querySelectorAll('mark')].map((m) => m.textContent)).toEqual(['Seedorf']);
    expect(auszug.querySelector('b')).toBeNull();
    expect(auszug.textContent).toContain('<b>Mai</b>');
  });

  it('sendet Suchbegriff, Filter und Belegnummer an das Archiv (Req 14.2, 14.7)', async () => {
    await antworten(ergebnis());
    eingeben('q', 'Rechnung');
    eingeben('art', 'AUSGANGSRECHNUNG', 'change');
    eingeben('betragVon', "1'000");
    eingeben('belegnummer', '2026-003');
    await absenden();
    expect(port.anfragen.at(-1)).toEqual({
      seite: 0,
      groesse: 20,
      q: 'Rechnung',
      sortierung: 'RELEVANZ',
      art: 'AUSGANGSRECHNUNG',
      betragVon: '1000',
      belegnummer: '2026-003',
    });
  });

  it('meldet ungültige Eingaben, ohne das Archiv zu fragen', async () => {
    await antworten(ergebnis());
    eingeben('geschaeftsjahr', '26');
    await absenden();
    expect(port.anfragen.length).toBe(1);
    expect(element.querySelector('[data-test=pruefung]')!.textContent).toBe(TEXTE.suche.pruefung['geschaeftsjahr']);
  });

  it('meldet eine abgelehnte Anfrage verständlich', async () => {
    port.antworten[0].error(new HttpErrorResponse({ status: 400 }));
    await fixture.whenStable();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toBe(TEXTE.suche.fehlerAnfrage);
  });

  it('filtert ohne Recht A6 über den Partner eines Treffers, ohne Kontakte abzufragen (Req 14.2)', async () => {
    await antworten(ergebnis(treffer({ partnerId: 'k1', partnerName: 'FC Schattdorf' })));
    expect(element.querySelector('[name=partner]')).toBeNull();
    element.querySelector<HTMLButtonElement>('.nach-partner')!.click();
    await fixture.whenStable();
    expect(port.anfragen.at(-1)).toMatchObject({ partner: 'k1' });
    expect(element.querySelector('[data-test=partnerfilter]')!.textContent).toContain('FC Schattdorf');
    expect(port.kontaktAnfragen).toEqual([]);
  });

  it('behält die letzte Suche für «Zurück», verwirft sie aber für einen anderen Benutzer', async () => {
    eingeben('q', 'Miete');
    await antworten(ergebnis(treffer({ titel: 'Mietvertrag' })));
    fixture.destroy();
    await oeffnen();
    expect(port.anfragen.length).toBe(1);
    expect(element.querySelector<HTMLInputElement>('[name=q]')!.value).toBe('Miete');
    expect(element.querySelector('[data-test=treffer]')!.textContent).toContain('Mietvertrag');

    fixture.destroy();
    benutzer.set({ id: 'u2', name: 'Fabian', mandant: 'urinet', rolle: 'inhaber' });
    await oeffnen();
    expect(element.querySelector<HTMLInputElement>('[name=q]')!.value).toBe('');
    expect(port.anfragen.length).toBe(2);
  });

  it('legt Suchbegriffe weder in die Adresse noch in den Browser-Speicher', async () => {
    eingeben('q', 'Lohn');
    await absenden();
    await antworten(ergebnis());
    expect(location.href).not.toContain('Lohn');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('Lohn');
  });
});
