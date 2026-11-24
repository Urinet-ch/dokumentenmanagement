import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { App, SEITE_NEU_LADEN } from './app';
import { WarteschlangeService } from './erfassen/handy/warteschlange/warteschlange.service';
import { EingangskorbEntwurfSpeicher } from './eingangskorb/entwurf-speicher';
import { Entwurf } from './eingangskorb/entwurf';
import { BrandingService, STANDARD_BRANDING } from './gemeinsam/branding.service';
import {
  AnmeldungService,
  Benutzer,
  Sitzungszustand,
} from './gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from './gemeinsam/anmeldung/rechte';
import { EingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';
import { ONLINE } from './gemeinsam/online';
import { TEXTE } from './gemeinsam/texte.de-CH';
import { WissenEntwurfZaehler } from './wissen/wissen-entwurf-zaehler';

class AnmeldungAttrappe {
  readonly zustand = signal<Sitzungszustand>('abgemeldet');
  readonly angemeldet = computed(() => this.zustand() === 'angemeldet');
  readonly benutzer = signal<Benutzer | null>(null);
  readonly abmelden = vi.fn();
  readonly anmelden = vi.fn();
  darf(aktion: Aktion) {
    const benutzer = this.benutzer();
    return this.angemeldet() && !!benutzer?.mandant && darf(benutzer.rolle, aktion);
  }
  als(rolle: Rolle | null, mandant: string | null = 'urinet') {
    this.benutzer.set({ id: 'u', name: 'Test Benutzer', mandant, rolle });
    this.zustand.set('angemeldet');
  }
}

class ZaehlerAttrappe extends EingangskorbZaehler {
  readonly anzahl = signal<number | null>(null);
}

@Component({ template: '' })
class Leer {}

describe('App (Req 2.6, 8.6)', () => {
  const LEERER_ENTWURF: Entwurf = {
    art: '',
    titel: '',
    datum: '',
    partnerId: '',
    partnerName: '',
    projekt: '',
    belegnummer: '',
    richtung: '',
    betrag: '',
    waehrung: '',
    faelligkeit: '',
    qrReferenz: '',
    gueltigAb: '',
    gueltigBis: '',
    vertraulichkeit: '',
    tags: [],
  };
  let anmeldung: AnmeldungAttrappe;
  let zaehler: ZaehlerAttrappe;
  let warteschlangeGestartet: boolean;
  let online: ReturnType<typeof signal<boolean>>;
  let versionUpdates: Subject<{ type: string }>;
  let activateUpdate: ReturnType<typeof vi.fn<() => Promise<boolean>>>;
  let seiteNeuLaden: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(() => {
    anmeldung = new AnmeldungAttrappe();
    zaehler = new ZaehlerAttrappe();
    warteschlangeGestartet = false;
    online = signal(true);
    versionUpdates = new Subject<{ type: string }>();
    activateUpdate = vi.fn<() => Promise<boolean>>();
    seiteNeuLaden = vi.fn<() => void>();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: '', component: Leer },
          { path: 'erfassen', component: Leer },
          { path: 'suche', component: Leer },
          { path: 'chat', component: Leer },
          { path: 'wissen', component: Leer },
          { path: 'wissen/entwuerfe', component: Leer },
        ]),
        { provide: WarteschlangeService, useFactory: () => ((warteschlangeGestartet = true), {}) },
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: BrandingService, useValue: { branding: signal(STANDARD_BRANDING) } },
        { provide: EingangskorbZaehler, useValue: zaehler },
        { provide: WissenEntwurfZaehler, useValue: { anzahl: signal(3), fehler: signal(null), auffrischen: vi.fn() } },
        { provide: ONLINE, useValue: online },
        {
          provide: SwUpdate,
          useValue: { isEnabled: true, versionUpdates, activateUpdate },
        },
        { provide: SEITE_NEU_LADEN, useValue: seiteNeuLaden },
      ],
    });
  });

  async function zeigen(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  async function fixtureZeigen() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    await vi.waitFor(() => expect(warteschlangeGestartet).toBe(true));
    return fixture;
  }

  const menue = (element: HTMLElement) =>
    [...element.querySelectorAll('nav a')].map((a) =>
      a.querySelector('.punkt')?.childNodes[0]?.textContent?.trim(),
    );

  it('startet die Warteschlange der Handy-Erfassung mit der App (Req 4.5)', async () => {
    await zeigen();
    await vi.waitFor(() => expect(warteschlangeGestartet).toBe(true));
  });

  it('zeigt den Titel und ohne Anmeldung kein Menü', async () => {
    const element = await zeigen();
    expect(element.querySelector('.titel')?.textContent).toContain(STANDARD_BRANDING.name);
    expect(element.querySelector('nav')).toBeNull();
    expect(element.textContent).not.toContain(TEXTE.anmeldung.abmelden);
  });

  it('bietet «Hilfe» auch ohne Anmeldung an', async () => {
    const element = await zeigen();
    const knopf = element.querySelector<HTMLButtonElement>('[aria-controls="hilfe-leiste"]');
    expect(knopf?.getAttribute('aria-label')).toBe(TEXTE.hilfe.titel);
    expect(knopf?.getAttribute('aria-expanded')).toBe('false');
    expect(knopf?.querySelector('app-symbol')?.getAttribute('name')).toBe('hilfe');
  });

  it.each<[Rolle, string[]]>([
    [
      'inhaber',
      [
        TEXTE.menue.uebersicht,
        TEXTE.menue.eingangskorb,
        TEXTE.menue.erfassen,
        TEXTE.menue.suche,
        TEXTE.menue.chat,
        TEXTE.menue.kontakte,
        TEXTE.menue.wissen,
        TEXTE.wissen.entwuerfe.titel,
        TEXTE.menue.importe,
        TEXTE.menue.export,
        TEXTE.menue.einstellungen,
        TEXTE.menue.benutzer,
        TEXTE.menue.dokumentarten,
        TEXTE.menue.protokoll,
        TEXTE.chat.auswertung.titel,
        TEXTE.menue.apiTokens,
      ],
    ],
    [
      'mitarbeiter',
      [TEXTE.menue.uebersicht, TEXTE.menue.eingangskorb, TEXTE.menue.erfassen, TEXTE.menue.suche, TEXTE.menue.chat, TEXTE.menue.wissen],
    ],
    [
      'geschaeftsstelle',
      [
        TEXTE.menue.uebersicht,
        TEXTE.menue.eingangskorb,
        TEXTE.menue.erfassen,
        TEXTE.menue.suche,
        TEXTE.menue.chat,
        TEXTE.menue.kontakte,
        TEXTE.menue.wissen,
        TEXTE.wissen.entwuerfe.titel,
      ],
    ],
    ['treuhaender', [TEXTE.menue.uebersicht, TEXTE.menue.suche, TEXTE.menue.chat, TEXTE.menue.wissen, TEXTE.menue.export]],
  ])('zeigt der Rolle %s nur die erlaubten Menüpunkte', async (rolle, erwartet) => {
    anmeldung.als(rolle);
    const element = await zeigen();
    expect(menue(element)).toEqual(erwartet);
    expect(element.querySelector('[data-test=benutzer]')?.textContent).toContain('urinet');
  });

  it('zeigt ohne Mandant kein Menü', async () => {
    anmeldung.als('inhaber', null);
    expect((await zeigen()).querySelector('nav')).toBeNull();
  });

  it('zeigt die Anzahl im Eingangskorb neben dem Menüpunkt (Req 8.6)', async () => {
    anmeldung.als('inhaber');
    zaehler.anzahl.set(3);
    const element = await zeigen();
    expect(element.querySelector('[data-test=eingangskorb-zaehler]')?.textContent).toBe('3');
    expect(element.querySelector('nav .nur-screenreader')?.textContent).toContain(
      TEXTE.eingangskorbAnzahl(3),
    );
  });

  it('zeigt keinen Zähler und keinen Fehler, solange die Anzahl fehlt', async () => {
    anmeldung.als('inhaber');
    const element = await zeigen();
    expect(element.querySelector('[data-test=eingangskorb-zaehler]')).toBeNull();
    expect(element.querySelector('[role=alert]')).toBeNull();
  });

  it('zeigt den Offline-Hinweis global und entfernt ihn nach der Wiederverbindung', async () => {
    online.set(false);
    const fixture = await fixtureZeigen();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-test=offline-hinweis]')?.textContent).toContain(TEXTE.offlineHinweis);

    online.set(true);
    fixture.detectChanges();
    expect(element.querySelector('[data-test=offline-hinweis]')).toBeNull();
  });

  it('zeigt ein bereitstehendes Service-Worker-Update und lädt nach Aktivierung neu', async () => {
    activateUpdate.mockResolvedValue(true);
    const fixture = await fixtureZeigen();
    const element = fixture.nativeElement as HTMLElement;
    versionUpdates.next({ type: 'VERSION_READY' });
    fixture.detectChanges();
    expect(element.querySelector('[data-test=update-hinweis]')?.textContent).toContain(TEXTE.updateBereit);

    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.updateJetzt)!
      .click();
    await fixture.whenStable();
    expect(activateUpdate).toHaveBeenCalledOnce();
    expect(seiteNeuLaden).toHaveBeenCalledOnce();
  });

  it('zeigt einen Fehler, wenn das Service-Worker-Update nicht aktiviert werden kann', async () => {
    activateUpdate.mockRejectedValue(new Error('synthetischer Fehler'));
    const fixture = await fixtureZeigen();
    const element = fixture.nativeElement as HTMLElement;
    versionUpdates.next({ type: 'VERSION_READY' });
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.updateJetzt)!
      .click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('[data-test=update-fehler]')?.textContent).toContain(TEXTE.updateFehler);
  });

  it('meldet über Keycloak ab', async () => {
    anmeldung.als('mitarbeiter');
    const element = await zeigen();
    window.sessionStorage.clear();
    const entwurfSpeicher = TestBed.inject(EingangskorbEntwurfSpeicher);
    entwurfSpeicher.speichern('u', 'd1', LEERER_ENTWURF, {});
    expect(window.sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).not.toBeNull();
    const abmelden = [...element.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === TEXTE.anmeldung.abmelden,
    );
    abmelden!.click();
    expect(window.sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).toBeNull();
    expect(anmeldung.abmelden).toHaveBeenCalledOnce();
  });

  it('schreibt bei der Anmeldung ohne Entwurf nichts in den sessionStorage', async () => {
    window.sessionStorage.clear();
    anmeldung.als('inhaber');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    fixture.detectChanges();
    const eingangskorbEintraege = Object.keys(window.sessionStorage).filter((k) =>
      k.startsWith('archiv.eingangskorb.'),
    );
    expect(eingangskorbEintraege).toEqual([]);
  });

  it('löscht Eingangskorb-Entwürfe beim Wechsel des angemeldeten Benutzers', async () => {
    window.sessionStorage.clear();
    anmeldung.als('inhaber');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const entwurfSpeicher = TestBed.inject(EingangskorbEntwurfSpeicher);
    entwurfSpeicher.speichern('u', 'd1', LEERER_ENTWURF, {});
    expect(window.sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).not.toBeNull();

    anmeldung.benutzer.set({ id: 'u2', name: 'Anderer Benutzer', mandant: 'urinet', rolle: 'inhaber' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(window.sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).toBeNull();
  });

  it('bietet «Abmelden» auch nach Ablauf der Sitzung an (Keycloak-Sitzung beenden)', async () => {
    anmeldung.zustand.set('abgelaufen');
    const element = await zeigen();
    const abmelden = [...element.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === TEXTE.anmeldung.abmelden,
    );
    abmelden!.click();
    expect(anmeldung.abmelden).toHaveBeenCalledOnce();
  });

  it('führt bei abgelaufener Sitzung zur Startseite und merkt sich die Seite als Ziel', async () => {
    anmeldung.als('mitarbeiter');
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/suche');
    await fixture.whenStable();
    anmeldung.zustand.set('abgelaufen');
    await fixture.whenStable();
    expect(router.url).toBe('/?ziel=%2Fsuche');
  });

  it('bleibt bei abgelaufener Sitzung auf «Erfassen», damit aufgenommene Seiten nicht verloren gehen', async () => {
    anmeldung.als('mitarbeiter');
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/erfassen');
    await fixture.whenStable();
    anmeldung.zustand.set('abgelaufen');
    await fixture.whenStable();
    expect(router.url).toBe('/erfassen');
  });

  it('führt mit der Suche in der Kopfzeile zur Seite «Suche» mit dem Begriff', async () => {
    anmeldung.als('inhaber');
    const element = await zeigen();
    const feld = element.querySelector<HTMLInputElement>('.kopfsuche input')!;
    feld.value = ' Hostpoint ';
    feld.form!.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/suche?q=Hostpoint'));
  });

  it('bietet die Suche in der Kopfzeile nur mit Recht A2 und Menü an', async () => {
    expect((await zeigen()).querySelector('.kopfsuche')).toBeNull();
  });

  it('wechselt das Farbschema und merkt es sich', async () => {
    window.localStorage.removeItem('archiv.farbschema');
    const element = await zeigen();
    const knopf = element.querySelector<HTMLButtonElement>('[data-test=farbschema]')!;
    const vorher = document.documentElement.dataset['farbschema'];
    knopf.click();
    const nachher = document.documentElement.dataset['farbschema'];
    expect(nachher === 'hell' || nachher === 'dunkel').toBe(true);
    expect(nachher).not.toBe(vorher);
    expect(window.localStorage.getItem('archiv.farbschema')).toBe(nachher);
    window.localStorage.removeItem('archiv.farbschema');
    delete document.documentElement.dataset['farbschema'];
  });

  it('hebt nur den genauesten Menüpunkt hervor', async () => {
    anmeldung.als('inhaber');
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/wissen/entwuerfe');
    await fixture.whenStable();
    fixture.detectChanges();
    const aktiv = [...(fixture.nativeElement as HTMLElement).querySelectorAll('nav a[aria-current=page]')].map((a) =>
      a.getAttribute('href'),
    );
    expect(aktiv).toEqual(['/wissen/entwuerfe']);
  });

  it('zeigt unten Version und Build der App, auch ohne Anmeldung', async () => {
    const element = await zeigen();
    expect(element.querySelector('[data-test=stand-version]')?.textContent).toContain('Version Entwicklung');
    expect(element.querySelector('[data-test=stand-build]')?.textContent).toContain('Build lokal');
    expect(element.querySelector('footer.stand')?.getAttribute('aria-label')).toBe(TEXTE.stand.beschriftung);
  });
});
