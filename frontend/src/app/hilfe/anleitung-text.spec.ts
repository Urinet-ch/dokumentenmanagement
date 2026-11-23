import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ANLEITUNG_MAX_BYTES } from './anleitung-markdown';
import { AnleitungText } from './anleitung-text';
import { HILFE_KATALOG, HilfeEintrag } from './hilfe-katalog';

const [ERSTE, PROBLEME] = HILFE_KATALOG;
const MD = { headers: { 'Content-Type': 'text/markdown' } };

@Component({ template: '' })
class Leer {}

@Component({
  imports: [AnleitungText],
  template: `@for (eintrag of eintraege; track eintrag.anker) {
    <section [attr.data-anker]="eintrag.anker">
      <app-anleitung-text [eintrag]="eintrag" [versatz]="1" [leiste]="leiste()" [ids]="ids()" (abschnitt)="gewaehlt.set($event)" (navigiert)="navigiert.set(true)" />
    </section>
  }`,
})
class Gastgeber {
  eintraege: readonly HilfeEintrag[] = [ERSTE, PROBLEME];
  readonly leiste = signal(false);
  readonly ids = signal(true);
  readonly gewaehlt = signal<string | null>(null);
  readonly navigiert = signal(false);
}

describe('AnleitungText (Spec 07, Req 7.3, 7.6)', () => {
  let http: HttpTestingController;

  async function zeigen(leiste = false) {
    TestBed.configureTestingModule({
      imports: [Gastgeber],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: Leer },
          { path: 'erfassen', component: Leer },
          { path: '**', redirectTo: '' },
        ]),
        { provide: BrandingService, useValue: { branding: signal({ name: 'Muster Archiv' }) } },
        { provide: AnmeldungService, useValue: { benutzer: signal({ rolle: 'mitarbeiter' }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Gastgeber);
    fixture.componentInstance.leiste.set(leiste);
    fixture.detectChanges();
    return fixture;
  }

  const abschnitt = (element: HTMLElement, anker: string) => element.querySelector(`[data-anker="${anker}"]`) as HTMLElement;

  afterEach(() => http.verify());

  it('stellt den Baum nur über Interpolation dar; HTML erscheint als Text', async () => {
    const fixture = await zeigen();
    http.expectOne(ERSTE.datei).flush(
      '## Im {name}\nAls {rolle} **fett** und [Erfassen](/erfassen), siehe [Probleme](#probleme).\n\n' +
        '> **Hinweis:** <img src=x onerror=alert(1)>\n\n- eins\n1. zwei',
      MD,
    );
    http.expectOne(PROBLEME.datei).flush('Text', MD);
    await fixture.whenStable();
    const teil = abschnitt(fixture.nativeElement, ERSTE.anker);
    expect(teil.querySelector('h3')?.textContent?.trim()).toBe('Im Muster Archiv');
    expect(teil.querySelector('h3')?.id).toBe('erste-schritte-im-name');
    expect(teil.querySelector('p')?.textContent).toContain('Als Mitarbeiter fett und Erfassen');
    expect(teil.querySelector('strong')?.textContent).toBe('fett');
    expect(teil.querySelector('a[href="/erfassen"]')?.textContent).toBe('Erfassen');
    expect(teil.querySelector('a[href="/hilfe#probleme"]')?.textContent).toBe('Probleme');
    const hinweis = teil.querySelector('.hinweis.info');
    expect(hinweis?.querySelector('app-symbol')).not.toBeNull();
    expect(hinweis?.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(teil.querySelector('img')).toBeNull();
    expect(teil.querySelectorAll('ul > li, ol > li')).toHaveLength(2);
  });

  it('in der Leiste wechselt ein Link auf einen Abschnitt nur den Inhalt, ein Link auf die App meldet «navigiert»', async () => {
    const fixture = await zeigen(true);
    http.expectOne(ERSTE.datei).flush('[Probleme](#probleme) und [Start](/)', MD);
    http.expectOne(PROBLEME.datei).flush('Text', MD);
    await fixture.whenStable();
    const teil = abschnitt(fixture.nativeElement, ERSTE.anker);
    const ereignis = new MouseEvent('click', { cancelable: true, bubbles: true });
    teil.querySelector<HTMLAnchorElement>('a[href="/hilfe#probleme"]')!.dispatchEvent(ereignis);
    expect(ereignis.defaultPrevented).toBe(true);
    expect(fixture.componentInstance.gewaehlt()).toBe('probleme');
    teil.querySelector<HTMLAnchorElement>('a[href="/"]')!.click();
    expect(fixture.componentInstance.navigiert()).toBe(true);
  });

  it('gibt in der Leiste ohne IDs keine ID-Attribute an Überschriften aus', async () => {
    const fixture = await zeigen(true);
    fixture.componentInstance.ids.set(false);
    http.expectOne(ERSTE.datei).flush('## Eins\n\n### Zwei\n\n#### Drei', MD);
    http.expectOne(PROBLEME.datei).flush('Text', MD);
    await fixture.whenStable();
    const ueberschriften = fixture.nativeElement.querySelectorAll('h3, h4, h5');
    expect(ueberschriften).toHaveLength(3);
    for (const ueberschrift of ueberschriften) expect(ueberschrift.hasAttribute('id')).toBe(false);
  });

  it('zeigt bei einem nicht ladbaren Abschnitt den Hinweis mit «Erneut versuchen», die übrigen bleiben (Req 7.6)', async () => {
    const fixture = await zeigen();
    http.expectOne(ERSTE.datei).error(new ProgressEvent('offline'));
    http.expectOne(PROBLEME.datei).flush('Wenn etwas nicht klappt.', MD);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const fehler = abschnitt(element, ERSTE.anker).querySelector('[role="alert"]');
    expect(fehler?.textContent?.trim()).toBe(TEXTE.hilfe.nichtVerfuegbar);
    expect(abschnitt(element, PROBLEME.anker).textContent).toContain('Wenn etwas nicht klappt.');

    const knopf = abschnitt(element, ERSTE.anker).querySelector('button');
    expect(knopf?.textContent?.trim()).toBe(TEXTE.hilfe.erneut);
    knopf!.click();
    http.expectOne(ERSTE.datei).flush('Wieder da.', MD);
    await fixture.whenStable();
    expect(abschnitt(element, ERSTE.anker).querySelector('[role="alert"]')).toBeNull();
    expect(abschnitt(element, ERSTE.anker).textContent).toContain('Wieder da.');
  });

  for (const [fall, antwort] of [
    ['HTTP 404', { status: 404, statusText: 'Not Found' }],
    ['Rückfallseite der SPA (HTML)', { headers: { 'Content-Type': 'text/html; charset=utf-8' } }],
  ] as const) {
    it(`behandelt «${fall}» als nicht verfügbar`, async () => {
      const fixture = await zeigen();
      http.expectOne(ERSTE.datei).flush('<!doctype html><app-root></app-root>', antwort);
      http.expectOne(PROBLEME.datei).flush('Text', MD);
      await fixture.whenStable();
      expect(abschnitt(fixture.nativeElement, ERSTE.anker).querySelector('[data-test="anleitung-fehler"]')).not.toBeNull();
    });
  }

  it('behandelt eine Datei über 32 KiB als nicht verfügbar', async () => {
    const fixture = await zeigen();
    http.expectOne(ERSTE.datei).flush('a'.repeat(ANLEITUNG_MAX_BYTES + 1), MD);
    http.expectOne(PROBLEME.datei).flush('Text', MD);
    await fixture.whenStable();
    expect(abschnitt(fixture.nativeElement, ERSTE.anker).querySelector('[data-test="anleitung-fehler"]')).not.toBeNull();
  });
});
