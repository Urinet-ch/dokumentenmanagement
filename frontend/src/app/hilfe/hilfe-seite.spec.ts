import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { AnmeldungService, Benutzer } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Anleitungsteil, anleitungMarkdown } from './anleitung-markdown';
import { istAppPfad } from './app-pfade';
import { HILFE_KATALOG, HilfeEintrag } from './hilfe-katalog';
import { HilfeSeite } from './hilfe-seite';
import { HILFE_EINTRAEGE } from './hilfe-sichtbar';

const eintrag = (anker: string, mehr: Partial<HilfeEintrag> = {}): HilfeEintrag => ({
  anker,
  titel: `Titel ${anker}`,
  datei: `/anleitung/${anker}.md`,
  ...mehr,
});

// Synthetischer Katalog: allgemein, nur mit Recht, Unterabschnitt, nur abgemeldet sichtbarer Unterabschnitt
const KATALOG: readonly HilfeEintrag[] = [
  eintrag('erste-schritte', { abgemeldet: true }),
  eintrag('erfassen', { bedingung: { eine: ['A1'] } }),
  eintrag('erfassen-offline', { eltern: 'erfassen', bedingung: { eine: ['A1'] }, abgemeldet: true }),
  eintrag('suche', { bedingung: { eine: ['A2'] } }),
  eintrag('importe', { bedingung: { eine: ['A12'] } }),
  eintrag('probleme', { abgemeldet: true }),
];
const MD = { headers: { 'Content-Type': 'text/markdown' } };

@Component({ template: '' })
class Leer {}

