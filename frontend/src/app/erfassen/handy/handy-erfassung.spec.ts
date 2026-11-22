import { Component, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER } from 'rxjs';
import { AnmeldungService, Benutzer, Sitzungszustand } from '../../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../../gemeinsam/anmeldung/rechte';
import { ONLINE } from '../../gemeinsam/online';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { HilfeLeiste } from '../../hilfe/hilfe-leiste.service';
import { UploadPort } from '../upload-port';
import { MAX_AUFNAHME_BYTES, MAX_AUFNAHME_SEITEN } from './aufnahme-grenzen';
import { HandyErfassung } from './handy-erfassung';
import { Eintrag } from './warteschlange/eintrag';
import { Ecken } from './scanner/ecken';
import { Aufbereitet, ScannerPort } from './scanner/scanner.service';
import { SpeicherImArbeitsspeicher, WarteschlangeSpeicher } from './warteschlange/speicher';
import { WarteschlangeUpload } from './warteschlange/warteschlange-upload';

class AnmeldungAttrappe {
  readonly zustand = signal<Sitzungszustand>('abgemeldet');
  readonly angemeldet = computed(() => this.zustand() === 'angemeldet');
  readonly benutzer = signal<Benutzer | null>(null);
  readonly anmelden = vi.fn(async () => undefined);
  readonly sitzungAbgelaufen = vi.fn();
  darf(aktion: Aktion) {
    const b = this.benutzer();
    return this.angemeldet() && !!b?.mandant && darf(b.rolle, aktion);
  }
  als(rolle: Rolle, id = 'u1') {
    this.benutzer.set({ id, name: 'Test', mandant: 'urinet', rolle });
    this.zustand.set('angemeldet');
  }
}

const ECKEN: Ecken = {
  obenLinks: { x: 10, y: 10 },
  obenRechts: { x: 90, y: 12 },
  untenRechts: { x: 88, y: 140 },
  untenLinks: { x: 12, y: 138 },
};

class ScannerAttrappe extends ScannerPort {
  readonly auftraege: (Ecken | null | undefined)[] = [];
  kanten = true;
  fehler = false;
  vorbereitet = false;
  vorbereiten(): void {
    this.vorbereitet = true;
  }
  async aufbereiten(_bild: Blob, ecken?: Ecken | null): Promise<Aufbereitet> {
    this.auftraege.push(ecken);
    if (this.fehler) {
      throw new Error('kaputt');
    }
    const verwendet = ecken === undefined ? (this.kanten ? ECKEN : null) : ecken;
    return { ecken: verwendet, breite: 100, hoehe: 150, arbeitsfassung: new Blob(['zuschnitt'], { type: 'image/jpeg' }) };
  }
}

@Component({ template: '' })
class Leer {}

