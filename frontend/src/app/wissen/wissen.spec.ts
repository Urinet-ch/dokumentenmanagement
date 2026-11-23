import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideApi } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenIndex } from './wissen-index';
import { WissenEinstellungenSeite } from './wissen-einstellungen';
import { WissenLog } from './wissen-log';
import { WissenSeite } from './wissen-seite';
import { WissenVerwendung } from './wissen-verwendung';
import { WissenQuellen } from './wissen-quellen';
import { wissenText } from './wissen-text';

describe('Wissensoberfläche', () => {
  let http: HttpTestingController;
  const rolle = { wert: 'inhaber' as Rolle };

  beforeEach(() => {
    rolle.wert = 'inhaber';
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideApi(''),
        {
          provide: AnmeldungService,
          useValue: { darf: (aktion: Aktion) => darf(rolle.wert, aktion) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('speichert Einstellungen und setzt leere Felder auf den Startwert zurück', async () => {
    const fixture = TestBed.createComponent(WissenEinstellungenSeite);
    http.expectOne('/api/v1/wissensbasis/einstellungen').flush({ budgetTokens: 123 });
    await fixture.whenStable();
    const eingabe = fixture.nativeElement.querySelector('#wissen-budget') as HTMLInputElement;
    eingabe.value = '456';
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    const anfrage = http.expectOne('/api/v1/wissensbasis/einstellungen');
    expect(anfrage.request.method).toBe('PUT');
    expect(anfrage.request.body).toEqual({
      budgetTokens: 456, lintBudgetTokens: null, lintZeitplan: null, monatsbudgetTokens: null,
    });
    anfrage.flush(anfrage.request.body);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissenEinstellungen.gespeichert);
  });

  it('fragt den Exportstatus ab und zeigt nach Abschluss Download und Prüfsumme', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const fixture = TestBed.createComponent(WissenEinstellungenSeite);
      http.expectOne('/api/v1/wissensbasis/einstellungen').flush({});
      fixture.detectChanges();
      const element = fixture.nativeElement as HTMLElement;
      const start = [...element.querySelectorAll('button')]
        .find((button) => button.textContent?.includes(TEXTE.wissenEinstellungen.exportStart))!;
      start.click();
      http.expectOne('/api/v1/wissensbasis/exporte').flush({ id: 'e1', status: 'OFFEN' });
      fixture.detectChanges();
      expect(start.disabled).toBe(true);
      vi.advanceTimersByTime(5000);
      http.expectOne('/api/v1/wissensbasis/exporte/e1').flush({
        id: 'e1', status: 'FERTIG', url: 'https://example.invalid/export', sha256: 'a'.repeat(64),
        gueltigBis: '2026-11-20T12:00:00Z',
      });
      fixture.detectChanges();
      expect(element.querySelector('a[href="https://example.invalid/export"]')).toBeTruthy();
      expect(element.textContent).toContain('a'.repeat(64));
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('zeigt einen unvollständigen fertigen Export nicht als Download an', async () => {
    const fixture = TestBed.createComponent(WissenEinstellungenSeite);
    http.expectOne('/api/v1/wissensbasis/einstellungen').flush({});
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.includes(TEXTE.wissenEinstellungen.exportStart))!.click();
    http.expectOne('/api/v1/wissensbasis/exporte').flush({ id: 'e1', status: 'FERTIG' });
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.wissenEinstellungen.exportUngueltig);
    expect(element.textContent).not.toContain(TEXTE.wissenEinstellungen.herunterladen);
  });

  it('filtert den Index über die API und zeigt die sichtbaren Ergebnisse', async () => {
    const fixture = TestBed.createComponent(WissenIndex);
    fixture.detectChanges();
    http.expectOne('/api/v1/wissen/quellen').flush([]);
    http.expectOne((anfrage) => anfrage.url === '/api/v1/wissensseiten')
      .flush({ gesamt: 1, gruppen: [{ typ: 'Konzept', seiten: [
        { id: '1', pfad: 'konzepte/probe', titel: 'Probe', status: 'VERALTET', quellePruefen: true },
      ] }] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Probe');
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.veraltet);
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.quellePruefen);

    const eingabe = fixture.nativeElement.querySelector('#wissen-suche') as HTMLInputElement;
    eingabe.value = 'Begriff';
    eingabe.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const anfrage = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(anfrage.request.params.get('q')).toBe('Begriff');
    anfrage.flush({ gesamt: 0, gruppen: [] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.keineSeiten);
  });

  it('zeigt Wissensquellen mit Art, Markierungsdatum und Ingest-Status und entfernt eine Markierung', async () => {
    const fixture = TestBed.createComponent(WissenQuellen);
    fixture.detectChanges();
    http.expectOne('/api/v1/wissen/quellen').flush([{
      dokumentId: 'd1',
      titel: 'Synthetische Quelle',
      art: 'QUITTUNG',
      markiertAm: '2026-11-23T09:00:00Z',
      ingestStatus: 'IN_ARBEIT',
    }]);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Synthetische Quelle');
    expect(element.textContent).toContain('QUITTUNG');
    expect(element.textContent).toContain('23.11.2026 10:00');
    expect(element.textContent).toContain(TEXTE.wissen.quellenListe.ingestStatus['IN_ARBEIT']);

    element.querySelector<HTMLButtonElement>(
      '[aria-label="Wissensquelle entfernen: «Synthetische Quelle»"]',
    )!.click();
    const entfernung = http.expectOne('/api/v1/dokumente/d1/wissensquelle');
    expect(entfernung.request.method).toBe('DELETE');
    entfernung.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.wissen.quellenListe.entfernt);
    expect(element.textContent).toContain(TEXTE.wissen.quellenListe.keine);
  });

  it('zeigt die Liste ohne A16 weiter, aber ohne Entfernen-Aktion', async () => {
    rolle.wert = 'mitarbeiter';
    const fixture = TestBed.createComponent(WissenQuellen);
    fixture.detectChanges();
    http.expectOne('/api/v1/wissen/quellen').flush([{
      dokumentId: 'd1',
      titel: 'Synthetische Quelle',
      art: 'QUITTUNG',
      markiertAm: '2026-11-23T09:00:00Z',
      ingestStatus: 'OFFEN',
    }]);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Synthetische Quelle');
    expect(element.querySelector('button')).toBeNull();
  });

  it('zeigt Fehler beim Abruf des Logs und lädt bei Wiederholung neu', async () => {
    const fixture = TestBed.createComponent(WissenLog);
    http.expectOne((r) => r.url === '/api/v1/wissensbasis/log')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.httpFehler.server);
    fixture.nativeElement.querySelector('button').click();
    http.expectOne((r) => r.url === '/api/v1/wissensbasis/log')
      .flush({ eintraege: [{ id: 1, text: 'Synthetischer Eintrag', seiten: [] }] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Synthetischer Eintrag');
  });

  it('übernimmt nur interne Wissenslinks und stellt fremde Inhalte als Text dar', () => {
    const teile = wissenText('<p>Text <a href="/wissen/konzepte/probe">Probe</a> '
      + '<a href="javascript:alert(1)">Fremd</a><script>unsichtbar</script></p>');
    expect(teile).toEqual([{ art: 'p', kinder: [
      { art: 'text', text: 'Text ' }, { art: 'link', text: 'Probe', pfad: '/wissen/konzepte/probe' },
      { art: 'text', text: ' ' }, { art: 'text', text: 'Fremd' },
    ] }]);
  });

  it('zeigt Quellendokumente mit Seitennummer und filtert die Darstellung auf Text und interne Links', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/probe');
    fixture.detectChanges();
    const seite = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(seite.request.params.get('pfad')).toBe('konzepte/probe');
    seite.flush({
      id: 's1', titel: 'Probe', inhaltHtml: '<h2>Abschnitt</h2><ul><li>Eintrag</li></ul>'
        + '<p>Belegt <a href="/wissen/ueberblick">Überblick</a>'
        + '<sup class="footnote-ref"><a href="#fn-q1" data-footnote-ref>1</a></sup></p>'
        + '<section data-footnotes><ol><li><p>q1 <a href="#fnref-q1">↩</a></p></li></ol></section>',
      quellen: [{ schluessel: 'q1', dokumentId: 'd1', seiteVon: 3, dokumentTitel: 'Quelle', dokumentDatum: '2000-01-01' }],
      quellePruefen: true, widerspruchOffen: true, freigegebenAm: '2026-01-02T10:00:00Z',
    });
    http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
    http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [] });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain(TEXTE.wissen.quellePruefen);
    expect(element.textContent).toContain(TEXTE.wissen.widerspruch);
    expect(element.textContent).toContain(`${TEXTE.wissen.quellendatum}: 01.01.2000`);
    expect(element.textContent).toContain('02.01.2026');
    expect(element.querySelector('article')?.textContent).toContain('Belegt');
    expect(element.querySelector('article h2')?.textContent).toBe('Abschnitt');
    expect(element.querySelector('article ul > li')?.textContent).toBe('Eintrag');
    expect(element.querySelector('article a')?.getAttribute('href')).toBe('/wissen/ueberblick');
    expect(element.querySelector('article a[href*="/dokument/d1"]')?.getAttribute('href'))
      .toBe('/dokument/d1?seite=3');
    expect(element.querySelector('article a[href*="/dokument/d1"]')?.getAttribute('aria-label'))
      .toBe('[1] Quelle · Seite 3');
    expect(element.querySelector('article')?.textContent).not.toContain('↩');
    expect(element.querySelector('a[href*="/dokument/d1"]')?.getAttribute('href'))
      .toBe('/dokument/d1?seite=3');
  });

  it('lädt Quellentitel nicht nach und zeigt Hinweise für abgelaufene Seiten und Seitenquellen', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/probe');
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/wissensseiten').flush({
      id: 's1', titel: 'Probe', status: 'AKTIV', veraltetAb: '2000-01-01',
      quellen: [
        { schluessel: 'q1', dokumentId: 'd1', dokumentDateiname: 'scan.pdf' },
        { schluessel: 'q2', quellSeiteId: 's2', quellSeitePfad: 'quellen/zwei', quellSeiteTitel: 'Quelle Zwei' },
        { schluessel: 'q3', dokumentId: 'd3' },
      ],
    });
    http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
    http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [] });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain(TEXTE.wissen.abgelaufen);
    expect(element.querySelector('a[href="/wissen/quellen/zwei"]')?.textContent).toContain('Quelle Zwei');
    expect(element.querySelector('a[href*="/dokument/d1"]')?.textContent).toContain('scan.pdf');
    expect(element.querySelector('a[href*="/dokument/d3"]')?.textContent).toContain('q3');
  });

  it('hebt Widerspruchsblöcke als Warn-Hinweis hervor, gewöhnliche Zitate nicht', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/probe');
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/wissensseiten').flush({
      id: 's1', titel: 'Probe', quellen: [],
      inhaltHtml: '<blockquote><p>⚠️ Widerspruch: A nennt 1, B nennt 2.</p></blockquote><blockquote><p>Zitat</p></blockquote>',
    });
    http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
    http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [] });
    await fixture.whenStable();
    const bloecke = (fixture.nativeElement as HTMLElement).querySelectorAll('article blockquote');
    expect(bloecke[0].classList).toContain('widerspruch');
    expect(bloecke[1].classList).not.toContain('widerspruch');
  });

  it('zeigt keinen Abgelaufen-Hinweis bei künftigem Datum oder Status VERALTET', async () => {
    for (const seite of [{ status: 'AKTIV', veraltetAb: '2999-01-01' }, { status: 'VERALTET', veraltetAb: '2000-01-01' }]) {
      const fixture = TestBed.createComponent(WissenSeite);
      fixture.componentRef.setInput('pfad', 'konzepte/probe');
      fixture.detectChanges();
      http.expectOne((r) => r.url === '/api/v1/wissensseiten').flush({ id: 's1', titel: 'Probe', quellen: [], ...seite });
      http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
      http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [] });
      await fixture.whenStable();
      expect(fixture.nativeElement.textContent).not.toContain(TEXTE.wissen.abgelaufen);
    }
  });

  it('zeigt bei fehlender oder unsichtbarer Wissensseite denselben 404-Hinweis', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/fehlt');
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/wissensseiten')
      .flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.httpFehler.nichtGefunden);
  });

  it('zeigt je Fehlerstatus der Seite die passende Meldung ohne Rechte-Hinweis bei 404', async () => {
    for (const status of [401, 403, 404, 409, 429, 500, 0]) {
      const fixture = TestBed.createComponent(WissenSeite);
      fixture.componentRef.setInput('pfad', 'konzepte/probe');
      fixture.detectChanges();
      http.expectOne((r) => r.url === '/api/v1/wissensseiten')
        .flush({}, { status, statusText: 'Fehler' });
      await fixture.whenStable();
      const alarm = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
      expect(alarm?.textContent?.trim()).toBeTruthy();
      if (status === 404) expect(alarm?.textContent).toContain(TEXTE.httpFehler.nichtGefunden);
    }
  });

  it('fragt sichtbare Versionen beim Öffnen erneut ab und bietet unsichtbare nicht an', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/probe');
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/wissensseiten')
      .flush({ id: 's1', titel: 'Probe', quellen: [] });
    http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
    http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [
      { nummer: 1 }, { nummer: 2, zeit: '2026-01-01T10:00:00Z' },
    ] });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(element.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Version 1'))).toBe(false);
    buttons.find((b) => b.textContent?.includes('Version 2'))!.click();
    http.expectOne('/api/v1/wissensseiten/s1/versionen/2').flush({
      nummer: 2, inhaltHtml: '<p>Frühere Fassung</p>',
    });
    await fixture.whenStable();
    expect(element.textContent).toContain('Frühere Fassung');
    buttons.find((b) => b.textContent?.includes('Version 2'))!.click();
    http.expectOne('/api/v1/wissensseiten/s1/versionen/2')
      .flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(element.textContent).toContain(TEXTE.httpFehler.nichtGefunden);
    expect(element.textContent).not.toContain('Frühere Fassung');
  });

  it('zeigt nur die vom Dokumentfilter gelieferten Seiten und setzt Paging beim Dokumentwechsel zurück', async () => {
    const fixture = TestBed.createComponent(WissenVerwendung);
    fixture.componentRef.setInput('dokumentId', 'd1');
    fixture.detectChanges();
    const erste = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(erste.request.params.get('dokumentId')).toBe('d1');
    expect(erste.request.params.get('offset')).toBe('0');
    erste.flush({ gesamt: 51, gruppen: [{ typ: 'Konzept', seiten: [
      { id: 's1', pfad: 'konzepte/probe', titel: 'Sichtbare Seite' },
    ] }] });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/wissen/konzepte/probe');
    element.querySelector('button')!.click();
    const zweite = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(zweite.request.params.get('offset')).toBe('50');
    zweite.flush({ gesamt: 51, gruppen: [] });
    fixture.componentRef.setInput('dokumentId', 'd2');
    fixture.detectChanges();
    const wechsel = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(wechsel.request.params.get('dokumentId')).toBe('d2');
    expect(wechsel.request.params.get('offset')).toBe('0');
    wechsel.flush({ gesamt: 0, gruppen: [] });
    await fixture.whenStable();
    expect(element.textContent).toContain(TEXTE.wissen.keineSeiten);
    expect(element.textContent).not.toContain('Sichtbare Seite');
  });
});
