import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { KontaktDetail, Suchergebnis } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KontaktDetailSeite } from './kontakt-detail';
import { DETAIL, KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';

const T = TEXTE.kontakte;

describe('Kontakte: Detail (Req 17.3, 17.4, 17.6)', () => {
  let port: KontakteAttrappe;
  let harness: RouterTestingHarness;
  let element: HTMLElement;
  const rolle = signal<Rolle>('inhaber');

  async function oeffnen(id = 'k1') {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'kontakte/:id', component: KontaktDetailSeite },
          { path: 'kontakte', component: KontaktDetailSeite },
        ]),
        { provide: KontaktePort, useValue: port },
        { provide: AnmeldungService, useValue: { darf: (a: Aktion) => darf(rolle(), a) } },
      ],
    });
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/kontakte/${id}`);
    element = harness.routeNativeElement as HTMLElement;
    await harness.fixture.whenStable();
  }

  async function laden(detail: KontaktDetail, nr = port.lesenAntworten.length - 1) {
    port.lesenAntworten[nr].next(detail);
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  const el = (test: string) => element.querySelector<HTMLElement>(`[data-test=${test}]`);
  const klick = async (test: string) => {
    el(test)!.click();
    await harness.fixture.whenStable();
    harness.detectChanges();
  };
  const text = (test: string) => el(test)!.textContent!.trim();

  beforeEach(() => {
    port = new KontakteAttrappe();
    rolle.set('inhaber');
  });

  it('zeigt Stammdaten, Kontakt-ID, Herkunft und «nicht mehr im CRM-Export»', async () => {
    await oeffnen();
    expect(port.lesenAnfragen).toEqual(['k1']);
    await laden({ ...DETAIL, nichtImExport: true, vorname: 'Anna' });
    expect(text('kontakt-name')).toBe('Anna Muster Garage AG');
    expect(text('wert-name')).toBe('Muster Garage AG');
    expect(text('wert-vorname')).toBe('Anna');
    expect(text('wert-typ')).toBe('Organisation');
    expect(text('wert-strasse')).toBe('Teststrasse 1');
    expect(text('wert-plz')).toBe('6460');
    expect(text('wert-ort')).toBe('Altdorf');
    expect(text('wert-email')).toBe('info@muster-garage.example');
    expect(text('wert-telefon')).toBe('041 000 00 00');
    expect(text('wert-uid')).toBe('CHE-000.000.000');
    expect(text('wert-iban')).toBe('CH0000000000000000000');
    expect(text('wert-kategorie')).toBe('Kunde');
    expect(text('wert-crmId')).toBe('4711');
    expect(text('wert-herkunft')).toBe(T.herkunftImport);
    expect(text('wert-export')).toBe(T.nichtImExportJa);
    expect(text('kennzeichen')).toContain(T.nichtImExport);
    expect(el('inaktiv-hinweis')).toBeNull();
  });

  it('zeigt leere Felder als Strich und Namen mit Markup nur als Text', async () => {
    await oeffnen();
    await laden({
      ...DETAIL,
      name: '<script>alert(1)</script>קבלה',
      email: null,
      iban: null,
      crmId: null,
      quelle: 'MANUELL',
    });
    expect(element.querySelector('script')).toBeNull();
    expect(text('wert-name')).toBe('<script>alert(1)</script>קבלה');
    expect(text('wert-email')).toBe(T.leer);
    expect(text('wert-crmId')).toBe(T.leer);
    expect(text('wert-herkunft')).toBe(T.herkunftManuell);
  });

  it('zeigt bei einem inaktiven, zusammengeführten Kontakt das Ziel und die Quellen als Links', async () => {
    await oeffnen();
    await laden({
      ...DETAIL,
      aktiv: false,
      inaktivSeit: '2026-11-13T09:00:00Z',
      zusammengefuehrtIn: { id: 'k2', name: 'Ziel AG' },
      zusammengefuehrtAus: [
        { id: 'k3', name: 'Alt A' },
        { id: 'k4', name: 'Alt B' },
      ],
    });
    expect(text('kennzeichen')).toContain('inaktiv seit');
    expect(el('inaktiv-hinweis')).not.toBeNull();
    expect(el('in-link')!.getAttribute('href')).toBe('/kontakte/k2');
    expect(el('in-link')!.textContent).toBe('Ziel AG');
    expect(
      [...element.querySelectorAll('[data-test=aus-link]')].map((a) => a.getAttribute('href')),
    ).toEqual(['/kontakte/k3', '/kontakte/k4']);
  });

  describe('Aktionen nur mit A12 (Req 17.6)', () => {
    it('zeigt dem Inhaber Bearbeiten, Zusammenführen und Inaktivmarkieren', async () => {
      await oeffnen();
      await laden(DETAIL);
      expect(['bearbeiten', 'zusammenfuehren', 'inaktiv'].map((t) => text(t))).toEqual([
        T.bearbeiten,
        T.zusammenfuehren,
        T.inaktivMarkieren,
      ]);
      expect(el('wieder-aktivieren')).toBeNull();
    });

    it.each(['geschaeftsstelle'] as Rolle[])(
      'zeigt der Rolle %s Liste und Detail, aber keine Aktionen',
      async (r) => {
        rolle.set(r);
        await oeffnen();
        await laden(DETAIL);
        expect(text('wert-name')).toBe('Muster Garage AG');
        expect(el('aktionen')).toBeNull();
        expect(element.querySelector('button[data-test]:not([data-test=zurueck])')).toBeNull();
      },
    );

    it('bietet einem inaktiven Kontakt nur «Wieder aktivieren» an', async () => {
      await oeffnen();
      await laden({ ...DETAIL, aktiv: false, inaktivSeit: '2026-11-13T09:00:00Z' });
      expect(el('bearbeiten')).toBeNull();
      expect(el('zusammenfuehren')).toBeNull();
      expect(el('inaktiv')).toBeNull();
      expect(text('wieder-aktivieren')).toBe(T.wiederAktivieren);
    });

    it('bietet einem zusammengeführten Kontakt auch «Wieder aktivieren» nicht an', async () => {
      await oeffnen();
      await laden({ ...DETAIL, aktiv: false, zusammengefuehrtIn: { id: 'k2', name: 'Ziel AG' } });
      expect(el('wieder-aktivieren')).toBeNull();
    });
  });

  describe('Fehler beim Laden', () => {
    it('zeigt bei 404 die Seite «nicht gefunden» ohne Kontaktdaten', async () => {
      await oeffnen('fremd');
      port.lesenAntworten[0].error(new HttpErrorResponse({ status: 404 }));
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(element.querySelector('[data-test=nicht-gefunden] h1')!.textContent).toBe(
        T.nichtGefundenTitel,
      );
      expect(element.textContent).toContain(T.nichtGefunden);
      expect(el('stammdaten')).toBeNull();
      expect(el('dokumente')).toBeNull();
    });

    it('zeigt bei 403 nur die Meldung, nie Kontaktdaten', async () => {
      await oeffnen();
      port.lesenAntworten[0].error(new HttpErrorResponse({ status: 403 }));
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(text('fehler')).toContain(T.fehlerVerboten);
      expect(el('stammdaten')).toBeNull();
      expect(el('aktionen')).toBeNull();
    });

    it('bietet bei einem Serverfehler einen neuen Versuch an', async () => {
      await oeffnen();
      port.lesenAntworten[0].error(new HttpErrorResponse({ status: 500 }));
      await harness.fixture.whenStable();
      harness.detectChanges();
      await klick('erneut');
      expect(port.lesenAnfragen).toEqual(['k1', 'k1']);
      await laden(DETAIL);
      expect(text('wert-name')).toBe('Muster Garage AG');
    });
  });

  describe('Wieder aktivieren', () => {
    const inaktiv = { ...DETAIL, aktiv: false, inaktivSeit: '2026-11-13T09:00:00Z', version: 5 };

    it('sendet If-Match mit der Version des Details und lädt neu', async () => {
      await oeffnen();
      await laden(inaktiv);
      await klick('wieder-aktivieren');
      expect(port.aktionen).toEqual([{ art: 'aktiv', id: 'k1', version: 5 }]);
      expect(port.lesenAnfragen).toHaveLength(2);
      await laden(DETAIL);
      expect(text('erfolg')).toContain(T.wiederAktiviert);
    });

    it('lädt bei 412 neu und weist darauf hin («inzwischen geändert»)', async () => {
      port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 412 }));
      await oeffnen();
      await laden(inaktiv);
      await klick('wieder-aktivieren');
      expect(port.lesenAnfragen).toHaveLength(2);
      await laden({ ...inaktiv, version: 6 });
      expect(text('veraltet')).toBe(T.veraltet);
      expect(el('erfolg')).toBeNull();
    });

    it('lädt auch bei 428 neu', async () => {
      port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 428 }));
      await oeffnen();
      await laden(inaktiv);
      await klick('wieder-aktivieren');
      expect(port.lesenAnfragen).toHaveLength(2);
    });

    it('meldet 409 verständlich', async () => {
      port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 409 }));
      await oeffnen();
      await laden(inaktiv);
      await klick('wieder-aktivieren');
      expect(text('aktion-fehler')).toBe(T.konflikt.aktiv);
    });

    it('sendet nicht doppelt, solange die Anfrage läuft', async () => {
      port.aktionAntwort = () => ({ subscribe: () => ({ unsubscribe: () => undefined }) }) as never;
      await oeffnen();
      await laden(inaktiv);
      await klick('wieder-aktivieren');
      await klick('wieder-aktivieren');
      expect(port.aktionen).toHaveLength(1);
      expect((el('wieder-aktivieren') as HTMLButtonElement).disabled).toBe(true);
    });
  });

  describe('Bearbeiten, Zusammenführen und Inaktivmarkieren', () => {
    it('öffnet das Formular und lädt nach dem Speichern neu mit Erfolgsmeldung', async () => {
      await oeffnen();
      await laden(DETAIL);
      await klick('bearbeiten');
      expect(el('bearbeiten-formular')).not.toBeNull();
      const ort = element.querySelector<HTMLInputElement>('[data-test=feld-ort]')!;
      ort.value = 'Luzern';
      ort.dispatchEvent(new Event('input'));
      await klick('speichern');
      expect(port.aktionen).toEqual([
        { art: 'aendern', id: 'k1', version: 3, daten: { ort: 'Luzern' } },
      ]);
      expect(el('bearbeiten-formular')).toBeNull();
      await laden({ ...DETAIL, ort: 'Luzern', version: 4 });
      expect(text('erfolg')).toBe(T.gespeichert);
      expect(text('wert-ort')).toBe('Luzern');
    });

    it('lädt bei 412 neu, weist darauf hin und behält die Eingabe im offenen Formular', async () => {
      port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 412 }));
      await oeffnen();
      await laden(DETAIL);
      await klick('bearbeiten');
      const ort = element.querySelector<HTMLInputElement>('[data-test=feld-ort]')!;
      ort.value = 'Luzern';
      ort.dispatchEvent(new Event('input'));
      await klick('speichern');
      await laden({ ...DETAIL, telefon: '041 111 11 11', version: 4 });
      expect(text('veraltet')).toBe(T.veraltet);
      expect(text('wert-telefon')).toBe('041 111 11 11');
      expect(element.querySelector<HTMLInputElement>('[data-test=feld-ort]')!.value).toBe('Luzern');
    });

    it('schreibt nach 412 keine fremd geänderten Felder mit dem alten Wert zurück', async () => {
      let n = 0;
      port.aktionAntwort = () =>
        n++ === 0 ? throwError(() => new HttpErrorResponse({ status: 412 })) : of(DETAIL);
      await oeffnen();
      await laden({ ...DETAIL, telefon: '', version: 3 });
      await klick('bearbeiten');
      const ort = element.querySelector<HTMLInputElement>('[data-test=feld-ort]')!;
      ort.value = 'Luzern';
      ort.dispatchEvent(new Event('input'));
      await klick('speichern');
      await laden({ ...DETAIL, telefon: '041 111 11 11', version: 4 });
      expect(element.querySelector<HTMLInputElement>('[data-test=feld-telefon]')!.value).toBe(
        '041 111 11 11',
      );
      await klick('speichern');
      expect(port.aktionen[1]).toEqual({
        art: 'aendern',
        id: 'k1',
        version: 4,
        daten: { ort: 'Luzern' },
      });
    });

    it('fragt vor dem Inaktivmarkieren zurück und sendet erst nach der Bestätigung', async () => {
      await oeffnen();
      await laden(DETAIL);
      await klick('inaktiv');
      expect(el('dialog')).not.toBeNull();
      expect(port.aktionen).toEqual([]);
      await klick('inaktiv-ausfuehren');
      expect(port.aktionen).toEqual([
        { art: 'inaktiv', id: 'k1', version: 3, daten: { grund: null } },
      ]);
      expect(el('dialog')).toBeNull();
      await laden({ ...DETAIL, aktiv: false, inaktivSeit: '2026-11-14T09:00:00Z' });
      expect(text('erfolg')).toBe(T.inaktivMarkiert);
    });

    it('schliesst die Rückfrage mit «Abbrechen», ohne etwas zu senden', async () => {
      await oeffnen();
      await laden(DETAIL);
      await klick('inaktiv');
      await klick('inaktiv-abbrechen');
      expect(el('dialog')).toBeNull();
      expect(port.aktionen).toEqual([]);
    });

    it('zeigt nach dem Zusammenführen die Meldung mit Link auf das Ziel und der Quelle als inaktiv', async () => {
      port.suchenTreffer = [{ id: 'k2', name: 'Ziel AG', plz: '6000', ort: 'Luzern' }];
      await oeffnen();
      await laden(DETAIL);
      await klick('zusammenfuehren');
      const suche = element.querySelector<HTMLInputElement>('[data-test=ziel-suche]')!;
      suche.value = 'Ziel';
      suche.dispatchEvent(new Event('input'));
      await new Promise((fertig) => setTimeout(fertig, 350));
      harness.detectChanges();
      await klick('ziel-vorschlag');
      const grund = element.querySelector<HTMLTextAreaElement>(
        '[data-test=zusammenfuehren-grund]',
      )!;
      grund.value = 'Dublette aus dem Import';
      grund.dispatchEvent(new Event('input'));
      await klick('weiter');
      port.aktionAntwort = () =>
        of({ ziel: 'k2', dokumente: 7, verkaufschancen: 2, korrekturbeispiele: 1 });
      await klick('zusammenfuehren-ausfuehren');
      expect(port.aktionen).toEqual([
        {
          art: 'zusammenfuehren',
          id: 'k1',
          version: 3,
          daten: { ziel: 'k2', grund: 'Dublette aus dem Import' },
        },
      ]);
      await laden({ ...DETAIL, aktiv: false, zusammengefuehrtIn: { id: 'k2', name: 'Ziel AG' } });
      expect(text('erfolg')).toContain('zusammengeführt in «Ziel AG»');
      expect(text('erfolg')).toContain('7 Dokumente');
      expect(el('ziel-link')!.getAttribute('href')).toBe('/kontakte/k2');
      expect(text('kennzeichen')).toContain('inaktiv');
    });
  });

  describe('Dokumente des Kontakts (Req 17.3)', () => {
    const treffer = (id: string, titel: string | null, dateiname: string | null = null) => ({
      id,
      titel,
      dateiname,
      art: 'AUSGANGSRECHNUNG',
      datum: '2026-11-08',
      eingang: '2026-11-08T08:00:00Z',
      status: 'ARCHIVIERT' as const,
      vertraulichkeit: 'NORMAL' as const,
      tags: [],
      auszug: [],
    });
    const ergebnis = (gesamt: number, ...t: ReturnType<typeof treffer>[]): Suchergebnis => ({
      treffer: t,
      gesamt,
      seite: 0,
      groesse: 20,
    });

    it('lädt die Dokumente mit Partner, ohne q, mit Blättern und zeigt die Zahl des Archivs', async () => {
      await oeffnen();
      await laden(DETAIL);
      expect(port.dokumentAnfragen).toEqual([{ id: 'k1', seite: 0, groesse: 20 }]);
      port.dokumentAntworten[0].next(
        ergebnis(45, treffer('d1', 'Rechnung 2026-003'), treffer('d2', null, 'Scan_1.pdf')),
      );
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(text('dokumente-anzahl')).toContain('45 Dokumente');
      const zeilen = [...element.querySelectorAll<HTMLElement>('[data-test=dokument]')];
      expect(zeilen[0].querySelector('a')!.getAttribute('href')).toBe('/dokument/d1');
      expect(zeilen[0].textContent).toContain('Rechnung 2026-003');
      expect(zeilen[0].textContent).toContain('Ausgangsrechnung');
      expect(zeilen[0].textContent).toContain('08.11.2026');
      expect(zeilen[1].querySelector('[data-test=titel-platzhalter]')!.textContent).toContain(
        'Scan_1.pdf',
      );
      await klick('dokumente-naechste');
      expect(port.dokumentAnfragen.at(-1)).toEqual({ id: 'k1', seite: 1, groesse: 20 });
    });

    it('zeigt ohne sichtbare Dokumente nur «Keine Dokumente zu sehen» und keine eigene Zahl', async () => {
      await oeffnen();
      await laden(DETAIL);
      port.dokumentAntworten[0].next(ergebnis(0));
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(text('dokumente-keine')).toBe(T.dokumenteKeine);
      expect(element.querySelector('[data-test=dokumente-vorherige]')).toBeNull();
    });

    it('meldet einen Fehler beim Laden der Dokumente, ohne das Detail zu verdecken', async () => {
      await oeffnen();
      await laden(DETAIL);
      port.dokumentAntworten[0].error(new HttpErrorResponse({ status: 500 }));
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(text('dokumente-fehler')).toContain(T.dokumenteFehler);
      expect(text('wert-name')).toBe('Muster Garage AG');
    });
  });
});
