import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AnmeldungService, Benutzer } from '../gemeinsam/anmeldung/anmeldung.service';
import { EinfuehrungVermerk } from './einfuehrung-vermerk';
import {
  MAX_VERMERKE,
  VERMERK_SCHLUESSEL,
  Vermerk,
  vermerkAblegen,
  vermerkDatum,
  vermerkeLesen,
  vermerkKennung,
} from './einfuehrung-vermerk-speicher';

const SUB_A = '4f1c2a3b-0000-4000-8000-000000000001';
const SUB_B = '4f1c2a3b-0000-4000-8000-000000000002';
// printf 'urinet:4f1c2a3b-0000-4000-8000-000000000001' | shasum -a 256
const HASH_A = 'ac1c935022222571430856850f1971abfa3483e30f462feed84ecdb1824d9e3e';

function benutzer(sub: string, mandant: string | null = 'urinet'): Benutzer {
  return { id: sub, name: 'Muster Hans', mandant, rolle: mandant ? 'mitarbeiter' : null };
}

function fremd(nr: number): Vermerk {
  return {
    k: nr.toString(16).padStart(64, '0'),
    version: 1,
    art: 'UEBERSPRUNGEN',
    am: '2026-11-01',
  };
}

function gespeichert(): unknown {
  return JSON.parse(localStorage.getItem(VERMERK_SCHLUESSEL) ?? 'null');
}