describe('HandyErfassung (Req 4.2–4.4, 4.7)', () => {
  let fixture: ComponentFixture<HandyErfassung>;
  let element: HTMLElement;
  let anmeldung: AnmeldungAttrappe;
  let scanner: ScannerAttrappe;
  let speicher: SpeicherImArbeitsspeicher;

  beforeEach(() => {
    anmeldung = new AnmeldungAttrappe();
    scanner = new ScannerAttrappe();
    speicher = new SpeicherImArbeitsspeicher();
    URL.createObjectURL ??= () => 'blob:test';
    URL.revokeObjectURL ??= () => undefined;
    TestBed.configureTestingModule({
      imports: [HandyErfassung],
      providers: [
        provideRouter([{ path: '**', component: Leer }]),
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: ScannerPort, useValue: scanner },
        { provide: WarteschlangeSpeicher, useValue: speicher },
        { provide: WarteschlangeUpload, useValue: { senden: vi.fn() } },
        { provide: ONLINE, useValue: signal(false) },
        { provide: UploadPort, useValue: { hochladen: () => NEVER } },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  async function zeigen(): Promise<void> {
    fixture = TestBed.createComponent(HandyErfassung);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  const knopf = (text: string) =>
    [...element.querySelectorAll('button')].find((b) => b.textContent?.trim() === text || b.getAttribute('aria-label') === text);

  async function fotoWaehlen(anzahl = 1): Promise<void> {
    knopf(TEXTE.handy.kameraOeffnen)?.click();
    await fixture.whenStable();
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file][capture=environment]')!;
    const dateien = Array.from({ length: anzahl }, (_, i) => new File([`roh${i}`], `foto${i}.jpg`, { type: 'image/jpeg' }));
    Object.defineProperty(eingabe, 'files', { value: dateien, configurable: true });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  const hinweise = () => [...element.querySelectorAll('[data-test=seite]')].map((s) => s.getAttribute('data-hinweis'));

  it('lädt den Scanner schon beim Öffnen, damit er ohne Verbindung bereitsteht (Req 4.4)', async () => {
    await zeigen();
    expect(scanner.vorbereitet).toBe(true);
    expect(scanner.auftraege).toEqual([]);
  });

  it('bietet ohne Kamera die Dateiwahl mit Rückkamera an und schneidet jede Seite zu', async () => {
    await zeigen();
    await fotoWaehlen(2);
    expect(element.textContent).toContain(TEXTE.handy.keineKamera);
    expect(hinweise()).toEqual(['kantenErkannt', 'kantenErkannt']);
    expect(scanner.auftraege).toEqual([undefined, undefined]);
  });

  it('öffnet die Warteschlangenhilfe ohne die begonnene Aufnahme zu verlieren', async () => {
    await zeigen();
    await fotoWaehlen();
    const link = element.querySelector<HTMLAnchorElement>('.schlange > a[href="/hilfe#erfassen-offline"]')!;
    link.click();
    expect(TestBed.inject(HilfeLeiste).anker()).toBe('erfassen-offline');
    expect(TestBed.inject(HilfeLeiste).offen()).toBe(true);
    expect(hinweise()).toEqual(['kantenErkannt']);
  });

  it('weist eine zusätzliche Seite ab, bevor sie in den Scanner gelangt', async () => {
    await zeigen();
    await fotoWaehlen(MAX_AUFNAHME_SEITEN + 1);
    expect(hinweise()).toHaveLength(MAX_AUFNAHME_SEITEN);
    expect(scanner.auftraege).toHaveLength(MAX_AUFNAHME_SEITEN);
    expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toBe(
      TEXTE.handy.aufnahmeZuVieleSeiten,
    );
  });

  it('weist eine zu grosse Datei ab, bevor sie decodiert wird', async () => {
    await zeigen();
    knopf(TEXTE.handy.kameraOeffnen)?.click();
    await fixture.whenStable();
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file][capture=environment]')!;
    Object.defineProperty(eingabe, 'files', {
      value: [new File([new Uint8Array(MAX_AUFNAHME_BYTES + 1)], 'gross.jpg', { type: 'image/jpeg' })],
    });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(hinweise()).toEqual([]);
    expect(scanner.auftraege).toEqual([]);
    expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toBe(TEXTE.handy.aufnahmeZuGross);
  });

  it('behält eine Grenzmeldung, wenn weitere Bilder derselben Auswahl angenommen werden', async () => {
    await zeigen();
    knopf(TEXTE.handy.kameraOeffnen)?.click();
    await fixture.whenStable();
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file][capture=environment]')!;
    Object.defineProperty(eingabe, 'files', {
      value: [
        new File([new Uint8Array(MAX_AUFNAHME_BYTES + 1)], 'gross.jpg', { type: 'image/jpeg' }),
        new File(['klein'], 'klein.jpg', { type: 'image/jpeg' }),
      ],
    });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(hinweise()).toHaveLength(1);
    expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toBe(TEXTE.handy.aufnahmeZuGross);
  });

  it('verwendet ohne erkannte Kanten das ganze Bild', async () => {
    scanner.kanten = false;
    await zeigen();
    await fotoWaehlen();
    expect(hinweise()).toEqual(['keineKanten']);
    expect(element.textContent).toContain(TEXTE.handy.keineKanten);
  });

  it('lässt den Zuschnitt anpassen und eine Seite verwerfen', async () => {
    await zeigen();
    await fotoWaehlen(2);
    knopf(TEXTE.handy.anpassen(1))!.click();
    await fixture.whenStable();
    expect(element.querySelectorAll('app-zuschnitt circle')).toHaveLength(4);
    const ecke = element.querySelector<SVGCircleElement>('circle[data-ecke=obenLinks]')!;
    ecke.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    await fixture.whenStable();
    knopf(TEXTE.handy.uebernehmen)!.click();
    await fixture.whenStable();
    expect(scanner.auftraege[2]).toEqual({ ...ECKEN, obenLinks: { x: 11.5, y: 10 } });
    expect(hinweise()).toEqual(['angepasst', 'kantenErkannt']);

    knopf(TEXTE.handy.anpassen(2))!.click();
    await fixture.whenStable();
    knopf(TEXTE.handy.ganzesBildVerwenden)!.click();
    await fixture.whenStable();
    expect(scanner.auftraege[3]).toBeNull();
    expect(hinweise()).toEqual(['angepasst', 'ganzesBild']);

    knopf(TEXTE.handy.verwerfen(1))!.click();
    await fixture.whenStable();
    expect(hinweise()).toEqual(['ganzesBild']);
  });

  it('legt die Seiten als eine Erfassung mit Original und Arbeitsfassung in die Warteschlange', async () => {
    await zeigen();
    await fotoWaehlen(2);
    knopf(TEXTE.handy.abschliessen)!.click();
    await fixture.whenStable();
    const [eintrag] = [...speicher.inhalt.values()];
    expect(eintrag.seiten).toHaveLength(2);
    expect(await eintrag.seiten[0].original.text()).toBe('roh0');
    expect(await eintrag.seiten[1].arbeitsfassung.text()).toBe('zuschnitt');
    expect(element.querySelector('[data-test=wartet]')!.textContent).toContain(TEXTE.handy.wartet(1));
    expect(element.textContent).toContain(TEXTE.handy.gespeichert);
    expect(hinweise()).toEqual([]);
  });

  it('meldet bei einer Seite mit Fehler beim Abschliessen und beim Anmelden, was zu tun ist', async () => {
    await zeigen();
    await fotoWaehlen();
    scanner.fehler = true;
    await fotoWaehlen();
    expect(hinweise()).toEqual(['kantenErkannt', 'fehler']);

    knopf(TEXTE.handy.abschliessen)!.click();
    await fixture.whenStable();
    expect(element.querySelector('[data-test=erfassung-meldung]')!.textContent).toBe(TEXTE.handy.seitenMitFehler([2]));
    expect(element.querySelector('.mit-fehler')).not.toBeNull();
    expect(speicher.inhalt.size).toBe(0);

    knopf(TEXTE.handy.verwerfen(1))!.click();
    await fixture.whenStable();
    knopf(TEXTE.anmeldung.anmelden)!.click();
    await fixture.whenStable();
    expect(element.querySelector('[data-test=erfassung-meldung]')!.textContent).toBe(TEXTE.handy.seitenMitFehler([1]));
    expect(anmeldung.anmelden).not.toHaveBeenCalled();
    expect(speicher.inhalt.size).toBe(0);

    knopf(TEXTE.handy.verwerfen(1))!.click();
    await fixture.whenStable();
    knopf(TEXTE.anmeldung.anmelden)!.click();
    await fixture.whenStable();
    expect(anmeldung.anmelden).toHaveBeenCalledWith('/erfassen');
  });

  it('behält die bisherige Arbeitsfassung, wenn das Anpassen des Zuschnitts scheitert', async () => {
    await zeigen();
    await fotoWaehlen();
    scanner.fehler = true;
    knopf(TEXTE.handy.anpassen(1))!.click();
    await fixture.whenStable();
    knopf(TEXTE.handy.ganzesBildVerwenden)!.click();
    await fixture.whenStable();
    expect(scanner.auftraege).toEqual([undefined, null]);
    expect(hinweise()).toEqual(['anpassenFehler']);
    expect(element.textContent).toContain(TEXTE.handy.anpassenFehler);
    expect(element.querySelector('.mit-fehler')).toBeNull();
    expect(element.querySelector('[data-test=seite] img')).not.toBeNull();
    expect(knopf(TEXTE.handy.anpassen(1))!.disabled).toBe(false);

    knopf(TEXTE.handy.abschliessen)!.click();
    await fixture.whenStable();
    const [eintrag] = [...speicher.inhalt.values()];
    expect(eintrag.seiten).toHaveLength(1);
    expect(await eintrag.seiten[0].arbeitsfassung.text()).toBe('zuschnitt');
  });

  it('speichert bei Doppeltipp nur einmal, sperrt die Aufnahme und behält währenddessen aufgenommene Seiten', async () => {
    let freigeben!: () => void;
    const gesperrt = new Promise<void>((weiter) => (freigeben = weiter));
    const speichern = vi.spyOn(speicher, 'speichern').mockImplementation(async (eintrag) => {
      await gesperrt;
      speicher.inhalt.set(eintrag.id, eintrag);
    });
    let n = 0;
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:${++n}`);
    const freigegeben = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    await zeigen();
    await fotoWaehlen(2);

    knopf(TEXTE.handy.abschliessen)!.click();
    knopf(TEXTE.handy.abschliessen)!.click();
    await fixture.whenStable();
    expect(knopf(TEXTE.handy.abschliessen)!.disabled).toBe(true);
    expect(knopf(TEXTE.handy.fotoWaehlen)!.disabled).toBe(true);
    expect(knopf(TEXTE.handy.verwerfen(1))!.disabled).toBe(true);
    // Eine Aufnahme, die schon unterwegs war, kommt während des Speicherns an
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file][capture=environment]')!;
    Object.defineProperty(eingabe, 'files', { value: [new File(['spaet'], 'spaet.jpg', { type: 'image/jpeg' })] });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(hinweise()).toHaveLength(3);
    const spaet = element.querySelectorAll<HTMLImageElement>('[data-test=seite] img')[2].src;

    freigeben();
    await vi.waitFor(() => expect(knopf(TEXTE.handy.fotoWaehlen)!.disabled).toBe(false));
    await fixture.whenStable();
    expect(speichern).toHaveBeenCalledOnce();
    expect([...speicher.inhalt.values()][0].seiten).toHaveLength(2);
    expect(hinweise()).toEqual(['kantenErkannt']);
    expect(element.querySelector('img')!.src).toBe(spaet);
    expect(freigegeben).toHaveBeenCalledTimes(4);
    expect(freigegeben.mock.calls.map(([url]) => url)).not.toContain(spaet);
    expect(knopf(TEXTE.handy.abschliessen)!.disabled).toBe(false);
    expect(knopf(TEXTE.handy.fotoWaehlen)!.disabled).toBe(false);
  });

  it('nennt im Dateinamen die erste verbliebene Seite, wenn die erste verworfen ist (Req 4.9)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      await zeigen();
      vi.setSystemTime(new Date('2026-11-13T07:15:00Z'));
      await fotoWaehlen();
      vi.setSystemTime(new Date('2026-11-13T07:17:00Z'));
      await fotoWaehlen();
      knopf(TEXTE.handy.verwerfen(1))!.click();
      await fixture.whenStable();
      knopf(TEXTE.handy.abschliessen)!.click();
      await fixture.whenStable();
      expect([...speicher.inhalt.values()][0].aufgenommen).toBe('2026-11-13T07:17:00.000Z');
    } finally {
      vi.useRealTimers();
    }
  });

  it('sichert ohne Anmeldung die Seiten vor der Anmeldung und kehrt danach zu «Erfassen» zurück', async () => {
    await zeigen();
    expect(element.textContent).toContain(TEXTE.handy.ohneAnmeldung);
    expect(element.querySelector('app-upload')).toBeNull();
    await fotoWaehlen();
    knopf(TEXTE.anmeldung.anmelden)!.click();
    await fixture.whenStable();
    expect(speicher.inhalt.size).toBe(1);
    expect(anmeldung.anmelden).toHaveBeenCalledWith('/erfassen');
  });

  it('zeigt angemeldet mit Recht A1 auch den Upload von Dateien', async () => {
    anmeldung.als('mitarbeiter');
    await zeigen();
    expect(element.textContent).not.toContain(TEXTE.handy.ohneAnmeldung);
    expect(element.querySelector('app-upload')).not.toBeNull();
    expect(element.querySelectorAll('h1')).toHaveLength(1);
  });
  describe('Benutzerwechsel und fehlerhafte Einträge (Req 4.10, 4.11)', () => {
    const eintrag = (id: string, benutzer: string, zustand: 'wartet' | 'fehlerhaft'): Eintrag => ({
      id,
      aufgenommen: '2026-11-13T07:15:00.000Z',
      seiten: [{ original: new Blob(['o']), arbeitsfassung: new Blob(['a']) }],
      fehlversuche: 0,
      zustand,
      meldung: zustand === 'fehlerhaft' ? 'Abgelehnt.' : undefined,
      benutzer,
      mandant: 'urinet',
    });
    const frage = () => element.querySelector('[data-test=fremde-frage]');
    const test = (name: string) => element.querySelector<HTMLButtonElement>(`[data-test=${name}]`)!;
    const klick = async (name: string) => {
      test(name).click();
      await fixture.whenStable();
    };

    it('fragt nach fremden Aufnahmen mit Anzahl und Zeitpunkt; «Löschen» verlangt eine zweite Bestätigung', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'wartet'));
      speicher.inhalt.set('b', eintrag('b', 'u3', 'wartet'));
      anmeldung.als('mitarbeiter', 'u2');
      await zeigen();
      expect(frage()?.textContent).toContain(TEXTE.handy.fremdeFrage(2));
      expect(frage()?.textContent).toContain('13.11.2026 08:15');
      expect(frage()?.textContent).not.toContain('u1');
      await klick('fremde-loeschen');
      expect(speicher.inhalt.size).toBe(2);
      expect(element.querySelector('[data-test=fremde-loeschen-frage]')?.textContent).toContain(TEXTE.handy.fremdeLoeschenFrage(2));
      knopf(TEXTE.handy.loeschenAbbrechen)!.click();
      await fixture.whenStable();
      expect(frage()).not.toBeNull();
      expect(speicher.inhalt.size).toBe(2);
      await klick('fremde-loeschen');
      await klick('fremde-loeschen-ja');
      expect(speicher.inhalt.size).toBe(0);
      expect(frage()).toBeNull();
      expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toContain(TEXTE.handy.fremdeGeloescht);
    });

    it('«Behalten» schliesst die Frage und ändert nichts', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'wartet'));
      anmeldung.als('mitarbeiter', 'u2');
      await zeigen();
      await klick('fremde-behalten');
      expect(frage()).toBeNull();
      expect(speicher.inhalt.get('a')).toMatchObject({ benutzer: 'u1', zustand: 'wartet' });
    });

    it('zeigt bei einem Fehler im Speicher eine Fehlermeldung statt «gelöscht»', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'wartet'));
      anmeldung.als('mitarbeiter', 'u2');
      await zeigen();
      vi.spyOn(speicher, 'loeschen').mockRejectedValueOnce(new Error('IndexedDB'));
      await klick('fremde-loeschen');
      await klick('fremde-loeschen-ja');
      expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toContain(TEXTE.handy.loeschenFehler);
      expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).not.toContain(TEXTE.handy.fremdeGeloescht);
    });

    it('fragt nicht für eigene Aufnahmen', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'wartet'));
      anmeldung.als('mitarbeiter', 'u1');
      await zeigen();
      expect(frage()).toBeNull();
    });

    it('löscht einen fehlerhaften Eintrag erst nach der zweiten Bestätigung und zeigt die Liste neu', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'fehlerhaft'));
      speicher.inhalt.set('b', eintrag('b', 'u1', 'fehlerhaft'));
      anmeldung.als('mitarbeiter', 'u1');
      await zeigen();
      expect(element.querySelectorAll('[data-test=fehlerhaft]')).toHaveLength(2);
      expect(knopf(TEXTE.handy.erneutVersuchen)).toBeTruthy();
      await klick('loeschen');
      expect(element.querySelector('[data-test=loeschen-frage]')?.textContent).toContain(TEXTE.handy.loeschenFrage);
      expect(speicher.inhalt.size).toBe(2);
      knopf(TEXTE.handy.loeschenAbbrechen)!.click();
      await fixture.whenStable();
      expect(speicher.inhalt.size).toBe(2);
      await klick('loeschen');
      await klick('loeschen-ja');
      expect([...speicher.inhalt.keys()]).toEqual(['b']);
      expect(element.querySelectorAll('[data-test=fehlerhaft]')).toHaveLength(1);
      expect(element.querySelector('[data-test=erfassung-meldung]')?.textContent).toContain(TEXTE.handy.geloescht);
    });

    it('öffnet die Hilfe direkt beim fehlerhaften Upload', async () => {
      speicher.inhalt.set('a', eintrag('a', 'u1', 'fehlerhaft'));
      anmeldung.als('mitarbeiter', 'u1');
      await zeigen();
      element.querySelector<HTMLAnchorElement>('[data-test=fehlerhaft] a[href="/hilfe#erfassen-offline"]')!.click();
      expect(TestBed.inject(HilfeLeiste).anker()).toBe('erfassen-offline');
      expect(TestBed.inject(HilfeLeiste).offen()).toBe(true);
      expect(speicher.inhalt.has('a')).toBe(true);
    });
  });
});
