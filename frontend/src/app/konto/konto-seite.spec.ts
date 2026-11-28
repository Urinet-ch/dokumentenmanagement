import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Eintrag } from '../erfassen/handy/warteschlange/eintrag';
import { WarteschlangeService } from '../erfassen/handy/warteschlange/warteschlange.service';
import { EingangskorbEntwurfSpeicher } from '../eingangskorb/entwurf-speicher';
import { Entwurf } from '../eingangskorb/entwurf';
import { AnmeldungService, Benutzer } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { UMGEBUNG } from '../../umgebung/umgebung';
import { VERMERK_SCHLUESSEL, vermerkAblegen, vermerkKennung, vermerkeLesen } from '../hilfe/einfuehrung-vermerk-speicher';
import { Abmeldung } from './abmeldung';
import { KontoSeite } from './konto-seite';

const SUB = '4f1c2a3b-0000-4000-8000-000000000001';
const T = TEXTE.konto;
const ENTWURF = { art: '', titel: 'x', tags: [] } as unknown as Entwurf;

class AnmeldungAttrappe {
  readonly zustand = signal<'angemeldet' | 'abgemeldet' | 'abgelaufen'>('angemeldet');
  readonly angemeldet = computed(() => this.zustand() === 'angemeldet');
  readonly benutzer = signal<Benutzer | null>(null);
  darf(aktion: Aktion) {
    const benutzer = this.benutzer();
    return this.angemeldet() && !!benutzer?.mandant && darf(benutzer.rolle, aktion);
  }
}

