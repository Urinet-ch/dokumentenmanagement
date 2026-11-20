import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenEntwurfZaehler } from './wissen-entwurf-zaehler';
import { WissenEntwuerfe } from './wissen-entwuerfe';

const daten = {
  entwurf: { id: 'e1', status: 'OFFEN', anlass: 'INGEST', erstellt: '2026-01-01T10:00:00Z' },
  kernaussagen: [{ schluessel: 'k1', text: 'Nicht zugeordnete Probe', seiten: [1], belegt: true, zugeordnet: false }],
  aenderungen: [{
    nr: 2, pfad: 'konzepte/probe', status: 'OFFEN', etag: 7, vertraulichkeitKontext: 'INTERN',
    daten: { titel: 'Probe', beschreibung: 'Ein Satz.', tags: ['probe'], quellen: [] }, inhalt: 'Neue Fassung.',
    befunde: [], hinweise: [], widersprueche: [], entfernteAussagen: [],
    diff: { zeilen: [{ alt: ['Alt'], neu: ['Neu'] }] },
  }],
};

describe('Wissensentwürfe', () => {
  let http: HttpTestingController;
  const auffrischen = vi.fn();

  beforeEach(() => {
    auffrischen.mockClear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: WissenEntwurfZaehler, useValue: { auffrischen } }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function zeigen(antwort = daten) {
    const fixture = TestBed.createComponent(WissenEntwuerfe);
    fixture.componentRef.setInput('id', 'e1');
    fixture.detectChanges();
    http.expectOne('/api/v1/wissensentwuerfe/e1').flush(antwort);
    await fixture.whenStable();
    return fixture;
  }

  function klicken(element: HTMLElement, text: string) {
    const button = [...element.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);
    expect(button).toBeTruthy();
    button!.click();
  }

  it('zeigt Diffzeichen, Kontext und nicht zugeordnete Kernaussagen', async () => {
    const fixture = await zeigen();
    expect(fixture.nativeElement.textContent).toContain('− Alt');
    expect(fixture.nativeElement.textContent).toContain('+ Neu');
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.entwuerfe.nichtZugeordnet);
    expect(fixture.nativeElement.textContent).toContain('INTERN');
  });

  it('sendet genau eine Annahme erst nach Bestätigung mit dem angezeigten ETag', async () => {
    const fixture = await zeigen();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.entscheiden);
    fixture.detectChanges();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.annehmen);
    await fixture.whenStable();
    http.expectNone((r) => r.method === 'POST');
    const dialog = document.querySelector('.cdk-overlay-container') as HTMLElement;
    klicken(dialog, TEXTE.wissen.entwuerfe.annehmen);
    await vi.waitFor(() => expect(document.querySelector('mat-dialog-container')).toBeNull());
    await fixture.whenStable();
    const request = http.expectOne('/api/v1/wissensentwuerfe/e1/aenderungen/2/annehmen');
    expect(request.request.headers.get('If-Match')).toBe('"7"');
    expect(request.request.body.vertraulichkeit).toBeNull();
    request.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/wissensentwuerfe/e1').flush(daten);
    expect(auffrischen).toHaveBeenCalledOnce();
    await fixture.whenStable();
  });

  it('zeigt erkannte manuelle Widersprüche aus dem Diff auch ohne KI-Blockliste', async () => {
    const antwort = { ...daten, aenderungen: [{ ...daten.aenderungen[0],
      diff: { ...daten.aenderungen[0].diff, widersprueche: {
        alt: [{ text: 'Alte strittige Aussage', fussnoten: ['q1', 'q2'] }],
        neu: [{ text: 'Neue strittige Aussage', fussnoten: ['q1', 'q3'] }],
      } } }] };
    const fixture = await zeigen(antwort);
    expect(fixture.nativeElement.textContent).toContain('− Alte strittige Aussage · q1, q2');
    expect(fixture.nativeElement.textContent).toContain('+ Neue strittige Aussage · q1, q3');
  });

  it('hebt die Auflösung aus dem Backend-Diffvertrag ohne KI-Blockliste hervor', async () => {
    const block = { text: 'Widerspruch: Alte Frist. Neue Frist.', fussnoten: ['alt', 'neu'] };
    const antwort = { ...daten, aenderungen: [{ ...daten.aenderungen[0],
      diff: { ...daten.aenderungen[0].diff, widersprueche: { alt: [block], neu: [] },
        entfernteWidersprueche: [block] } }] };
    const fixture = await zeigen(antwort);
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.entwuerfe.aufloesung);
    expect(fixture.nativeElement.textContent).toContain('− Widerspruch: Alte Frist. Neue Frist. · alt, neu');
  });

  it('verwirft nicht ohne Grund und sendet bei abgebrochener Bestätigung nichts', async () => {
    const fixture = await zeigen();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.entscheiden);
    fixture.detectChanges();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.verwerfen);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.entwuerfe.grundFehlt);
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.annehmen);
    await fixture.whenStable();
    klicken(document.querySelector('.cdk-overlay-container') as HTMLElement, TEXTE.wissen.entwuerfe.abbrechen);
    await fixture.whenStable();
    http.expectNone((r) => r.method === 'POST');
  });

  it('prüft bearbeitete JSON-Listen und zeigt Versionskonflikte ohne automatischen zweiten POST', async () => {
    const fixture = await zeigen();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.entscheiden);
    fixture.detectChanges();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.bearbeiten);
    fixture.detectChanges();
    const quellen = [...fixture.nativeElement.querySelectorAll('label')]
      .find((e: HTMLLabelElement) => e.textContent?.includes(TEXTE.wissen.entwuerfe.quellenJson))!
      .querySelector('textarea') as HTMLTextAreaElement;
    quellen.value = '{';
    quellen.dispatchEvent(new Event('input'));
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.geaendertAnnehmen);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.entwuerfe.jsonUngueltig);
    quellen.value = '[]';
    quellen.dispatchEvent(new Event('input'));
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.geaendertAnnehmen);
    await fixture.whenStable();
    klicken(document.querySelector('.cdk-overlay-container') as HTMLElement, TEXTE.wissen.entwuerfe.annehmen);
    await vi.waitFor(() => expect(document.querySelector('mat-dialog-container')).toBeNull());
    await fixture.whenStable();
    const request = http.expectOne('/api/v1/wissensentwuerfe/e1/aenderungen/2/annehmen');
    expect(request.request.body.fassung.tags).toEqual(['probe']);
    request.flush({ fehlercode: 'VERSION_KONFLIKT' }, { status: 412, statusText: 'Precondition Failed' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    expect(quellen.value).toBe('[]');
    http.expectNone((r) => r.method === 'POST');
  });

  it('bietet für einen veralteten Import weder Annahme noch KI-Neuerzeugung an', async () => {
    const fixture = await zeigen({ ...daten, entwurf: { ...daten.entwurf, anlass: 'IMPORT' },
      aenderungen: [{ ...daten.aenderungen[0], status: 'VERALTET' }] });
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.entscheiden);
    await fixture.whenStable();
    const buttons = [...fixture.nativeElement.querySelectorAll('button')].map((b: HTMLButtonElement) => b.textContent?.trim());
    expect(buttons).not.toContain(TEXTE.wissen.entwuerfe.annehmen);
    expect(buttons).not.toContain(TEXTE.wissen.entwuerfe.neuErzeugen);
    expect(buttons).toContain(TEXTE.wissen.entwuerfe.verwerfen);
  });

  it('zeigt bei einer abgewiesenen Annahme jeden neuen fachlichen Befund', async () => {
    const fixture = await zeigen();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.entscheiden);
    fixture.detectChanges();
    klicken(fixture.nativeElement, TEXTE.wissen.entwuerfe.annehmen);
    await fixture.whenStable();
    klicken(document.querySelector('.cdk-overlay-container') as HTMLElement, TEXTE.wissen.entwuerfe.annehmen);
    await vi.waitFor(() => expect(document.querySelector('mat-dialog-container')).toBeNull());
    http.expectOne('/api/v1/wissensentwuerfe/e1/aenderungen/2/annehmen')
      .flush({ befunde: ['AUSSAGE_ENTFERNT', 'VERTRAULICHKEIT_ZU_TIEF'] },
        { status: 422, statusText: 'Unprocessable Content' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('AUSSAGE_ENTFERNT');
    expect(fixture.nativeElement.textContent).toContain('VERTRAULICHKEIT_ZU_TIEF');
    expect(auffrischen).not.toHaveBeenCalled();
    http.expectNone((r) => r.method === 'POST');
  });
});