describe('Seite /hilfe (Spec 07, Req 5.1 bis 5.4, 6.2, 6.3, 6.5, 7.6)', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let scrollIntoView: ReturnType<typeof vi.fn>;
  const angemeldet = signal(false);
  const benutzer = signal<Benutzer | null>(null);

  beforeEach(async () => {
    scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView as unknown as Element['scrollIntoView'];
    angemeldet.set(false);
    benutzer.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: Leer },
          { path: 'hilfe', component: HilfeSeite },
        ]),
        { provide: HILFE_EINTRAEGE, useValue: KATALOG },
        { provide: BrandingService, useValue: { branding: signal({ name: 'Muster Archiv' }) } },
        { provide: AnmeldungService, useValue: { angemeldet, benutzer } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  function anmelden(rolle: Benutzer['rolle'], mandant: string | null = 'muster') {
    angemeldet.set(true);
    benutzer.set({ id: 'u1', name: 'Erika Muster', mandant, rolle });
  }

  /** Öffnet die Adresse und beantwortet alle Dateien; `fehler` erhalten HTTP 404. */
  async function oeffnen(url: string, fehler: readonly string[] = []): Promise<HTMLElement> {
    await harness.navigateByUrl(url);
    for (const anfrage of http.match(() => true)) {
      const anker = anfrage.request.url.replace('/anleitung/', '').replace('.md', '');
      if (fehler.includes(anker)) anfrage.flush('', { status: 404, statusText: 'Not Found' });
      else anfrage.flush(`Text zu ${anker}.`, MD);
    }
    await harness.fixture.whenStable();
    harness.detectChanges();
    return harness.routeNativeElement as HTMLElement;
  }

  const inhalt = (seite: HTMLElement) =>
    [...seite.querySelectorAll<HTMLAnchorElement>('nav a')].map((a) => a.getAttribute('href'));
  const abschnitte = (seite: HTMLElement) =>
    [...seite.querySelectorAll<HTMLElement>('.abschnitte [data-anker]')].map((a) => `/hilfe#${a.dataset['anker']}`);

  it('zeigt Seitenkopf mit Name aus dem Branding, Inhaltsverzeichnis und Abschnitte in derselben Reihenfolge', async () => {
    anmelden('inhaber');
    const seite = await oeffnen('/hilfe');
    expect(seite.querySelector('.seitenkopf .vorzeile')?.textContent?.trim()).toBe('Muster Archiv');
    expect(seite.querySelector('.seitenkopf h1')?.textContent?.trim()).toBe('Hilfe');
    expect(seite.querySelector<HTMLAnchorElement>('.seitenkopf a')?.getAttribute('href')).toBe('/?einfuehrung=');
    const nav = seite.querySelector('nav')!;
    expect(seite.querySelector(`#${nav.getAttribute('aria-labelledby')}`)?.textContent?.trim()).toBe('Inhalt der Anleitung');
    expect(inhalt(seite)).toEqual([
      '/hilfe#erste-schritte',
      '/hilfe#erfassen',
      '/hilfe#erfassen-offline',
      '/hilfe#suche',
      '/hilfe#importe',
      '/hilfe#probleme',
    ]);
    expect(abschnitte(seite)).toEqual(inhalt(seite));
    // Unterabschnitt als h3 in der Karte seines Abschnitts, im Inhaltsverzeichnis eingerückt
    expect(seite.querySelector('[data-anker="erfassen"] h3#erfassen-offline')).not.toBeNull();
    expect(seite.querySelector('nav li li a')?.getAttribute('href')).toBe('/hilfe#erfassen-offline');
    expect(seite.querySelector('#importe')?.textContent?.trim()).toBe('Titel importe');
  });

  it('nennt die eigene Rolle mit der Bezeichnung aus der Kopfzeile (Req 6.5)', async () => {
    anmelden('mitarbeiter');
    const seite = await oeffnen('/hilfe');
    expect(seite.querySelector('[data-test="hilfe-rolle"]')?.textContent?.trim()).toBe(
      'Sie sind als ‹Mitarbeiter› angemeldet. Diese Anleitung zeigt die Bereiche, die Ihre Rolle nutzen darf.',
    );
    expect(seite.querySelector('[data-test="hilfe-abgemeldet"]')).toBeNull();
  });

  it.each<NonNullable<Benutzer['rolle']>>(['inhaber', 'mitarbeiter', 'treuhaender', 'geschaeftsstelle'])(
    'zeigt für %s bei gleicher Rolle und gleichem Branding denselben Hilfetext in beiden Mandanten (Req 2.4)',
    async (rolle) => {
      anmelden(rolle, 'urinet');
      const seite = await oeffnen('/hilfe');
      const text = seite.textContent;
      const anker = inhalt(seite);

      anmelden(rolle, 'vhf-gsk');
      harness.detectChanges();
      await harness.fixture.whenStable();
      harness.detectChanges();

      expect(seite.textContent).toBe(text);
      expect(inhalt(seite)).toEqual(anker);
    },
  );

  it('zeigt Abschnitte ohne Recht weder im Inhaltsverzeichnis noch im Text (Req 6.2)', async () => {
    anmelden('treuhaender');
    const seite = await oeffnen('/hilfe');
    expect(inhalt(seite)).toEqual(['/hilfe#erste-schritte', '/hilfe#suche', '/hilfe#probleme']);
    expect(seite.textContent).not.toContain('Titel erfassen');
    expect(seite.textContent).not.toContain('Titel importe');
    http.expectNone('/anleitung/importe.md');
  });

  for (const [fall, vorbereiten] of [
    ['abgemeldet', () => undefined],
    ['ohne Mandant', () => anmelden('inhaber', null)],
    ['ohne Rolle', () => anmelden(null)],
  ] as const) {
    it(`zeigt ${fall} nur die allgemeinen Abschnitte, «erfassen-offline» und den Hinweis (Req 6.3)`, async () => {
      vorbereiten();
      const seite = await oeffnen('/hilfe');
      expect(inhalt(seite)).toEqual(['/hilfe#erste-schritte', '/hilfe#erfassen-offline', '/hilfe#probleme']);
      expect(abschnitte(seite)).toEqual(inhalt(seite));
      expect(seite.querySelector('h2#erfassen-offline')).not.toBeNull();
      expect(seite.querySelector('[data-test="hilfe-abgemeldet"]')?.textContent?.trim()).toBe(TEXTE.hilfe.abgemeldet);
      expect(seite.querySelector('[data-test="hilfe-rolle"]')).toBeNull();
      expect(seite.querySelector('.seitenkopf a')).toBeNull();
    });
  }

  it('springt zur Sprungmarke und setzt den Fokus auf die Überschrift, erst wenn alles geladen ist (Req 5.2)', async () => {
    anmelden('inhaber');
    await harness.navigateByUrl('/hilfe#suche');
    harness.detectChanges();
    expect(scrollIntoView).not.toHaveBeenCalled();
    for (const anfrage of http.match(() => true)) anfrage.flush('Text.', MD);
    await harness.fixture.whenStable();
    const ziel = harness.routeNativeElement!.querySelector('#suche');
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(ziel);
    expect(document.activeElement).toBe(ziel);
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('nav a[aria-current="location"]')?.getAttribute('href')).toBe(
      '/hilfe#suche',
    );
  });

  it('springt auch zu einem Unterabschnitt', async () => {
    anmelden('mitarbeiter');
    const seite = await oeffnen('/hilfe#erfassen-offline');
    expect(document.activeElement).toBe(seite.querySelector('h3#erfassen-offline'));
  });

  for (const [fall, url] of [
    ['unbekanntem Anker', '/hilfe#gibt-es-nicht'],
    ['verborgenem Anker', '/hilfe#importe'],
  ] as const) {
    it(`zeigt bei ${fall} die Seite von oben, ohne Meldung (Req 5.2)`, async () => {
      anmelden('mitarbeiter');
      const seite = await oeffnen(url);
      expect(scrollIntoView).not.toHaveBeenCalled();
      expect(seite.querySelector('[role="alert"]')).toBeNull();
      expect(seite.querySelector('nav a[aria-current]')).toBeNull();
    });
  }

  it('setzt beim Wählen im Inhaltsverzeichnis die Adresse und springt, auch erneut zum selben Anker (Req 5.3)', async () => {
    anmelden('inhaber');
    const seite = await oeffnen('/hilfe');
    seite.querySelector<HTMLAnchorElement>('nav a[href="/hilfe#probleme"]')!.click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Location).path(true)).toBe('/hilfe#probleme');
    expect(document.activeElement).toBe(seite.querySelector('#probleme'));
    (document.activeElement as HTMLElement).blur();
    seite.querySelector<HTMLAnchorElement>('nav a[href="/hilfe#probleme"]')!.click();
    await harness.fixture.whenStable();
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(document.activeElement).toBe(seite.querySelector('#probleme'));
  });

  it('zeigt bei einer nicht ladbaren Datei den Hinweis beim Abschnitt, die übrigen bleiben (Req 7.6)', async () => {
    anmelden('inhaber');
    const seite = await oeffnen('/hilfe#probleme', ['suche']);
    const fehler = seite.querySelectorAll('[data-test="anleitung-fehler"]');
    expect(fehler.length).toBe(1);
    expect(seite.querySelector('[data-anker="suche"] [data-test="anleitung-fehler"]')?.textContent?.trim()).toBe(
      TEXTE.hilfe.nichtVerfuegbar,
    );
    expect(seite.querySelector('[data-anker="probleme"]')?.textContent).toContain('Text zu probleme.');
    // Der Sprung wartet nicht auf die fehlende Datei
    expect(document.activeElement).toBe(seite.querySelector('#probleme'));
  });
});

