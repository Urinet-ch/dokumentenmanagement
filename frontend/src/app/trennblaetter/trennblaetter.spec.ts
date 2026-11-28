import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Dokumentart, DokumentartenService, TrennblaetterService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Trennblaetter } from './trennblaetter';

const ART = (code: string, name: string, aktiv = true) => ({ code, name, aktiv }) as Dokumentart;

describe('Trennblätter: Seite (Spec 09, Aufgabe 27.5)', () => {
  let fixture: ComponentFixture<Trennblaetter>;
  let element: HTMLElement;
  const arten = { apiV1DokumentartenGet: vi.fn() };
  const api = { apiV1TrennblaetterPost: vi.fn() };
  const texte = TEXTE.trennblaetter;

  async function starten(liste: Dokumentart[] | 'fehler' = [ART('QUITTUNG', 'Quittung / Spesen'), ART('BANK', 'Bank'), ART('ALT', 'Alt', false)]) {
    arten.apiV1DokumentartenGet
      .mockReset()
      .mockReturnValue(liste === 'fehler' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(liste));
    api.apiV1TrennblaetterPost.mockReset();
    TestBed.configureTestingModule({
      imports: [Trennblaetter],
      providers: [
        { provide: DokumentartenService, useValue: arten },
        { provide: TrennblaetterService, useValue: api },
      ],
    });
    fixture = TestBed.createComponent(Trennblaetter);
    element = fixture.nativeElement;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function eingeben(feld: HTMLInputElement, wert: string) {
    feld.value = wert;
    feld.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function anzahlFeld(name: string): HTMLInputElement {
    return element.querySelector(`input[aria-label="${texte.anzahlFuer(name)}"]`) as HTMLInputElement;
  }

  const knopf = () => element.querySelector('[data-test="pdf-erstellen"]') as HTMLButtonElement;

  afterEach(() => vi.restoreAllMocks());

  it('zeigt «Ohne Art» und nur aktive Arten, nach Namen sortiert', async () => {
    await starten();
    const zeilen = [...element.querySelectorAll('[data-test="trenn-art"]')].map((z) => z.getAttribute('data-art'));
    expect(element.querySelector('[data-test="trenn-ohne-art"]')?.textContent).toContain(texte.ohneArt);
    expect(zeilen).toEqual(['BANK', 'QUITTUNG']);
    expect(element.querySelector('h1')?.textContent).toContain(texte.titel);
  });

  it('erstellt das PDF mit Trennblättern und Deckblatt und lädt es herunter', async () => {
    await starten();
    const erstellt = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:pdf');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const klick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    api.apiV1TrennblaetterPost.mockReturnValue(
      of(new HttpResponse({ body: new Blob(['%PDF-1.7'], { type: 'application/pdf' }) })),
    );
    expect(knopf().disabled).toBe(true);

    eingeben(anzahlFeld(texte.ohneArt), '2');
    eingeben(anzahlFeld('Quittung / Spesen'), '5');
    eingeben(element.querySelector('[data-test="deckblatt-titel"]') as HTMLInputElement, ' Versicherungen 2026 ');
    (element.querySelector('[data-test="deckblatt-hinzufuegen"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(element.querySelector('[data-test="summe"]')?.textContent?.trim()).toBe('8');

    knopf().click();
    fixture.detectChanges();

    expect(api.apiV1TrennblaetterPost).toHaveBeenCalledWith(
      {
        blaetter: [
          { typ: 'TRENN', anzahl: 2 },
          { typ: 'TRENN', art: 'QUITTUNG', anzahl: 5 },
          { typ: 'ORDNER', titel: 'Versicherungen 2026', anzahl: 1 },
        ],
      },
      'response',
      false,
      { httpHeaderAccept: 'application/pdf', transferCache: false },
    );
    expect(erstellt).toHaveBeenCalled();
    expect(klick).toHaveBeenCalled();
    expect(element.textContent).toContain(texte.fertig);
  });

  it('nimmt keinen ungültigen Titel an und meldet ihn', async () => {
    await starten();
    const titel = element.querySelector('[data-test="deckblatt-titel"]') as HTMLInputElement;
    const hinzufuegen = element.querySelector('[data-test="deckblatt-hinzufuegen"]') as HTMLButtonElement;

    eingeben(titel, 'Ä'.repeat(61));
    hinzufuegen.click();
    fixture.detectChanges();
    expect(element.textContent).toContain(texte.titelLang);
    expect(element.querySelectorAll('[data-test="deckblatt"]')).toHaveLength(0);

    eingeben(titel, 'Ordner\u202E');
    hinzufuegen.click();
    fixture.detectChanges();
    expect(element.textContent).toContain(texte.titelZeichen);
    expect(titel.getAttribute('aria-invalid')).toBe('true');
  });

  it('sperrt das Erstellen über 100 Blätter und bei ungültiger Anzahl', async () => {
    await starten();
    eingeben(anzahlFeld(texte.ohneArt), '60');
    eingeben(anzahlFeld('Bank'), '41');
    expect(element.textContent).toContain(texte.zuViele);
    expect(knopf().disabled).toBe(true);

    eingeben(anzahlFeld('Bank'), '40');
    expect(knopf().disabled).toBe(false);
    eingeben(anzahlFeld('Bank'), '-3');
    expect(element.textContent).toContain(texte.anzahlUngueltig);
    expect(knopf().disabled).toBe(true);
  });

  it('zeigt 400 TRENNBLATT_UNGUELTIG aus dem Blob als eigene Meldung', async () => {
    await starten();
    const problem = new Blob([JSON.stringify({ fehlercode: 'TRENNBLATT_UNGUELTIG', status: 400 })], {
      type: 'application/problem+json',
    });
    api.apiV1TrennblaetterPost.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: problem, headers: new HttpHeaders() })),
    );
    eingeben(anzahlFeld(texte.ohneArt), '1');
    knopf().click();
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();

    expect(element.querySelector('[role="alert"].hinweis')?.textContent).toContain(texte.titelUngueltig);
  });

  it('erlaubt ohne Dokumentarten weiterhin Trennblätter ohne Art', async () => {
    await starten('fehler');
    expect(element.textContent).toContain(texte.artenFehler);
    eingeben(anzahlFeld(texte.ohneArt), '1');
    expect(knopf().disabled).toBe(false);
  });
});
