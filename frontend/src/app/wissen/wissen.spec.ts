import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenIndex } from './wissen-index';
import { WissenLog } from './wissen-log';
import { WissenSeite } from './wissen-seite';
import { wissenText } from './wissen-text';

describe('Wissensoberfläche', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('filtert den Index über die API und zeigt die sichtbaren Ergebnisse', async () => {
    const fixture = TestBed.createComponent(WissenIndex);
    http.expectOne((anfrage) => anfrage.url === '/api/v1/wissensseiten')
      .flush({ gesamt: 1, gruppen: [{ typ: 'Konzept', seiten: [
        { id: '1', pfad: 'konzepte/probe', titel: 'Probe', status: 'VERALTET' },
      ] }] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Probe');
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.veraltet);

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
    expect(teile).toEqual([[
      { text: 'Text ' }, { text: 'Probe', pfad: '/wissen/konzepte/probe' },
      { text: ' ' }, { text: 'Fremd' },
    ]]);
  });

  it('zeigt Quellendokumente mit Seitennummer und filtert die Darstellung auf Text und interne Links', async () => {
    const fixture = TestBed.createComponent(WissenSeite);
    fixture.componentRef.setInput('pfad', 'konzepte/probe');
    fixture.detectChanges();
    const seite = http.expectOne((r) => r.url === '/api/v1/wissensseiten');
    expect(seite.request.params.get('pfad')).toBe('konzepte/probe');
    seite.flush({
      id: 's1', titel: 'Probe', inhaltHtml: '<p>Belegt <a href="/wissen/ueberblick">Überblick</a></p>',
      quellen: [{ schluessel: 'q1', dokumentId: 'd1', seiteVon: 3 }], quellePruefen: true,
    });
    http.expectOne('/api/v1/dokumente/d1').flush({ titel: 'Quelle' });
    http.expectOne('/api/v1/wissensseiten/s1/eingehend').flush([]);
    http.expectOne('/api/v1/wissensseiten/s1/versionen').flush({ versionen: [] });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain(TEXTE.wissen.quellePruefen);
    expect(element.querySelector('article')?.textContent).toContain('Belegt');
    expect(element.querySelector('article a')?.getAttribute('href')).toBe('/wissen/ueberblick');
    expect(element.querySelector('a[href*="/dokument/d1"]')?.getAttribute('href'))
      .toBe('/dokument/d1?seite=3');
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
});
