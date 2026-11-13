import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Dokument, provideApi } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, darf } from '../gemeinsam/anmeldung/rechte';
import { EingangskorbZaehler } from '../gemeinsam/eingangskorb-zaehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Eingangskorb } from './eingangskorb';

const T = TEXTE.eingangskorb;

const dokument = (id: string, titel: string): Dokument => ({
  id,
  titel,
  eingang: '2026-11-08T09:30:00Z',
  quelle: 'UPLOAD',
  tags: [],
  vertraulichkeit: 'PERSONAL',
  status: 'EINGANGSKORB',
  fristAbgelaufen: false,
  version: 1,
  warnungen: [],
  pdfaFehlt: false,
  unsicher: [],
  versionen: [],
  art: 'QUITTUNG',
});

class ZaehlerAttrappe extends EingangskorbZaehler {
  readonly anzahl = signal<number | null>(null);
  aufgefrischt = 0;
  override auffrischen() {
    this.aufgefrischt++;
  }
}

describe('Eingangskorb (Req 8.1, 8.6)', () => {
  let fixture: ComponentFixture<Eingangskorb>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let zaehler: ZaehlerAttrappe;

  beforeEach(async () => {
    zaehler = new ZaehlerAttrappe();
    TestBed.configureTestingModule({
      imports: [Eingangskorb],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        { provide: AnmeldungService, useValue: { darf: (a: Aktion) => darf('inhaber', a) } },
        { provide: EingangskorbZaehler, useValue: zaehler },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Eingangskorb);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    http.expectOne('/api/v1/dokumentarten').flush([]);
  });

  afterEach(() => http.verify());

  async function seite(seite: number, eintraege: Dokument[], gesamt: number) {
    http
      .expectOne(`/api/v1/eingangskorb?groesse=20&seite=${seite}`)
      .flush({ eintraege, gesamt, seite, groesse: 20 });
    await fixture.whenStable();
  }

  const titel = () => [...element.querySelectorAll('h2')].map((h) => h.textContent?.trim());

  it('zeigt die Einträge in der Reihenfolge des Archivs und lädt weitere nach', async () => {
    await seite(0, [dokument('a', 'Neu'), dokument('b', 'Älter')], 3);
    expect(titel()).toEqual(['Neu', 'Älter']);
    expect(element.querySelector('[data-test=anzahl]')?.textContent).toContain(T.anzahl(2, 3));
    const mehr = [...element.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === T.mehr,
    )!;
    mehr.click();
    await seite(1, [dokument('c', 'Am ältesten')], 3);
    expect(titel()).toEqual(['Neu', 'Älter', 'Am ältesten']);
    expect(
      [...element.querySelectorAll('button')].some((b) => b.textContent?.trim() === T.mehr),
    ).toBe(false);
  });

  it('hängt beim Nachladen keine Dokumente doppelt an (Review PR #68, K2)', async () => {
    await seite(0, [dokument('a', 'Neu'), dokument('b', 'Älter')], 4);
    [...element.querySelectorAll('button')].find((b) => b.textContent?.trim() === T.mehr)!.click();
    await seite(1, [dokument('b', 'Älter'), dokument('c', 'Am ältesten')], 4);
    expect(titel()).toEqual(['Neu', 'Älter', 'Am ältesten']);
  });

  it('zeigt einen leeren Eingangskorb', async () => {
    await seite(0, [], 0);
    expect(element.querySelector('[data-test=leer]')?.textContent).toContain(T.leer);
  });

  it('lädt nach einer Aktion neu, meldet sie und frischt den Zähler auf', async () => {
    await seite(0, [dokument('a', 'Neu')], 1);
    [...element.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === T.bestaetigen)!
      .click();
    await fixture.whenStable();
    http
      .expectOne('/api/v1/dokumente/a/bestaetigen')
      .flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    await seite(0, [], 0);
    expect(element.querySelector('[data-test=meldung]')?.textContent).toContain(
      T.erfolgBestaetigt('Neu'),
    );
    expect(zaehler.aufgefrischt).toBe(1);
    expect(document.activeElement?.tagName).toBe('H1');
  });

  it('meldet einen Ladefehler mit «Neu laden»', async () => {
    http
      .expectOne('/api/v1/eingangskorb?groesse=20&seite=0')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(T.fehlerLaden);
  });
});
