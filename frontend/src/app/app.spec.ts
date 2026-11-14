import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from './app';
import { WarteschlangeService } from './erfassen/handy/warteschlange/warteschlange.service';
import {
  AnmeldungService,
  Benutzer,
  Sitzungszustand,
} from './gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from './gemeinsam/anmeldung/rechte';
import { EingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';
import { TEXTE } from './gemeinsam/texte.de-CH';

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
  let anmeldung: AnmeldungAttrappe;
  let zaehler: ZaehlerAttrappe;
  let warteschlangeGestartet: boolean;

  beforeEach(() => {
    anmeldung = new AnmeldungAttrappe();
    zaehler = new ZaehlerAttrappe();
    warteschlangeGestartet = false;
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: '', component: Leer },
          { path: 'erfassen', component: Leer },
          { path: 'suche', component: Leer },
        ]),
        { provide: WarteschlangeService, useFactory: () => ((warteschlangeGestartet = true), {}) },
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: EingangskorbZaehler, useValue: zaehler },
      ],
    });
  });

  async function zeigen(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
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
    expect(element.querySelector('.titel')?.textContent).toContain(TEXTE.appTitel);
    expect(element.querySelector('nav')).toBeNull();
    expect(element.textContent).not.toContain(TEXTE.anmeldung.abmelden);
  });

  it.each<[Rolle, string[]]>([
    [
      'inhaber',
      [
        TEXTE.menue.erfassen,
        TEXTE.menue.eingangskorb,
        TEXTE.menue.suche,
        TEXTE.menue.kontakte,
        TEXTE.menue.importe,
        TEXTE.menue.dokumentarten,
        TEXTE.menue.protokoll,
        TEXTE.menue.apiTokens,
        TEXTE.menue.export,
      ],
    ],
    ['mitarbeiter', [TEXTE.menue.erfassen, TEXTE.menue.eingangskorb, TEXTE.menue.suche]],
    ['geschaeftsstelle', [TEXTE.menue.erfassen, TEXTE.menue.eingangskorb, TEXTE.menue.suche, TEXTE.menue.kontakte]],
    ['treuhaender', [TEXTE.menue.suche, TEXTE.menue.export]],
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

  it('meldet über Keycloak ab', async () => {
    anmeldung.als('mitarbeiter');
    const element = await zeigen();
    const abmelden = [...element.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === TEXTE.anmeldung.abmelden,
    );
    abmelden!.click();
    expect(anmeldung.abmelden).toHaveBeenCalledOnce();
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
});
