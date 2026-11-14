import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ImportLaufMitZeilen, ImporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ABFRAGE_MS, ABFRAGE_STOERUNG_MS, ImportLaufSeite, WARTEN_MAX_MS } from './import-lauf';

const ZUORDNUNG: ImportLaufMitZeilen = {
  lauf: { id: 'l1', art: 'kontakt', status: 'ZUORDNUNG_NOETIG', dateiname: 'kontakte.csv', zeilen: 2 },
  eintraege: [{ nr: 1, werte: ['Muster AG', '6460', 'Altdorf'] }],
  gesamt: 2,
  spalten: [
    { position: 0, name: 'Organisation' },
    { position: 1, name: 'PLZ' },
    { position: 2, name: 'Ort' },
  ],
  vorschlagZuordnung: { profil: '21.CRM', felder: { organisation: 0, plz: 1, ort: 2 } },
};

const K1 = '11111111-0000-0000-0000-000000000001';
const K2 = '22222222-0000-0000-0000-000000000002';

const VORSCHAU: ImportLaufMitZeilen = {
  lauf: {
    id: 'l1',
    art: 'kontakt',
    vollexport: true,
    status: 'VORSCHAU',
    zeilen: 4,
    neu: 1,
    geaendert: 1,
    unveraendert: 0,
    dubletten: 2,
    fehler: 0,
    uebersprungen: 0,
    inaktiv: 1,
  },
  eintraege: [
    { nr: 1, ergebnis: 'NEU', felder: { name: 'Neue GmbH', plz: '6000' }, kandidaten: [] },
    { nr: 2, ergebnis: 'DUBLETTE', meldung: 'mehrere Treffer', felder: { name: 'Muster Garage' }, kandidaten: [K1, K2] },
    { nr: 3, ergebnis: 'DUBLETTE', felder: { name: 'Doppelt' }, kandidaten: [] },
  ],
  gesamt: 3,
  kontakte: {
    [K1]: { name: 'Muster Garage AG', plz: '6460' },
    [K2]: { name: 'Muster Garage GmbH', plz: '6460' },
  },
};

