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
  kontaktListe: Kontakt[] = [];
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
    return of(this.kontaktListe);
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

  function schalter(): HTMLInputElement | null {
    return element.querySelector<HTMLInputElement>('[name=volltext]');
  }

  async function umschalten(an: boolean) {
    const feld = schalter()!;
    feld.checked = an;
    feld.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  async function absenden() {
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('sucht beim Öffnen ohne Filter und zeigt die Treffer', async () => {
    expect(port.anfragen).toEqual([{ modus: 'volltext', seite: 0, groesse: 20 }]);
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
      modus: 'hybrid',
      q: 'Rechnung',
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

  it('blättert in der zuletzt gesendeten Suche, nicht in noch nicht abgeschickten Filtern', async () => {
    await antworten(ergebnis());
    eingeben('q', 'Miete');
    await fixture.whenStable();
    await umschalten(true);
    await absenden();
    await antworten({ ...ergebnis(treffer({})), gesamt: 45 });
    eingeben('belegnummer', '2026-003');
    eingeben('q', 'Lohn');
    element.querySelector<HTMLButtonElement>('nav.blaettern button:last-of-type')!.click();
    await fixture.whenStable();
    expect(port.anfragen.at(-1)).toEqual({ modus: 'volltext', seite: 1, groesse: 20, q: 'Miete', sortierung: 'RELEVANZ' });
  });

  it('zeigt den gewählten Partner mit vollem Namen und Ort wie in den Vorschlägen (A6)', async () => {
    benutzer.set({ id: 'u2', name: 'Fabian', mandant: 'urinet', rolle: 'inhaber' });
    fixture.destroy();
    await oeffnen();
    await antworten(ergebnis());
    port.kontaktListe = [{ id: 'k7', typ: 'PERSON', vorname: 'Anna', name: 'Muster', ort: 'Altdorf' }];
    eingeben('partner', 'Mus');
    await new Promise((fertig) => setTimeout(fertig, 350));
    await fixture.whenStable();
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Anna Muster'))!.click();
    await fixture.whenStable();
    expect(port.anfragen.at(-1)).toMatchObject({ partner: 'k7' });
    expect(element.querySelector('[data-test=partnerfilter]')!.textContent).toContain('Anna Muster, Altdorf');
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

  describe('Modus hybrid und Volltext (Spec 02, Req 2.7)', () => {
    const dreissigUndMehr = (n: number) =>
      Array.from({ length: n }, (_, i) => treffer({ id: `d${i}`, titel: `Treffer ${String(n - i).padStart(2, '0')}` }));
    const hybridAntwort = (n: number, mehr: Partial<Suchergebnis> = {}): Suchergebnis => ({
      treffer: dreissigUndMehr(n),
      gesamt: n,
      seite: 0,
      groesse: 30,
      modus: 'hybrid',
      ...mehr,
    });

    async function hybridSuchen(n = 3, mehr: Partial<Suchergebnis> = {}) {
      await antworten(ergebnis());
      eingeben('q', 'Miete');
      await absenden();
      await antworten(hybridAntwort(n, mehr));
    }

    it('zeigt den Umschalter nur mit Suchbegriff und sendet beim Öffnen ausdrücklich Volltext', async () => {
      expect(port.anfragen[0].modus).toBe('volltext');
      await antworten(ergebnis());
      expect(schalter()).toBeNull();
      eingeben('q', '  ');
      await fixture.whenStable();
      expect(schalter()).toBeNull();
      eingeben('q', 'Miete');
      await fixture.whenStable();
      expect(schalter()!.checked).toBe(false);
      expect(element.querySelector('label:has([name=volltext])')!.textContent).toContain('Volltext');
    });

    it('zeigt im Modus hybrid die Treffer in gelieferter Reihenfolge, ohne Gesamtzahl und ohne Blättern', async () => {
      await hybridSuchen(30);
      expect(port.anfragen.at(-1)).toEqual({ modus: 'hybrid', q: 'Miete' });
      const titel = [...element.querySelectorAll('[data-test=treffer] a.titel')].map((a) => a.textContent!.trim());
      expect(titel.length).toBe(30);
      expect(titel[0]).toBe('Treffer 30');
      expect(titel.at(-1)).toBe('Treffer 01');
      expect(element.querySelector('[data-test=anzahl]')!.textContent).toContain(TEXTE.suche.besteTreffer);
      expect(element.querySelector('h2')!.textContent).not.toMatch(/\d/);
      expect(element.querySelector('.nur-screenreader[aria-live]')!.textContent!.trim()).toBe(TEXTE.suche.besteTreffer);
      expect(element.querySelector('nav.blaettern')).toBeNull();
      expect(element.querySelector('[data-test=nur-volltext]')).toBeNull();
    });

    it('zeigt bei ausbleibenden Treffern weiter «keine Treffer»', async () => {
      await hybridSuchen(0);
      expect(element.querySelector('[data-test=keine-treffer]')!.textContent).toBe(TEXTE.suche.keineTreffer);
    });

    it('bleibt im Volltext bei Anzahl und Blättern wie bisher', async () => {
      await antworten(ergebnis());
      eingeben('q', 'Miete');
      await fixture.whenStable();
      await umschalten(true);
      await absenden();
      await antworten({ ...ergebnis(treffer({})), gesamt: 45 });
      expect(element.querySelector('[data-test=anzahl]')!.textContent).toContain('45 Treffer');
      expect(element.querySelector('nav.blaettern')).not.toBeNull();
    });

    it('weist bei degradiert auf den Volltext hin, und der Knopf sucht im Volltext ab Seite 0', async () => {
      await hybridSuchen(3, { degradiert: true });
      const hinweis = element.querySelector('[data-test=nur-volltext]')!;
      expect(hinweis.getAttribute('role')).toBe('status');
      expect(hinweis.textContent).toContain(TEXTE.suche.nurVolltext);
      expect(element.querySelector('[data-test=anzahl]')!.textContent).not.toMatch(/\d/);
      hinweis.querySelector<HTMLButtonElement>('button')!.click();
      await fixture.whenStable();
      expect(port.anfragen.at(-1)).toEqual({ modus: 'volltext', seite: 0, groesse: 20, q: 'Miete', sortierung: 'RELEVANZ' });
      expect(schalter()!.checked).toBe(true);
      await antworten({ ...ergebnis(treffer({})), gesamt: 45 });
      expect(element.querySelector('[data-test=nur-volltext]')).toBeNull();
      expect(element.querySelector('[data-test=anzahl]')!.textContent).toContain('45 Treffer');
    });

    it('zeigt keinen Hinweis bei degradiert false', async () => {
      await hybridSuchen(3, { degradiert: false });
      expect(element.querySelector('[data-test=nur-volltext]')).toBeNull();
    });

    it('wiederholt bei einem Fehler nicht von sich aus im Volltext', async () => {
      await antworten(ergebnis());
      eingeben('q', 'Miete');
      await absenden();
      port.antworten.at(-1)!.error(new HttpErrorResponse({ status: 503 }));
      await fixture.whenStable();
      expect(element.querySelector('[data-test=fehler]')!.textContent).toBe(TEXTE.suche.fehlerAllgemein);
      expect(port.anfragen.length).toBe(2);
    });

    it('sendet beim Umschalten die zuletzt gesendete Anfrage im neuen Modus ab Seite 0', async () => {
      await hybridSuchen();
      eingeben('belegnummer', '2026-003');
      await umschalten(true);
      expect(port.anfragen.at(-1)).toEqual({ modus: 'volltext', seite: 0, groesse: 20, q: 'Miete', sortierung: 'RELEVANZ' });
      await antworten({ ...ergebnis(treffer({})), gesamt: 45 });
      await umschalten(false);
      expect(port.anfragen.at(-1)).toEqual({ modus: 'hybrid', q: 'Miete' });
    });

    it('koppelt Datum und Umschalter: Datum schaltet Volltext ein, Ausschalten setzt Relevanz', async () => {
      await hybridSuchen();
      eingeben('sortierung', 'DATUM', 'change');
      await fixture.whenStable();
      expect(schalter()!.checked).toBe(true);
      await absenden();
      expect(port.anfragen.at(-1)).toEqual({ modus: 'volltext', seite: 0, groesse: 20, q: 'Miete', sortierung: 'DATUM' });
      await antworten({ ...ergebnis(), gesamt: 2 });
      await umschalten(false);
      expect(element.querySelector<HTMLSelectElement>('[name=sortierung]')!.value).toBe('RELEVANZ');
      expect(port.anfragen.at(-1)).toEqual({ modus: 'hybrid', q: 'Miete' });
    });

    it('lässt Umschalter, Suchbegriff und Sortierung beim Zurücksetzen der Filter stehen', async () => {
      await hybridSuchen();
      await umschalten(true);
      await antworten(ergebnis());
      eingeben('belegnummer', '2026-003');
      element.querySelectorAll<HTMLButtonElement>('.filter-aktionen button')[1].click();
      await fixture.whenStable();
      expect(port.anfragen.at(-1)).toEqual({ modus: 'volltext', seite: 0, groesse: 20, q: 'Miete', sortierung: 'RELEVANZ' });
      expect(schalter()!.checked).toBe(true);
    });

    it('behält den Modus für «Zurück» und setzt ihn für einen anderen Benutzer zurück, ohne Speicher', async () => {
      await hybridSuchen();
      await umschalten(true);
      await antworten({ ...ergebnis(treffer({ titel: 'Mietvertrag' })), gesamt: 45 });
      fixture.destroy();
      await oeffnen();
      expect(schalter()!.checked).toBe(true);
      expect(element.querySelector('[data-test=anzahl]')!.textContent).toContain('45 Treffer');
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('volltext');

      fixture.destroy();
      benutzer.set({ id: 'u2', name: 'Fabian', mandant: 'urinet', rolle: 'inhaber' });
      await oeffnen();
      expect(schalter()).toBeNull();
      eingeben('q', 'Miete');
      await fixture.whenStable();
      expect(schalter()!.checked).toBe(false);
    });
  });
});
