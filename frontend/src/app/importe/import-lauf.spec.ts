import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ImportLaufMitZeilen, ImporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ABFRAGE_MS, ImportLaufSeite } from './import-lauf';

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

  async function oeffnen(...antworten: ImportLaufMitZeilen[]) {
    service.apiV1ImporteIdGet.mockReset();
    for (const antwort of antworten) {
      service.apiV1ImporteIdGet.mockReturnValueOnce(of(antwort));
    }
    service.apiV1ImporteIdGet.mockReturnValue(of(antworten.at(-1)));
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
      ]);
      chips[4].click();
      await fixture.whenStable();
      expect(service.apiV1ImporteIdGet).toHaveBeenLastCalledWith('l1', 'DUBLETTE', 50, 0);
      expect(chips[4].getAttribute('aria-pressed')).toBe('true');
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

  it('fragt den Status ab, solange die Vorschau aussteht, und hört danach auf', async () => {
    vi.useFakeTimers();
    await oeffnen({ lauf: { id: 'l1', art: 'kontakt', status: 'VORSCHAU_AUSSTEHEND' }, eintraege: [] }, VORSCHAU);
    expect(element.textContent).toContain(TEXTE.importe.ausstehend);
    expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(ABFRAGE_MS);
    await stabil();
    expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
    expect(element.querySelectorAll('[data-test=zeile]')).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(ABFRAGE_MS * 3);
    expect(service.apiV1ImporteIdGet).toHaveBeenCalledTimes(2);
  });
});
