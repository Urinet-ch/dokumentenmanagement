import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AnmeldungService, Benutzer, Sitzungszustand } from './gemeinsam/anmeldung/anmeldung.service';
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

describe('App (Req 2.6, 8.6)', () => {
  let anmeldung: AnmeldungAttrappe;
  let zaehler: ZaehlerAttrappe;

  beforeEach(() => {
    anmeldung = new AnmeldungAttrappe();
    zaehler = new ZaehlerAttrappe();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
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
    [...element.querySelectorAll('nav a')].map((a) => a.querySelector('.punkt')?.childNodes[0]?.textContent?.trim());

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
        TEXTE.menue.importe,
        TEXTE.menue.dokumentarten,
        TEXTE.menue.protokoll,
      ],
    ],
    ['mitarbeiter', [TEXTE.menue.erfassen, TEXTE.menue.eingangskorb, TEXTE.menue.suche]],
    ['geschaeftsstelle', [TEXTE.menue.erfassen, TEXTE.menue.eingangskorb, TEXTE.menue.suche]],
    ['treuhaender', [TEXTE.menue.suche]],
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
    expect(element.querySelector('nav .nur-screenreader')?.textContent).toContain(TEXTE.eingangskorbAnzahl(3));
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
});