describe('Mein Konto (Spec 06, Req 11)', () => {
  let anmeldung: AnmeldungAttrappe;
  let online: ReturnType<typeof signal<boolean>>;
  let eintraege: ReturnType<typeof signal<Eintrag[]>>;
  let abmelden: ReturnType<typeof vi.fn>;

  function als(rolle: Rolle | null, mandant: string | null = 'urinet', mehr: Partial<Benutzer> = {}) {
    anmeldung.benutzer.set({ id: SUB, name: 'Muster Hans', mandant, rolle, ...mehr });
  }

  async function zeigen(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(KontoSeite);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (element: HTMLElement, test: string) =>
    element.querySelector(`[data-test=${test}]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    delete document.documentElement.dataset['farbschema'];
    anmeldung = new AnmeldungAttrappe();
    online = signal(true);
    eintraege = signal<Eintrag[]>([]);
    abmelden = vi.fn();
    TestBed.configureTestingModule({
      imports: [KontoSeite],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: ONLINE, useValue: online },
        // Nur die Anzeige; die Seite ruft an der Warteschlange nichts auf (nie löschen, Spec 01, Req 4.11)
        { provide: WarteschlangeService, useValue: { eintraege } },
        { provide: Abmeldung, useValue: { abmelden } },
      ],
    });
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    delete document.documentElement.dataset['farbschema'];
  });

  it('zeigt die Angaben aus dem Token nur lesend, ohne technische Kennung (Req 11.3)', async () => {
    als('mitarbeiter', 'urinet', { benutzername: 'hmuster', email: 'hans@example.invalid' });
    const element = await zeigen();
    const person = text(element, 'konto-person');
    for (const teil of ['Muster Hans', 'hmuster', 'hans@example.invalid', 'Mitarbeiter', 'urinet', T.person.hinweis]) {
      expect(person).toContain(teil);
    }
    expect(element.textContent).not.toContain(SUB);
    expect(element.querySelector('[data-test=konto-person] input')).toBeNull();
  });

  it('lässt fehlende Angaben weg (Req 11.3, 11.11)', async () => {
    als(null, null);
    const element = await zeigen();
    expect([...element.querySelectorAll('[data-test=konto-person] dt')].map((dt) => dt.textContent)).toEqual([
      T.person.name,
    ]);
    expect(element.querySelector('[data-test=konto-hilfe]')).toBeNull();
    for (const test of ['konto-sicherheit', 'konto-darstellung', 'konto-abmelden']) {
      expect(element.querySelector(`[data-test=${test}]`), test).not.toBeNull();
    }
  });

  it('öffnet die vier Einstiege der Kontoverwaltung in einem neuen Tab (Req 11.4)', async () => {
    als('inhaber');
    const element = await zeigen();
    const basis = `${UMGEBUNG.oidc.aussteller}/account/account-security`;
    const erwartet: [string, string, string][] = [
      ['konto-passkeys', T.sicherheit.passkeys, `${basis}/signing-in`],
      ['konto-passwort', T.sicherheit.passwort, `${basis}/signing-in`],
      ['konto-einmalcode', T.sicherheit.einmalcode, `${basis}/signing-in`],
      ['konto-geraete', T.sicherheit.geraete, `${basis}/device-activity`],
    ];
    for (const [test, name, url] of erwartet) {
      const link = element.querySelector<HTMLAnchorElement>(`a[data-test=${test}]`)!;
      expect(link.getAttribute('href')).toBe(url);
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.getAttribute('aria-label')).toBe(T.sicherheit.neuerTab(name));
    }
    expect(element.querySelector('[data-test=konto-sicherheit] input')).toBeNull();
  });

  it('deaktiviert die Einstiege offline und sagt warum (Req 11.4, 11.11)', async () => {
    als('inhaber');
    online.set(false);
    const element = await zeigen();
    expect(element.querySelector('[data-test=konto-sicherheit] a')).toBeNull();
    const knoepfe = [...element.querySelectorAll<HTMLButtonElement>('[data-test=konto-sicherheit] button')];
    expect(knoepfe).toHaveLength(4);
    expect(knoepfe.every((k) => k.disabled && k.getAttribute('aria-describedby') === 'konto-offline')).toBe(true);
    expect(text(element, 'konto-offline')).toContain(T.sicherheit.offline);
  });

  it('wendet die Wahl des Farbschemas sofort an und merkt sie nur im Browser (Req 11.6)', async () => {
    als('mitarbeiter');
    localStorage.setItem('archiv.farbschema', 'dunkel');
    const element = await zeigen();
    const knopf = (wert: string) => element.querySelector<HTMLButtonElement>(`[data-test=farbschema-${wert}]`)!;
    expect(knopf('dunkel').getAttribute('aria-pressed')).toBe('true');
    knopf('hell').click();
    TestBed.tick();
    expect(document.documentElement.dataset['farbschema']).toBe('hell');
    expect(localStorage.getItem('archiv.farbschema')).toBe('hell');
    expect(knopf('hell').getAttribute('aria-pressed')).toBe('true');
    knopf('system').click();
    expect(document.documentElement.dataset['farbschema']).toBeUndefined();
    expect(localStorage.getItem('archiv.farbschema')).toBeNull();
  });

  it('bietet mit Rolle «Einführung erneut zeigen» und die Anleitung (Req 11.7)', async () => {
    als('treuhaender');
    const element = await zeigen();
    const links = [...element.querySelectorAll<HTMLAnchorElement>('[data-test=konto-hilfe] a')];
    expect(links.map((a) => [a.textContent?.trim(), a.getAttribute('href')])).toEqual([
      [TEXTE.einfuehrung.erneut, '/?einfuehrung='],
      [T.hilfe.anleitung, '/hilfe'],
    ]);
  });

  it('zählt die Aufnahmen der Warteschlange dieses Benutzers und verlinkt die Liste (Req 11.8)', async () => {
    als('mitarbeiter');
    eintraege.set([{ id: '1' }, { id: '2' }] as Eintrag[]);
    const element = await zeigen();
    expect(text(element, 'konto-aufnahmen')).toBe(T.geraet.aufnahmen(2));
    expect(element.querySelector('[data-test=konto-geraet] a')?.getAttribute('href')).toBe('/erfassen');
  });

  it('zeigt ohne Recht A1 oder ohne Mandant weder Zähler noch Link (Req 11.8)', async () => {
    eintraege.set([{ id: '1' }] as Eintrag[]);
    for (const [rolle, mandant] of [['treuhaender', 'urinet'], ['mitarbeiter', null]] as [Rolle, string | null][]) {
      als(rolle, mandant);
      const element = await zeigen();
      expect(element.querySelector('[data-test=konto-aufnahmen]'), `${rolle}/${mandant}`).toBeNull();
      expect(element.querySelector('[data-test=konto-geraet] a')).toBeNull();
      expect(element.querySelector('[data-test=konto-entfernen]')).not.toBeNull();
    }
  });

  it('meldet einen Speicherfehler beim Entfernen statt Erfolg (Req 11.9)', async () => {
    als('mitarbeiter');
    localStorage.setItem('archiv.farbschema', 'dunkel');
    const element = await zeigen();
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('gesperrt');
    });
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen]')!.click();
    TestBed.tick();
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen-ja]')!.click();
    await vi.waitFor(() => expect(element.querySelector('[data-test=konto-entfernen-fehler]')).not.toBeNull());
    expect(text(element, 'konto-entfernen-fehler')).toBe(T.geraet.fehler);
    expect(element.querySelector('[data-test=konto-entfernt]')).toBeNull();
    vi.restoreAllMocks();
  });

  it('bleibt bei abgelaufener Sitzung ohne Angaben nutzbar: Darstellung, Dieses Gerät, Abmelden (Req 11.11)', async () => {
    als('mitarbeiter');
    eintraege.set([{ id: '1' }] as Eintrag[]);
    TestBed.inject(EingangskorbEntwurfSpeicher).speichern(SUB, 'd1', ENTWURF, {});
    const fixture = TestBed.createComponent(KontoSeite);
    await fixture.whenStable();
    anmeldung.zustand.set('abgelaufen');
    anmeldung.benutzer.set(null);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(text(element, 'konto-abgelaufen')).toBe(T.abgelaufen);
    for (const test of ['konto-person', 'konto-sicherheit', 'konto-hilfe']) {
      expect(element.querySelector(`[data-test=${test}]`), test).toBeNull();
    }
    for (const test of ['konto-darstellung', 'konto-geraet', 'konto-abmelden']) {
      expect(element.querySelector(`[data-test=${test}]`), test).not.toBeNull();
    }
    expect(text(element, 'konto-aufnahmen')).toBe(T.geraet.aufnahmen(1));
    expect(element.textContent).not.toContain('Muster Hans');
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen]')!.click();
    fixture.detectChanges();
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen-ja]')!.click();
    await vi.waitFor(() => expect(element.querySelector('[data-test=konto-entfernt]')).not.toBeNull());
    expect(sessionStorage.getItem(`archiv.eingangskorb.entwurf.v1:${SUB}:d1`)).toBeNull();
  });

  it('entfernt nach der Rückfrage genau die eigenen lokalen Einstellungen (Req 11.9)', async () => {
    als('mitarbeiter');
    const eigen = (await vermerkKennung('urinet', SUB, crypto.subtle))!;
    const fremd = 'f'.repeat(64);
    vermerkAblegen(localStorage, { k: fremd, version: 1, art: 'UEBERSPRUNGEN', am: '2026-11-01' });
    vermerkAblegen(localStorage, { k: eigen, version: 1, art: 'ABGESCHLOSSEN', am: '2026-11-02' });
    localStorage.setItem('archiv.farbschema', 'dunkel');
    localStorage.setItem(`archiv.branding.${location.hostname}`, '{"name":"Archiv"}');
    localStorage.setItem('anderes', 'bleibt');
    TestBed.inject(EingangskorbEntwurfSpeicher).speichern(SUB, 'd1', ENTWURF, {});
    sessionStorage.setItem('archiv.eingangskorb.entwurf.v1:anderer:d2', 'bleibt');
    const element = await zeigen();

    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen]')!.click();
    TestBed.tick();
    expect(text(element, 'konto-entfernen-frage')).toBe(T.geraet.frage);
    expect(localStorage.getItem('archiv.farbschema')).toBe('dunkel');
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen-ja]')!.click();
    await vi.waitFor(() => expect(element.querySelector('[data-test=konto-entfernt]')).not.toBeNull());

    expect(localStorage.getItem('archiv.farbschema')).toBeNull();
    expect(localStorage.getItem(`archiv.branding.${location.hostname}`)).toBeNull();
    expect(vermerkeLesen(localStorage).map((v) => v.k)).toEqual([fremd]);
    expect(sessionStorage.getItem(`archiv.eingangskorb.entwurf.v1:${SUB}:d1`)).toBeNull();
    expect(sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:anderer:d2')).toBe('bleibt');
    expect(localStorage.getItem('anderes')).toBe('bleibt');
    expect(localStorage.getItem(VERMERK_SCHLUESSEL)).not.toBeNull();
    expect(anmeldung.angemeldet()).toBe(true);
    expect(abmelden).not.toHaveBeenCalled();
  });

  it('«Abbrechen» lässt alles stehen (Req 11.9)', async () => {
    als('mitarbeiter');
    localStorage.setItem('archiv.farbschema', 'hell');
    const element = await zeigen();
    element.querySelector<HTMLButtonElement>('[data-test=konto-entfernen]')!.click();
    TestBed.tick();
    [...element.querySelectorAll<HTMLButtonElement>('[data-test=konto-geraet] button')]
      .find((b) => b.textContent?.trim() === T.geraet.abbrechen)!
      .click();
    TestBed.tick();
    expect(element.querySelector('[data-test=konto-entfernen-frage]')).toBeNull();
    expect(localStorage.getItem('archiv.farbschema')).toBe('hell');
  });

  it('meldet über denselben Weg ab wie bisher die Kopfzeile (Req 11.10)', async () => {
    als('mitarbeiter');
    const element = await zeigen();
    [...element.querySelectorAll<HTMLButtonElement>('[data-test=konto-abmelden] button')]
      .find((b) => b.textContent?.trim() === TEXTE.anmeldung.abmelden)!
      .click();
    expect(abmelden).toHaveBeenCalledOnce();
  });
});