// Ids der Überschriften in den Dateien («<anker>-<titel>») dürfen keinen Anker des Katalogs verdecken
describe('Überschriften der Anleitung neben den Sprungmarken (Spec 07, Req 5.2)', () => {
  interface Dateisystem {
    readFileSync(pfad: string, kodierung: 'utf8'): string;
  }
  const fs = (globalThis as unknown as { process: { getBuiltinModule(name: string): Dateisystem } }).process.getBuiltinModule(
    'node:fs',
  );
  const ids = (teile: readonly Anleitungsteil[]): string[] =>
    teile.flatMap((teil) => [...(teil.art.startsWith('h') && teil.anker ? [teil.anker] : []), ...ids(teil.kinder ?? [])]);

  it('keine Überschrift einer Datei hat die id eines Abschnitts', () => {
    const anker = new Set(HILFE_KATALOG.map((e) => e.anker));
    for (const e of HILFE_KATALOG) {
      const { teile } = anleitungMarkdown(fs.readFileSync(`public${e.datei}`, 'utf8'), {
        istAppPfad: (pfad) => istAppPfad(pfad, routes),
        praefix: e.anker,
      });
      for (const id of ids(teile)) expect(anker.has(id), `${e.anker}: ${id}`).toBe(false);
    }
  });
});