describe('Einführungsvermerk (Spec 07, Req 1.3–1.6)', () => {
  const angemeldet = signal(true);
  const aktuell = signal<Benutzer | null>(benutzer(SUB_A));

  /** Neue Instanz wie nach dem Neuladen der Seite: der Arbeitsspeicher ist leer, der localStorage bleibt. */
  function dienst(): EinfuehrungVermerk {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AnmeldungService, useValue: { angemeldet, benutzer: aktuell } },
      ],
    });
    return TestBed.inject(EinfuehrungVermerk);
  }

  async function bekannt(vermerk: EinfuehrungVermerk): Promise<boolean | null> {
    TestBed.tick();
    await vi.waitFor(() => expect(vermerk.bekannt()).not.toBeNull());
    return vermerk.bekannt();
  }

  beforeEach(() => {
    localStorage.clear();
    angemeldet.set(true);
    aktuell.set(benutzer(SUB_A));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('Kennung ist der SHA-256 (hex) von «<mandant>:<sub>»', async () => {
    expect(await vermerkKennung('urinet', SUB_A, crypto.subtle)).toBe(HASH_A);
    expect(await vermerkKennung('urinet', SUB_A, undefined)).toBeNull();
  });

  it('neuer Benutzer hat keinen Vermerk; nach dem Ablegen ist er bekannt, auch nach dem Neuladen (Req 1.3)', async () => {
    const vermerk = dienst();
    expect(await bekannt(vermerk)).toBe(false);

    await vermerk.ablegen('ABGESCHLOSSEN');

    expect(vermerk.bekannt()).toBe(true);
    expect(gespeichert()).toEqual({
      v: 1,
      eintraege: [{ k: HASH_A, version: 1, art: 'ABGESCHLOSSEN', am: vermerkDatum(new Date()) }],
    });
    expect(await bekannt(dienst())).toBe(true);
  });

  it('legt weder Benutzer-ID, Name, Mandant noch Rolle im Klartext ab und fragt nie den Server (Req 1.3)', async () => {
    const abruf = vi.spyOn(globalThis, 'fetch');
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('UEBERSPRUNGEN');

    const roh = localStorage.getItem(VERMERK_SCHLUESSEL) ?? '';
    for (const klartext of [SUB_A, 'Muster', 'urinet', 'mitarbeiter']) {
      expect(roh).not.toContain(klartext);
    }
    expect(Object.keys(localStorage)).toEqual([VERMERK_SCHLUESSEL]);
    TestBed.inject(HttpTestingController).verify();
    expect(abruf).not.toHaveBeenCalled();
  });

  it('erneutes Ablegen ersetzt den Vermerk desselben Benutzers (Req 3.5)', async () => {
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('UEBERSPRUNGEN');
    await vermerk.ablegen('ABGESCHLOSSEN');

    expect(vermerkeLesen(localStorage)).toEqual([
      { k: HASH_A, version: 1, art: 'ABGESCHLOSSEN', am: vermerkDatum(new Date()) },
    ]);
  });

  it('behält den Schritt nur im Arbeitsspeicher und trennt ihn nach Benutzer (Req 3.6)', async () => {
    const vermerk = dienst();
    await bekannt(vermerk);
    vermerk.schrittSetzen(2);
    expect(vermerk.schritt()).toBe(2);
    expect(localStorage.getItem(VERMERK_SCHLUESSEL)).toBeNull();
    aktuell.set(benutzer(SUB_B));
    expect(vermerk.schritt()).toBe(0);
    aktuell.set(benutzer(SUB_A));
    expect(vermerk.schritt()).toBe(2);
    expect(dienst().schritt()).toBe(0);
  });

  it('trennt Benutzer und Mandanten und lässt die Vermerke anderer unverändert (Req 1.4)', async () => {
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('ABGESCHLOSSEN');
    const vonA = vermerkeLesen(localStorage);

    aktuell.set(benutzer(SUB_B));
    expect(await bekannt(vermerk)).toBe(false);
    aktuell.set(benutzer(SUB_A, 'vhf-gsk'));
    expect(await bekannt(vermerk)).toBe(false);
    await vermerk.ablegen('UEBERSPRUNGEN');

    const alle = vermerkeLesen(localStorage);
    expect(alle).toHaveLength(2);
    expect(alle[0]).toEqual(vonA[0]);
    aktuell.set(benutzer(SUB_B));
    expect(await bekannt(dienst())).toBe(false);
    aktuell.set(benutzer(SUB_A));
    expect(await bekannt(dienst())).toBe(true);
  });

  it('nach der Abmeldung gibt es keinen Benutzer; der Vermerk bleibt für die nächste Anmeldung (Req 1.4)', async () => {
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('ABGESCHLOSSEN');

    angemeldet.set(false);
    aktuell.set(null);
    TestBed.tick();
    expect(vermerk.bekannt()).toBeNull();
    await vermerk.ablegen('UEBERSPRUNGEN');
    expect(vermerkeLesen(localStorage).map((v) => v.art)).toEqual(['ABGESCHLOSSEN']);

    angemeldet.set(true);
    aktuell.set(benutzer(SUB_A));
    expect(await bekannt(vermerk)).toBe(true);
  });

  it('ohne Mandant oder Rolle gibt es keinen Vermerk und kein Ablegen (Req 1.7)', async () => {
    aktuell.set(benutzer(SUB_A, null));
    const vermerk = dienst();
    TestBed.tick();
    await vermerk.ablegen('ABGESCHLOSSEN');
    expect(vermerk.bekannt()).toBeNull();
    expect(localStorage.getItem(VERMERK_SCHLUESSEL)).toBeNull();
  });

  it('höchstens 20 Vermerke, beim Schreiben eines weiteren fällt der älteste weg (Req 1.6)', async () => {
    const alt = Array.from({ length: MAX_VERMERKE }, (_, i) => fremd(i + 1));
    localStorage.setItem(VERMERK_SCHLUESSEL, JSON.stringify({ v: 1, eintraege: alt }));
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('ABGESCHLOSSEN');

    const neu = vermerkeLesen(localStorage);
    expect(neu).toHaveLength(MAX_VERMERKE);
    expect(neu.slice(0, -1)).toEqual(alt.slice(1));
    expect(neu.at(-1)?.k).toBe(HASH_A);
  });

  it('kürzt eine zu lange gespeicherte Liste auf die neuesten 20 (Req 1.6)', () => {
    const alt = Array.from({ length: 25 }, (_, i) => fremd(i + 1));
    localStorage.setItem(VERMERK_SCHLUESSEL, JSON.stringify({ v: 1, eintraege: alt }));

    expect(vermerkAblegen(localStorage, fremd(99))).toBe(true);

    expect(vermerkeLesen(localStorage)).toEqual([...alt.slice(6), fremd(99)]);
  });

  it('ein ersetzter Vermerk zählt als neuester und verdrängt nicht sich selbst (Req 1.6)', () => {
    const alt = Array.from({ length: MAX_VERMERKE }, (_, i) => fremd(i + 1));
    localStorage.setItem(VERMERK_SCHLUESSEL, JSON.stringify({ v: 1, eintraege: alt }));

    vermerkAblegen(localStorage, { ...fremd(1), art: 'ABGESCHLOSSEN' });

    expect(vermerkeLesen(localStorage)).toEqual([
      ...alt.slice(1),
      { ...fremd(1), art: 'ABGESCHLOSSEN' },
    ]);
  });

  it('gesperrter Speicher: Einführung zeigen, Vermerk bis zum Neuladen im Arbeitsspeicher, keine Ausnahme (Req 1.5)', async () => {
    const gesperrt = () => {
      throw new DOMException('Speicher gesperrt', 'SecurityError');
    };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(gesperrt);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(gesperrt);
    const vermerk = dienst();
    expect(await bekannt(vermerk)).toBe(false);

    await expect(vermerk.ablegen('UEBERSPRUNGEN')).resolves.toBeUndefined();
    expect(vermerk.bekannt()).toBe(true);
    aktuell.set(benutzer(SUB_B));
    expect(await bekannt(vermerk)).toBe(false);
    aktuell.set(benutzer(SUB_A));
    expect(await bekannt(vermerk)).toBe(true);

    expect(await bekannt(dienst())).toBe(false);
  });

  it('ohne Zugriff auf localStorage (Zugriff wirft) gilt dasselbe (Req 1.5)', async () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('Speicher gesperrt', 'SecurityError');
    });
    const vermerk = dienst();
    expect(await bekannt(vermerk)).toBe(false);
    await vermerk.ablegen('ABGESCHLOSSEN');
    expect(vermerk.bekannt()).toBe(true);
  });

  it('voller Speicher: Vermerk nur im Arbeitsspeicher (Req 1.5)', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('voll', 'QuotaExceededError');
    });
    const vermerk = dienst();
    await bekannt(vermerk);
    await vermerk.ablegen('ABGESCHLOSSEN');
    expect(vermerk.bekannt()).toBe(true);
    vi.restoreAllMocks();
    expect(await bekannt(dienst())).toBe(false);
  });

  it('ohne crypto.subtle (kein sicherer Kontext): nur Arbeitsspeicher, nichts gespeichert (Req 1.5)', async () => {
    vi.spyOn(window, 'crypto', 'get').mockReturnValue({} as Crypto);
    const vermerk = dienst();
    expect(await bekannt(vermerk)).toBe(false);
    await vermerk.ablegen('ABGESCHLOSSEN');
    expect(vermerk.bekannt()).toBe(true);
    expect(localStorage.getItem(VERMERK_SCHLUESSEL)).toBeNull();
  });

  it.each([
    ['kein JSON', '{kaputt'],
    ['falsches Format', JSON.stringify({ v: 2, eintraege: [] })],
    ['Liste fehlt', JSON.stringify({ v: 1 })],
    ['kein Objekt', JSON.stringify([1, 2])],
  ])(
    'beschädigter Eintrag (%s): Einführung zeigen, beim nächsten Schreiben ersetzen (Req 1.5)',
    async (_, wert) => {
      localStorage.setItem(VERMERK_SCHLUESSEL, wert);
      const vermerk = dienst();
      expect(await bekannt(vermerk)).toBe(false);
      await vermerk.ablegen('ABGESCHLOSSEN');
      expect(gespeichert()).toEqual({
        v: 1,
        eintraege: [{ k: HASH_A, version: 1, art: 'ABGESCHLOSSEN', am: vermerkDatum(new Date()) }],
      });
    },
  );

  it('unlesbare Einträge fallen weg, lesbare anderer Benutzer bleiben; zusätzliche Felder werden nicht übernommen', () => {
    const gut = fremd(7);
    localStorage.setItem(
      VERMERK_SCHLUESSEL,
      JSON.stringify({
        v: 1,
        eintraege: [
          { ...gut, name: 'Muster Hans' },
          { k: 'kurz', version: 1, art: 'ABGESCHLOSSEN', am: '2026-11-21' },
          { ...fremd(8), art: 'GESEHEN' },
          { ...fremd(9), version: 0 },
          { ...fremd(10), am: '21.11.2026' },
          null,
        ],
      }),
    );
    expect(vermerkeLesen(localStorage)).toEqual([gut]);
  });

  it('ein Vermerk einer anderen Fassung der Einführung zählt ebenfalls (design.md, «Einführungsvermerk»)', async () => {
    localStorage.setItem(
      VERMERK_SCHLUESSEL,
      JSON.stringify({
        v: 1,
        eintraege: [{ k: HASH_A, version: 2, art: 'UEBERSPRUNGEN', am: '2026-11-21' }],
      }),
    );
    expect(await bekannt(dienst())).toBe(true);
  });

  it('Datum in Europe/Zurich, ohne Uhrzeit', () => {
    expect(vermerkDatum(new Date('2026-11-21T23:30:00Z'))).toBe('2026-11-22');
    expect(vermerkDatum(new Date('2026-07-01T21:59:00Z'))).toBe('2026-07-01');
  });
});