describe('Import-Lauf (Aufgabe 4.4)', () => {
  let fixture: ComponentFixture<ImportLaufSeite>;
  let element: HTMLElement;
  const service = {
    apiV1ImporteIdGet: vi.fn(),
    apiV1ImporteCrmZuordnungPut: vi.fn(),
    apiV1ImporteIdUebernehmenPost: vi.fn(),
  };

  async function oeffnen(...antworten: (ImportLaufMitZeilen | HttpErrorResponse)[]) {
    service.apiV1ImporteIdGet.mockReset();
    const als = (a: ImportLaufMitZeilen | HttpErrorResponse) =>
      a instanceof HttpErrorResponse ? throwError(() => a) : of(a);
    for (const antwort of antworten) {
      service.apiV1ImporteIdGet.mockReturnValueOnce(als(antwort));
    }
    service.apiV1ImporteIdGet.mockReturnValue(als(antworten.at(-1)!));
    TestBed.configureTestingModule({
      imports: [ImportLaufSeite],
      providers: [provideRouter([]), { provide: ImporteService, useValue: service }],
    });
    fixture = TestBed.createComponent(ImportLaufSeite);
    fixture.componentRef.setInput('id', 'l1');
    element = fixture.nativeElement as HTMLElement;
    await stabil();
  }

  // Mit falschen Uhren wartet whenStable() nicht auf deren Ablauf; dann genügt ein Durchlauf mit detectChanges()
  const stabil = () => (vi.isFakeTimers() ? Promise.resolve(fixture.detectChanges()) : fixture.whenStable());

  beforeEach(() => {
    service.apiV1ImporteCrmZuordnungPut.mockReset().mockReturnValue(of({ id: 'l1' }));
    service.apiV1ImporteIdUebernehmenPost.mockReset().mockReturnValue(of({ id: 'l1' }));
  });

  afterEach(() => vi.useRealTimers());

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;
  const auswahlen = () => [...element.querySelectorAll<HTMLSelectElement>('[data-test=zuordnung] select')];

  async function waehlen(auswahl: HTMLSelectElement, wert: string) {
    auswahl.value = wert;
    auswahl.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  describe('Zuordnungsdialog (Req 13.2)', () => {
    it('zeigt jede Spalte mit Beispiel und dem Vorschlag, Profil «21.CRM» vorausgewählt', async () => {
      await oeffnen(ZUORDNUNG);
      expect(auswahlen().map((a) => a.value)).toEqual(['organisation', 'plz', 'ort']);
      expect(element.textContent).toContain('Spalte 1: «Organisation»');
      expect(element.textContent).toContain('Beispiel: Muster AG');
      expect(element.querySelector<HTMLInputElement>('[data-test=profil] input')!.checked).toBe(true);

      knopf(TEXTE.importe.zuordnungBestaetigen).click();
      expect(service.apiV1ImporteCrmZuordnungPut).toHaveBeenCalledWith(
        { laufId: 'l1', profil: '21.CRM', felder: { organisation: 0, plz: 1, ort: 2 } },
        'kontakt',
      );
    });

    it('sendet ohne Profil, wenn ein Profilfeld entfernt wurde, und verlangt Name oder Organisation', async () => {
      await oeffnen(ZUORDNUNG);
      await waehlen(auswahlen()[2], '');
      expect(element.querySelector<HTMLInputElement>('[data-test=profil] input')!.checked).toBe(false);
      await waehlen(auswahlen()[0], '');
      expect(element.querySelector('[data-test=pflicht]')!.textContent).toContain(TEXTE.importe.pflichtKontakt);
      expect(knopf(TEXTE.importe.zuordnungBestaetigen).disabled).toBe(true);

      await waehlen(auswahlen()[0], 'name');
      knopf(TEXTE.importe.zuordnungBestaetigen).click();
      expect(service.apiV1ImporteCrmZuordnungPut).toHaveBeenCalledWith(
        { laufId: 'l1', profil: null, felder: { plz: 1, name: 0 } },
        'kontakt',
      );
    });
  });

  describe('Vorschau und Übernahme (Req 13.3, 13.5–13.7)', () => {
    it('zeigt die Zähler und filtert die Zeilen je Ergebnis', async () => {
      await oeffnen(VORSCHAU);
      const chips = [...element.querySelectorAll<HTMLButtonElement>('.chip')];
      expect(chips.map((c) => c.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
        '4 Alle',
        '1 neu',
        '1 geändert',
        '0 unverändert',
        '2 mögliche Dublette',
        '0 fehlerhaft',
        '0 übersprungen',
        '1 inaktiv',
      ]);
      chips[4].click();
      await fixture.whenStable();
      expect(service.apiV1ImporteIdGet).toHaveBeenLastCalledWith('l1', 'DUBLETTE', 50, 0);
      expect(chips[4].getAttribute('aria-pressed')).toBe('true');
    });

    it('verlinkt getroffene und mögliche Kontakte auf die Kontaktseite (Req 17.1, 17.13)', async () => {
      await oeffnen({
        ...VORSCHAU,
        eintraege: [
          { nr: 4, ergebnis: 'INAKTIV', meldung: 'Kontakt inaktiv', felder: { name: 'Alte AG' }, kontaktId: K1, kandidaten: [] },
          ...(VORSCHAU.eintraege ?? []),
        ],
      });
      const getroffen = element.querySelector<HTMLAnchorElement>('[data-test=kontakt-link]')!;
      expect(getroffen.getAttribute('href')).toBe(`/kontakte/${K1}`);
      const kandidaten = [...element.querySelectorAll<HTMLAnchorElement>('[data-test=kandidat-link]')];
      expect(kandidaten.map((a) => a.getAttribute('href'))).toEqual([`/kontakte/${K1}`, `/kontakte/${K2}`]);
    });

    it('filtert die Zeilen auf «inaktiv» (Req 13.3)', async () => {
      await oeffnen(VORSCHAU);
      const chip = [...element.querySelectorAll<HTMLButtonElement>('.chip')].find((c) => c.dataset['ergebnis'] === 'INAKTIV')!;
      chip.click();
      await fixture.whenStable();
      expect(service.apiV1ImporteIdGet).toHaveBeenLastCalledWith('l1', 'INAKTIV', 50, 0);
    });

    it('zeigt Kandidaten und übernimmt erst nach Bestätigung mit den Entscheiden', async () => {
      await oeffnen(VORSCHAU);
      expect(element.textContent).toContain('«Muster Garage AG», 6460');
      const entscheide = [...element.querySelectorAll<HTMLSelectElement>('[data-test=entscheid]')];
      expect(entscheide).toHaveLength(2);
      expect([...entscheide[0].options].map((o) => o.value)).toEqual([
        '',
        'NEU',
        'UEBERSPRINGEN',
        `ZUSAMMENFUEHREN:${K1}`,
        `ZUSAMMENFUEHREN:${K2}`,
      ]);
      await waehlen(entscheide[0], `ZUSAMMENFUEHREN:${K2}`);
      expect(element.textContent).toContain('1 von 2 möglichen Dubletten entschieden');

      knopf(TEXTE.importe.uebernehmen).click();
      await fixture.whenStable();
      const frage = element.querySelector('[data-test=bestaetigen]')!;
      expect(frage.textContent).toContain('1 mögliche Dubletten ohne Entscheid werden ausgelassen');
      expect(frage.querySelector('[data-test=vollexport-folge]')!.textContent).toContain('nicht mehr im CRM-Export');
      expect(service.apiV1ImporteIdUebernehmenPost).not.toHaveBeenCalled();

      knopf(TEXTE.importe.jetztUebernehmen).click();
      expect(service.apiV1ImporteIdUebernehmenPost).toHaveBeenCalledWith('l1', {
        dublettenEntscheide: { '2': `ZUSAMMENFUEHREN:${K2}` },
      });
    });

    it('behält die Entscheide über Filter hinweg und nennt sie in der Bestätigung (Review PR #72, K1, K3)', async () => {
      await oeffnen(VORSCHAU);
      const auswahl = () => [...element.querySelectorAll<HTMLSelectElement>('[data-test=entscheid]')];
      await waehlen(auswahl()[0], `ZUSAMMENFUEHREN:${K1}`);
      await waehlen(auswahl()[1], 'NEU');
      const chips = () => [...element.querySelectorAll<HTMLButtonElement>('.chip')];
      chips()[1].click();
      await fixture.whenStable();
      chips()[0].click();
      await fixture.whenStable();
      expect(service.apiV1ImporteIdGet).toHaveBeenLastCalledWith('l1', undefined, 50, 0);
      expect(auswahl().map((a) => a.value)).toEqual([`ZUSAMMENFUEHREN:${K1}`, 'NEU']);

      knopf(TEXTE.importe.uebernehmen).click();
      await fixture.whenStable();
      expect(element.querySelector('[data-test=entscheide-folge]')!.textContent).toContain(
        TEXTE.importe.uebernehmenEntscheide('1', '1', '0'),
      );
      knopf(TEXTE.importe.jetztUebernehmen).click();
      expect(service.apiV1ImporteIdUebernehmenPost).toHaveBeenCalledWith('l1', {
        dublettenEntscheide: { '2': `ZUSAMMENFUEHREN:${K1}`, '3': 'NEU' },
      });
    });

    it('nennt Zeilen ohne angezeigte Werte (Feld zu lang)', async () => {
      await oeffnen({
        ...VORSCHAU,
        eintraege: [{ nr: 5, ergebnis: 'FEHLER', meldung: 'Feld zu lang', werte: null, felder: null }],
      });
      expect(element.querySelector('[data-test=werte-verworfen]')!.textContent).toContain(TEXTE.importe.werteVerworfen);
    });

    it('zeigt bei Verkaufschancen keine Entscheide', async () => {
      await oeffnen({ ...VORSCHAU, lauf: { ...VORSCHAU.lauf, art: 'verkaufschance', vollexport: false } });
      expect(element.querySelectorAll('[data-test=entscheid]')).toHaveLength(0);
      expect(element.querySelector('[data-test=dubletten-hinweis]')!.textContent).toContain(
        TEXTE.importe.dublettenVerkaufschance,
      );
    });

    it('meldet einen Fehler der Übernahme verständlich', async () => {
      await oeffnen(VORSCHAU);
      service.apiV1ImporteIdUebernehmenPost.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 0 })),
      );
      knopf(TEXTE.importe.uebernehmen).click();
      await fixture.whenStable();
      knopf(TEXTE.importe.jetztUebernehmen).click();
      await fixture.whenStable();
      expect(element.querySelector('[role=alert]')!.textContent).toContain(TEXTE.importe.fehlerVerbindung);
    });

    it('zeigt nach der Übernahme die Bilanz', async () => {
      await oeffnen({
        ...VORSCHAU,
        lauf: { ...VORSCHAU.lauf, status: 'UEBERNOMMEN', angelegt: 1, aktualisiert: 1, ausgelassen: 1, gekennzeichnet: 3 },
      });
      const bilanz = element.querySelector('[data-test=bilanz]')!.textContent!;
      expect(bilanz).toContain('1 angelegt');
      expect(bilanz).toContain('3 als «nicht mehr im CRM-Export» gekennzeichnet');
      expect(element.querySelectorAll('[data-test=entscheid]')).toHaveLength(0);
    });
  });

  describe('Statusabfrage', () => {
    const AUSSTEHEND: ImportLaufMitZeilen = {
      lauf: { id: 'l1', art: 'kontakt', status: 'VORSCHAU_AUSSTEHEND' },
      eintraege: [],
    };
    const LAEUFT: ImportLaufMitZeilen = { ...VORSCHAU, lauf: { ...VORSCHAU.lauf, uebernommenVon: 'inhaber' } };
    const ansage = () => element.querySelector('[data-test=ansage]')!.textContent!.trim();

    it('fragt den Status ab, solange die Vorschau aussteht, hört danach auf und sagt das Ergebnis an', async () => {
      vi.useFakeTimers();
      await oeffnen(AUSSTEHEND, VORSCHAU);
      expect(ansage()).toBe(TEXTE.importe.ausstehend);
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      await stabil();
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
      expect(element.querySelectorAll('[data-test=zeile]')).toHaveLength(3);
      // Review PR #72, S2: Das Ende steht in der Live-Region (WCAG 4.1.3)
      expect(ansage()).toBe(
        'Vorschau bereit: 4 Zeilen – 1 neu, 1 geändert, 0 unverändert, 2 mögliche Dubletten, 0 fehlerhaft, 0 übersprungen.',
      );
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS * 3);
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
    });

    it('sagt das Ende der Übernahme mit der Bilanz an', async () => {
      vi.useFakeTimers();
      await oeffnen(LAEUFT, {
        ...VORSCHAU,
        lauf: { ...VORSCHAU.lauf, status: 'UEBERNOMMEN', angelegt: 1, aktualisiert: 2, ausgelassen: 1, gekennzeichnet: 0 },
      });
      expect(ansage()).toBe(TEXTE.importe.uebernahmeLaeuft);
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      await stabil();
      expect(ansage()).toBe(
        'Übernommen: 1 angelegt, 2 aktualisiert, 1 ausgelassen, 0 als «nicht mehr im CRM-Export» gekennzeichnet.',
      );
    });

    it('sagt beim blossen Öffnen einer fertigen Vorschau nichts an', async () => {
      await oeffnen(VORSCHAU);
      expect(ansage()).toBe('');
    });

    it('beendet die Abfrage einer hängenden Übernahme mit Hinweis und erlaubt sie erneut (Review PR #72, S1)', async () => {
      vi.useFakeTimers();
      await oeffnen(LAEUFT);
      expect(element.querySelectorAll('[data-test=entscheid]')).toHaveLength(0);
      expect(knopf(TEXTE.importe.erneutUebernehmen)).toBeUndefined();
      vi.setSystemTime(Date.now() + WARTEN_MAX_MS);
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      await stabil();
      const aufrufe = service.apiV1ImporteIdGet.mock.calls.length;
      expect(element.querySelector('[data-test=zu-lange] [role=alert]')!.textContent).toContain(
        TEXTE.importe.zuLangeUebernahme,
      );
      expect(ansage()).toBe('');
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS * 5);
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(aufrufe);

      expect(element.querySelectorAll('[data-test=entscheid]')).toHaveLength(2);
      knopf(TEXTE.importe.erneutUebernehmen).click();
      await stabil();
      expect(element.querySelector('[data-test=bestaetigen]')!.textContent).toContain(
        '2 mögliche Dubletten ohne Entscheid werden ausgelassen',
      );
      service.apiV1ImporteIdGet.mockReturnValue(of(LAEUFT));
      knopf(TEXTE.importe.jetztUebernehmen).click();
      await stabil();
      expect(service.apiV1ImporteIdUebernehmenPost).toHaveBeenCalledWith('l1', { dublettenEntscheide: {} });
      expect(element.querySelector('[data-test=zu-lange]')).toBeNull();
      const nachher = service.apiV1ImporteIdGet.mock.calls.length;
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(nachher + 1);
    });

    it('beendet die Abfrage einer hängenden Vorschau und prüft auf Wunsch erneut', async () => {
      vi.useFakeTimers();
      await oeffnen(AUSSTEHEND);
      vi.setSystemTime(Date.now() + WARTEN_MAX_MS);
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      await stabil();
      expect(element.querySelector('[data-test=zu-lange]')!.textContent).toContain(TEXTE.importe.zuLangeVorschau);

      service.apiV1ImporteIdGet.mockReturnValue(of(VORSCHAU));
      const aufrufe = service.apiV1ImporteIdGet.mock.calls.length;
      knopf(TEXTE.importe.erneutPruefen).click();
      await stabil();
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(aufrufe + 1);
      expect(element.querySelector('[data-test=zu-lange]')).toBeNull();
      expect(element.querySelectorAll('[data-test=zeile]')).toHaveLength(3);
    });

    it('fragt nach einer vorübergehenden Störung mit längerem Abstand weiter ab (Review PR #72, K2)', async () => {
      vi.useFakeTimers();
      await oeffnen(AUSSTEHEND, new HttpErrorResponse({ status: 503 }), VORSCHAU);
      await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
      await stabil();
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
      expect(element.querySelector('[role=alert]')!.textContent).toContain(TEXTE.importe.abfrageGestoert);
      await vi.advanceTimersByTimeAsync(ABFRAGE_STOERUNG_MS - 1);
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(1);
      await stabil();
      expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(3);
      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(element.querySelectorAll('[data-test=zeile]')).toHaveLength(3);
    });
  });
});
