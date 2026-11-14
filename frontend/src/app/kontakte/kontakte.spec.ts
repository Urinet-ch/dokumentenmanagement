import { BreakpointObserver } from '@angular/cdk/layout';
import { HttpErrorResponse } from '@angular/common/http';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { KontaktEintrag, KontaktListe } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Kontakte } from './kontakte';
import { KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';

const T = TEXTE.kontakte;
const eintrag = (teil: Partial<KontaktEintrag>): KontaktEintrag => ({
  id: 'k1',
  typ: 'ORGANISATION',
  name: 'Muster Garage AG',
  plz: '6460',
  ort: 'Altdorf',
  email: 'info@muster-garage.example',
  quelle: 'CSV_IMPORT',
  aktiv: true,
  nichtImExport: false,
  ...teil,
});
const liste = (gesamt: number, ...eintraege: KontaktEintrag[]): KontaktListe => ({
  gesamt,
  seite: 0,
  groesse: 25,
  eintraege,
});
const warten = (ms: number) => new Promise((fertig) => setTimeout(fertig, ms));

describe('Kontakte: Liste (Req 17.1, 17.2)', () => {
  let port: KontakteAttrappe;
  let harness: RouterTestingHarness;
  let element: HTMLElement;

  async function oeffnen(url = '/kontakte', mobil = true) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'kontakte', component: Kontakte },
          { path: 'kontakte/:id', component: Kontakte },
        ]),
        { provide: KontaktePort, useValue: port },
        {
          provide: BreakpointObserver,
          useValue: { observe: () => of({ matches: mobil, breakpoints: {} }) },
        },
      ],
    });
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    element = harness.routeNativeElement as HTMLElement;
    await harness.fixture.whenStable();
  }

  async function antworten(l: KontaktListe, nr = port.listeAntworten.length - 1) {
    port.listeAntworten[nr].next(l);
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  const url = () => TestBed.inject(Location).path();

  beforeEach(() => {
    port = new KontakteAttrappe();
  });

  it('fragt beim Öffnen aktive Kontakte ohne Suchbegriff ab und zeigt die Gesamtzahl', async () => {
    await oeffnen();
    expect(port.listeAnfragen).toEqual([
      {
        q: undefined,
        status: 'AKTIV',
        quelle: undefined,
        nichtImExport: undefined,
        seite: 0,
        groesse: 25,
      },
    ]);
    expect(element.querySelector('mat-progress-bar')).not.toBeNull();
    await antworten(liste(2, eintrag({}), eintrag({ id: 'k2', name: 'Zweite AG' })));
    expect(element.querySelector('[data-test=anzahl]')!.textContent).toContain('2 Kontakte');
    expect(element.querySelectorAll('[data-test=kontakt]')).toHaveLength(2);
    expect(element.querySelector('mat-progress-bar')).toBeNull();
  });

  it('zeigt auf dem Handy Karten mit Name, Ort, E-Mail und Kennzeichen', async () => {
    await oeffnen();
    await antworten(
      liste(
        3,
        eintrag({ vorname: 'Anna' }),
        eintrag({
          id: 'k2',
          name: 'Alt AG',
          aktiv: false,
          quelle: 'MANUELL',
          email: null,
          nichtImExport: true,
        }),
        eintrag({ id: 'k3', name: 'Neu AG', quelle: 'MANUELL' }),
      ),
    );
    const karten = [...element.querySelectorAll<HTMLElement>('[data-test=kontakt]')];
    expect(element.querySelector('table')).toBeNull();
    expect(karten[0].querySelector('a')!.getAttribute('href')).toBe('/kontakte/k1');
    expect(karten[0].textContent).toContain('Anna Muster Garage AG');
    expect(karten[0].textContent).toContain('6460 Altdorf');
    expect(karten[0].querySelector('[data-test=email]')!.textContent).toBe(
      'info@muster-garage.example',
    );
    const marken = (k: HTMLElement) =>
      [...k.querySelectorAll('.marken li')].map((m) => m.textContent!.trim());
    expect(marken(karten[0])).toEqual([T.kennzeichenImport]);
    expect(marken(karten[1])).toEqual([
      T.kennzeichenInaktiv,
      T.kennzeichenManuell,
      T.kennzeichenNichtImExport,
    ]);
    expect(marken(karten[2])).toEqual([T.kennzeichenManuell]);
    expect(karten[1].querySelector('[data-test=email]')).toBeNull();
  });

  it('zeigt ab 960 px eine Tabelle mit denselben Angaben', async () => {
    await oeffnen('/kontakte', false);
    await antworten(liste(1, eintrag({ vorname: 'Anna', aktiv: false })));
    const zeile = element.querySelector('table [data-test=kontakt]')!;
    expect(element.querySelectorAll('thead th')).toHaveLength(6);
    expect(
      [...zeile.querySelectorAll('td')].map((td) => td.textContent!.replace(/\s+/g, ' ').trim()),
    ).toEqual([
      'Muster Garage AG',
      'Anna',
      'Organisation',
      '6460 Altdorf',
      'info@muster-garage.example',
      'inaktivImport',
    ]);
    expect(zeile.querySelector('a')!.getAttribute('href')).toBe('/kontakte/k1');
  });

  it('zeigt Namen mit Markup und Schrift von rechts nach links nur als Text', async () => {
    await oeffnen();
    await antworten(
      liste(1, eintrag({ name: '<img src=x onerror=alert(1)>קבלה', vorname: '<b>Fett</b>' })),
    );
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('b')).toBeNull();
    expect(element.querySelector('.name')!.textContent).toContain(
      '<b>Fett</b> <img src=x onerror=alert(1)>קבלה',
    );
  });

  it('zeigt den leeren Zustand', async () => {
    await oeffnen();
    await antworten(liste(0));
    expect(element.querySelector('[data-test=keine]')!.textContent).toContain(T.keine);
    expect(element.querySelector('nav.blaettern')).toBeNull();
  });

  it('wartet 300 ms nach der letzten Eingabe, sendet dann einmal und schreibt den Begriff in die Adresse', async () => {
    await oeffnen();
    await antworten(liste(0));
    const feld = element.querySelector<HTMLInputElement>('[data-test=suche]')!;
    for (const text of ['g', 'ga', 'gar']) {
      feld.value = text;
      feld.dispatchEvent(new Event('input'));
      await warten(50);
    }
    expect(port.listeAnfragen).toHaveLength(1);
    await warten(300);
    await harness.fixture.whenStable();
    expect(port.listeAnfragen).toHaveLength(2);
    expect(port.listeAnfragen[1].q).toBe('gar');
    expect(port.listeAnfragen[1].seite).toBe(0);
    expect(url()).toBe('/kontakte?q=gar');
  });

  it('sendet einen leeren Begriff ohne q und entfernt ihn aus der Adresse', async () => {
    await oeffnen('/kontakte?q=gar');
    expect(port.listeAnfragen[0].q).toBe('gar');
    await antworten(liste(0));
    const feld = element.querySelector<HTMLInputElement>('[data-test=suche]')!;
    expect(feld.value).toBe('gar');
    feld.value = '   ';
    feld.dispatchEvent(new Event('input'));
    await warten(350);
    await harness.fixture.whenStable();
    expect(port.listeAnfragen.at(-1)!.q).toBeUndefined();
    expect(url()).toBe('/kontakte');
  });

  it('übernimmt dieselbe Eingabe nach der Zurück-Taste erneut (Feld und Liste stimmen überein)', async () => {
    await oeffnen('/kontakte?q=mueller');
    await antworten(liste(0));
    const feld = () => element.querySelector<HTMLInputElement>('[data-test=suche]')!;
    const tippen = async (text: string) => {
      feld().value = text;
      feld().dispatchEvent(new Event('input'));
      await warten(350);
      await harness.fixture.whenStable();
    };
    await tippen('');
    expect(url()).toBe('/kontakte');
    await harness.navigateByUrl('/kontakte?q=mueller'); // wie die Zurück-Taste
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(feld().value).toBe('mueller');
    await tippen('');
    expect(url()).toBe('/kontakte');
    expect(port.listeAnfragen.at(-1)!.q).toBeUndefined();
  });

  it('sendet einen ungültigen Begriff (Steuerzeichen, über 200 Zeichen) nie als q und sagt es', async () => {
    await oeffnen(`/kontakte?q=${encodeURIComponent('a\u0001b')}`);
    expect(port.listeAnfragen[0].q).toBeUndefined();
    await antworten(liste(0));
    expect(element.querySelector('[data-test=such-ungueltig]')!.textContent).toContain(
      T.suchUngueltig,
    );
    await harness.navigateByUrl(`/kontakte?q=${'x'.repeat(250)}`);
    await harness.fixture.whenStable();
    expect(port.listeAnfragen.at(-1)!.q).toBeUndefined();
  });

  it('verwirft die Antwort einer veralteten Anfrage', async () => {
    await oeffnen('/kontakte?q=alt');
    await harness.navigateByUrl('/kontakte?q=neu');
    await harness.fixture.whenStable();
    expect(port.listeAnfragen.map((a) => a.q)).toEqual(['alt', 'neu']);
    await antworten(liste(1, eintrag({ id: 'neu', name: 'Neuer Treffer' })), 1);
    await antworten(liste(1, eintrag({ id: 'alt', name: 'Alter Treffer' })), 0);
    expect(element.textContent).toContain('Neuer Treffer');
    expect(element.textContent).not.toContain('Alter Treffer');
  });

  it('liest Suchbegriff, Filter und Seite aus der Adresse (Zurück-Taste führt zur selben Liste)', async () => {
    await oeffnen('/kontakte?q=ga&status=ALLE&quelle=MANUELL&nichtImExport=true&seite=2');
    expect(port.listeAnfragen[0]).toEqual({
      q: 'ga',
      status: 'ALLE',
      quelle: 'MANUELL',
      nichtImExport: true,
      seite: 2,
      groesse: 25,
    });
    await antworten(liste(80, eintrag({})));
    expect(element.querySelector<HTMLSelectElement>('[data-test=filter-status]')!.value).toBe(
      'ALLE',
    );
    expect(element.querySelector<HTMLSelectElement>('[data-test=filter-quelle]')!.value).toBe(
      'MANUELL',
    );
    expect(element.querySelector<HTMLInputElement>('[data-test=filter-export]')!.checked).toBe(
      true,
    );
    expect(element.querySelector('.blaettern')!.textContent).toContain('Seite 3 von 4');
  });

  it('setzt jeden Filter in die Adresse und beginnt wieder auf der ersten Seite', async () => {
    await oeffnen('/kontakte?seite=3');
    await antworten(liste(100, eintrag({})));
    const status = element.querySelector<HTMLSelectElement>('[data-test=filter-status]')!;
    status.value = 'INAKTIV';
    status.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(url()).toBe('/kontakte?status=INAKTIV');
    expect(port.listeAnfragen.at(-1)).toMatchObject({ status: 'INAKTIV', seite: 0 });

    const quelle = element.querySelector<HTMLSelectElement>('[data-test=filter-quelle]')!;
    quelle.value = 'CSV_IMPORT';
    quelle.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(port.listeAnfragen.at(-1)).toMatchObject({ status: 'INAKTIV', quelle: 'CSV_IMPORT' });

    const haken = element.querySelector<HTMLInputElement>('[data-test=filter-export]')!;
    haken.checked = true;
    haken.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(port.listeAnfragen.at(-1)!.nichtImExport).toBe(true);
    expect(url()).toBe('/kontakte?status=INAKTIV&quelle=CSV_IMPORT&nichtImExport=true');

    (element.querySelector('[data-test=zuruecksetzen]') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/kontakte');
    expect(port.listeAnfragen.at(-1)).toMatchObject({
      status: 'AKTIV',
      quelle: undefined,
      nichtImExport: undefined,
    });
  });

  it('blättert mit der Seite in der Adresse', async () => {
    await oeffnen();
    await antworten(liste(60, eintrag({})));
    const naechste = element.querySelector<HTMLButtonElement>('[data-test=naechste]')!;
    const vorherige = element.querySelector<HTMLButtonElement>('[data-test=vorherige]')!;
    expect(vorherige.disabled).toBe(true);
    naechste.click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/kontakte?seite=1');
    expect(port.listeAnfragen.at(-1)!.seite).toBe(1);
    await antworten(liste(60, eintrag({ id: 'k9' })));
    expect(element.querySelector('.blaettern')!.textContent).toContain('Seite 2 von 3');
    vorherige.click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/kontakte');
  });

  it('zeigt bei 403 nur die Meldung und nie Kontaktdaten', async () => {
    await oeffnen();
    await antworten(liste(1, eintrag({ name: 'Geheim AG' })));
    port.listeAntworten.length = 0;
    await harness.navigateByUrl('/kontakte?q=x');
    port.listeAntworten[0].error(new HttpErrorResponse({ status: 403 }));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toContain(T.fehlerVerboten);
    expect(element.textContent).not.toContain('Geheim AG');
    expect(element.querySelector('[data-test=erneut]')).toBeNull();
  });

  it('bietet bei einem Serverfehler einen neuen Versuch an', async () => {
    await oeffnen();
    port.listeAntworten[0].error(new HttpErrorResponse({ status: 500 }));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toContain(T.fehlerAllgemein);
    (element.querySelector('[data-test=erneut]') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    expect(port.listeAnfragen).toHaveLength(2);
    await antworten(liste(1, eintrag({})));
    expect(element.querySelector('[data-test=fehler]')).toBeNull();
    expect(element.querySelectorAll('[data-test=kontakt]')).toHaveLength(1);
  });

  it('zeigt bei fehlender Verbindung die Verbindungsmeldung', async () => {
    await oeffnen();
    port.listeAntworten[0].error(new HttpErrorResponse({ status: 0 }));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toContain(T.fehlerVerbindung);
  });

  it('nutzt den Router ohne die Seite neu zu laden, wenn nur das Suchfeld fokussiert wird', async () => {
    await oeffnen();
    await antworten(liste(0));
    expect(TestBed.inject(Router).url).toBe('/kontakte');
    expect(port.listeAnfragen).toHaveLength(1);
  });
});
