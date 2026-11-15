import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AnmeldungService, Benutzer, Sitzungszustand } from '../../../gemeinsam/anmeldung/anmeldung.service';
import { Rolle, darf } from '../../../gemeinsam/anmeldung/rechte';
import { ONLINE } from '../../../gemeinsam/online';
import { Ausgang, Eintrag } from './eintrag';
import { SpeicherImArbeitsspeicher, WarteschlangeSpeicher } from './speicher';
import { WarteschlangeUpload } from './warteschlange-upload';
import { WarteschlangeService } from './warteschlange.service';

class AnmeldungAttrappe {
  readonly benutzer = signal<Benutzer | null>(null);
  readonly zustand = signal<Sitzungszustand>('abgemeldet');
  readonly angemeldet = computed(() => this.zustand() === 'angemeldet');
  readonly sitzungAbgelaufen = vi.fn(() => this.ende('abgelaufen'));
  darf(aktion: 'A1') {
    const b = this.benutzer();
    return this.angemeldet() && !!b?.mandant && darf(b.rolle, aktion);
  }
  als(id: string, rolle: Rolle = 'mitarbeiter', mandant = 'urinet') {
    this.benutzer.set({ id, name: 'Test', mandant, rolle });
    this.zustand.set('angemeldet');
  }
  ende(zustand: Sitzungszustand = 'abgemeldet') {
    this.benutzer.set(null);
    this.zustand.set(zustand);
  }
}

class UploadAttrappe {
  readonly gesendet: string[] = [];
  readonly antworten: Ausgang[] = [];
  async senden(eintrag: Eintrag): Promise<Ausgang> {
    this.gesendet.push(eintrag.id);
    return this.antworten.shift() ?? { art: 'angenommen', ergebnisse: [{ datei: 'x', status: 'NEU', id: 'd' }] };
  }
}

const seite = () => ({ original: new Blob(['o']), arbeitsfassung: new Blob(['a']) });

describe('WarteschlangeService (Req 4.4–4.6, 4.8)', () => {
  let speicher: SpeicherImArbeitsspeicher;
  let upload: UploadAttrappe;
  let anmeldung: AnmeldungAttrappe;
  const online = signal(false);
  let schlange: WarteschlangeService;

  beforeEach(async () => {
    speicher = new SpeicherImArbeitsspeicher();
    upload = new UploadAttrappe();
    anmeldung = new AnmeldungAttrappe();
    online.set(false);
    TestBed.configureTestingModule({
      providers: [
        { provide: WarteschlangeSpeicher, useValue: speicher },
        { provide: WarteschlangeUpload, useValue: upload },
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: ONLINE, useValue: online },
      ],
    });
    schlange = TestBed.inject(WarteschlangeService);
    await schlange.geladen;
  });

  afterEach(() => vi.useRealTimers());

  async function ruhig(): Promise<void> {
    TestBed.tick();
    await schlange.verarbeiten();
  }

  it('hält Aufnahmen offline und ohne Anmeldung und zählt sie als «wartet auf Upload»', async () => {
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T07:15:00Z'));
    await schlange.hinzufuegen([seite(), seite()], new Date('2026-11-13T07:20:00Z'));
    await ruhig();
    expect(schlange.wartend()).toBe(2);
    expect(upload.gesendet).toEqual([]);
    expect(speicher.inhalt.size).toBe(2);
    expect([...speicher.inhalt.values()][0].aufgenommen).toBe('2026-11-13T07:15:00.000Z');
  });

  it('lädt nach der Anmeldung und wieder online automatisch hoch und löscht übertragene Einträge', async () => {
    await schlange.hinzufuegen([seite()], new Date());
    anmeldung.als('u1');
    await ruhig();
    expect(upload.gesendet).toEqual([]);
    online.set(true);
    await ruhig();
    expect(upload.gesendet).toHaveLength(1);
    expect(schlange.wartend()).toBe(0);
    expect(speicher.inhalt.size).toBe(0);
  });

  it('löscht Dubletten wie übertragene Einträge', async () => {
    anmeldung.als('u1');
    upload.antworten.push({ art: 'angenommen', ergebnisse: [{ datei: 'x', status: 'DUBLETTE', vorhandenId: 'v' }] });
    online.set(true);
    await schlange.hinzufuegen([seite()], new Date());
    await ruhig();
    expect(speicher.inhalt.size).toBe(0);
  });

  it('markiert nach drei Fehlversuchen online fehlerhaft und bietet die Wiederholung an', async () => {
    vi.useFakeTimers();
    anmeldung.als('u1');
    online.set(true);
    upload.antworten.push(
      { art: 'abgelehnt', status: 503 },
      { art: 'abgelehnt', status: 0 },
      { art: 'abgelehnt', status: 502 },
    );
    await schlange.hinzufuegen([seite()], new Date());
    await ruhig();
    expect(schlange.eintraege()[0]).toMatchObject({ zustand: 'wartet', fehlversuche: 1 });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(schlange.eintraege()[0]).toMatchObject({ zustand: 'wartet', fehlversuche: 2 });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(upload.gesendet).toHaveLength(3);
    expect(schlange.wartend()).toBe(0);
    expect(schlange.fehlerhaft()).toHaveLength(1);
    expect(speicher.inhalt.size).toBe(1);

    await schlange.wiederholen(schlange.fehlerhaft()[0].id);
    await ruhig();
    expect(upload.gesendet).toHaveLength(4);
    expect(speicher.inhalt.size).toBe(0);
  });

  it('versucht es nach einem Fehlversuch erst nach der Wartezeit erneut, wieder online sofort', async () => {
    anmeldung.als('u1');
    online.set(true);
    upload.antworten.push({ art: 'abgelehnt', status: 503 });
    await schlange.hinzufuegen([seite()], new Date());
    await schlange.hinzufuegen([seite()], new Date());
    await ruhig();
    expect(upload.gesendet).toHaveLength(1);
    online.set(false);
    TestBed.tick();
    online.set(true);
    await ruhig();
    expect(upload.gesendet).toHaveLength(3);
    expect(speicher.inhalt.size).toBe(0);
  });

  it('zählt Netzwerkfehler im Offline-Zustand nicht', async () => {
    anmeldung.als('u1');
    online.set(true);
    upload.antworten.push({ art: 'abgelehnt', status: 0 });
    upload.senden = async function (eintrag) {
      online.set(false);
      return UploadAttrappe.prototype.senden.call(this, eintrag);
    };
    await schlange.hinzufuegen([seite()], new Date());
    await ruhig();
    expect(schlange.eintraege()[0]).toMatchObject({ zustand: 'wartet', fehlversuche: 0 });
  });

  it('markiert 4xx sofort mit der Meldung des Servers als fehlerhaft und lädt die übrigen weiter hoch', async () => {
    anmeldung.als('u1');
    upload.antworten.push({ art: 'abgelehnt', status: 413, detail: 'Zu gross.' });
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T07:00:00Z'));
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T08:00:00Z'));
    online.set(true);
    await ruhig();
    expect(upload.gesendet).toHaveLength(2);
    expect(schlange.fehlerhaft()).toEqual([expect.objectContaining({ meldung: 'Zu gross.', fehlversuche: 0 })]);
    expect(speicher.inhalt.size).toBe(1);
  });

  it('meldet bei 401 die Sitzung als abgelaufen und behält den Eintrag', async () => {
    anmeldung.als('u1');
    upload.antworten.push({ art: 'abgelehnt', status: 401 });
    await schlange.hinzufuegen([seite()], new Date());
    online.set(true);
    await ruhig();
    expect(anmeldung.sitzungAbgelaufen).toHaveBeenCalledOnce();
    expect(schlange.eintraege()[0]).toMatchObject({ zustand: 'wartet', fehlversuche: 0 });
  });

  it('lädt Einträge nur für den Benutzer hoch, der sie erfasst hat, und nur mit Recht A1', async () => {
    anmeldung.als('u1');
    await schlange.hinzufuegen([seite()], new Date());
    anmeldung.als('u2');
    online.set(true);
    await ruhig();
    expect(upload.gesendet).toEqual([]);
    anmeldung.als('u1', 'treuhaender');
    await ruhig();
    expect(upload.gesendet).toEqual([]);
    anmeldung.als('u1');
    await ruhig();
    expect(upload.gesendet).toHaveLength(1);
  });

  it('ordnet Einträge ohne Anmeldung der nächsten Anmeldung zu und lädt sie nur in deren Mandanten hoch', async () => {
    await schlange.hinzufuegen([seite()], new Date());
    expect(schlange.wartend()).toBe(1);
    anmeldung.als('a', 'mitarbeiter', 'vhf-gsk');
    await ruhig();
    expect([...speicher.inhalt.values()][0]).toMatchObject({ benutzer: 'a', mandant: 'vhf-gsk' });
    expect(upload.gesendet).toEqual([]);

    online.set(true);
    for (const [id, mandant] of [['a', 'urinet'], ['b', 'urinet'], ['b', 'vhf-gsk']]) {
      anmeldung.als(id, 'inhaber', mandant);
      await ruhig();
      expect(schlange.wartend()).toBe(0);
    }
    anmeldung.ende();
    await ruhig();
    expect(schlange.wartend()).toBe(0);
    expect(upload.gesendet).toEqual([]);

    anmeldung.als('a', 'mitarbeiter', 'vhf-gsk');
    await ruhig();
    expect(upload.gesendet).toHaveLength(1);
  });

  it('zeigt fremde Einträge weder im Zähler noch unter «Nicht hochgeladen»', async () => {
    anmeldung.als('u1');
    upload.antworten.push({ art: 'abgelehnt', status: 400, detail: 'Ungültig.' });
    online.set(true);
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T07:00:00Z'));
    await ruhig();
    online.set(false);
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T08:00:00Z'));
    await ruhig();
    expect(schlange.fehlerhaft()).toHaveLength(1);
    expect(schlange.wartend()).toBe(1);

    anmeldung.als('u2');
    await ruhig();
    expect(schlange.fehlerhaft()).toEqual([]);
    expect(schlange.wartend()).toBe(0);
    await schlange.wiederholen([...speicher.inhalt.keys()][0]);
    expect([...speicher.inhalt.values()][0]).toMatchObject({ zustand: 'fehlerhaft' });

    anmeldung.ende();
    await ruhig();
    expect(schlange.fehlerhaft()).toEqual([]);
    expect(schlange.wartend()).toBe(0);
  });

  it('ordnet nach Ablauf der Sitzung neue Erfassungen weiter dem zuletzt angemeldeten Benutzer zu', async () => {
    anmeldung.als('u1');
    upload.antworten.push({ art: 'abgelehnt', status: 401 });
    online.set(true);
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T07:00:00Z'));
    await ruhig();
    expect(anmeldung.zustand()).toBe('abgelaufen');
    await schlange.hinzufuegen([seite()], new Date('2026-11-13T08:00:00Z'));
    expect(schlange.wartend()).toBe(2);
    expect([...speicher.inhalt.values()].map((e) => e.benutzer)).toEqual(['u1', 'u1']);

    anmeldung.als('u2');
    await ruhig();
    expect(schlange.wartend()).toBe(0);
    expect(upload.gesendet).toHaveLength(1);
    anmeldung.als('u1');
    await ruhig();
    expect(upload.gesendet).toHaveLength(3);
  });
  describe('Benutzerwechsel und fehlerhafte Einträge (Req 4.10, 4.11)', () => {
    const fremd = (id: string, benutzer = 'u1', mandant = 'urinet', zustand: Eintrag['zustand'] = 'wartet'): Eintrag => ({
      id,
      aufgenommen: `2026-11-13T07:0${id.length}:00.000Z`,
      seiten: [seite()],
      fehlversuche: 0,
      zustand,
      benutzer,
      mandant,
    });

    async function ablegen(...eintraege: Eintrag[]): Promise<void> {
      for (const e of eintraege) {
        speicher.inhalt.set(e.id, e);
      }
      await schlange['neuLaden']();
    }

    it('fragt beim Wechsel des Benutzers nach den wartenden Aufnahmen anderer Inhaber, ohne Namen', async () => {
      await ablegen(fremd('a'), fremd('bb', 'u3'));
      expect(schlange.fremdeFrage()).toBeNull();
      anmeldung.als('u2');
      await ruhig();
      expect(schlange.fremdeFrage()).toEqual({
        anzahl: 2,
        aufgenommen: ['2026-11-13T07:01:00.000Z', '2026-11-13T07:02:00.000Z'],
        ids: ['a', 'bb'],
      });
      expect(speicher.inhalt.size).toBe(2);
    });

    it('fragt auch beim Wechsel des Mandanten', async () => {
      await ablegen(fremd('a', 'u1', 'urinet'));
      anmeldung.als('u1', 'mitarbeiter', 'vhf-gsk');
      await ruhig();
      expect(schlange.fremdeFrage()?.anzahl).toBe(1);
    });

    it('fragt nicht ohne fremde, ohne wartende (fehlerhafte) und nicht für den eigenen Inhaber', async () => {
      await ablegen(fremd('a', 'u2'), fremd('bb', 'u1', 'urinet', 'fehlerhaft'));
      anmeldung.als('u2');
      await ruhig();
      expect(schlange.fremdeFrage()).toBeNull();
    });

    it('«Behalten» lässt die Einträge unverändert und fragt nur einmal, auch nach abgelaufener Sitzung', async () => {
      await ablegen(fremd('a'));
      anmeldung.als('u2');
      await ruhig();
      schlange.fremdeBehalten();
      expect(schlange.fremdeFrage()).toBeNull();
      expect(speicher.inhalt.get('a')).toMatchObject({ benutzer: 'u1', zustand: 'wartet' });
      anmeldung.ende('abgelaufen');
      anmeldung.als('u2');
      await ruhig();
      expect(schlange.fremdeFrage()).toBeNull();
      expect(schlange.wartend()).toBe(0);
      expect(upload.gesendet).toEqual([]);
    });

    it('«Löschen» entfernt nur die wartenden Einträge anderer Inhaber, eigene und fehlerhafte bleiben', async () => {
      await ablegen(fremd('a'), fremd('bb', 'u3'), fremd('ccc', 'u1', 'urinet', 'fehlerhaft'), fremd('dddd', 'u2'));
      anmeldung.als('u2');
      await ruhig();
      await schlange.fremdeLoeschen();
      expect([...speicher.inhalt.keys()].sort()).toEqual(['ccc', 'dddd']);
      expect(schlange.fremdeFrage()).toBeNull();
      expect(schlange.wartend()).toBe(1);
    });

    it('löscht ohne offene Frage nichts', async () => {
      await ablegen(fremd('a'));
      await schlange.fremdeLoeschen();
      expect(speicher.inhalt.size).toBe(1);
    });

    it('fragt nichts und löscht nichts bei Abmeldung und abgelaufener Sitzung', async () => {
      await ablegen(fremd('a'));
      anmeldung.als('u1');
      await ruhig();
      anmeldung.ende('abgelaufen');
      await ruhig();
      expect(schlange.fremdeFrage()).toBeNull();
      anmeldung.ende();
      await ruhig();
      expect(schlange.fremdeFrage()).toBeNull();
      expect(speicher.inhalt.size).toBe(1);
    });

    it('fragt nach dem Behalten wieder, wenn danach ein anderer Benutzer kommt', async () => {
      await ablegen(fremd('a'));
      anmeldung.als('u2');
      await ruhig();
      schlange.fremdeBehalten();
      anmeldung.ende();
      anmeldung.als('u3');
      await ruhig();
      expect(schlange.fremdeFrage()?.anzahl).toBe(1);
    });

    it('fragt wieder, wenn nach einem Zwischenwechsel derselbe Benutzer zurückkommt (u2 → u1 → u2)', async () => {
      await ablegen(fremd('a'));
      anmeldung.als('u2');
      await ruhig();
      schlange.fremdeBehalten();
      expect(schlange.fremdeFrage()).toBeNull();
      anmeldung.ende();
      anmeldung.als('u1');
      await ruhig();
      anmeldung.ende();
      anmeldung.als('u2');
      await ruhig();
      expect(schlange.fremdeFrage()?.anzahl).toBe(1);
    });

    it('«Löschen» löscht nur die gefragten Einträge und liefert deren Anzahl; Fehler werden weitergereicht', async () => {
      await ablegen(fremd('a'), fremd('bb', 'u3'));
      anmeldung.als('u2');
      await ruhig();
      vi.spyOn(speicher, 'loeschen').mockRejectedValueOnce(new Error('IndexedDB'));
      await expect(schlange.fremdeLoeschen()).rejects.toThrow('IndexedDB');
      expect(schlange.fremdeFrage()?.anzahl).toBe(2);
      const ids = schlange.fremdeFrage()!.ids.filter((id) => id === 'a');
      await ablegen(fremd('ccc', 'u3'));
      expect(await schlange.fremdeLoeschen(ids)).toBe(1);
      expect([...speicher.inhalt.keys()].sort()).toEqual(['bb', 'ccc']);
    });

    it('meldet, ob «loeschen» etwas gelöscht hat, und lädt bei einem Fehler trotzdem neu', async () => {
      anmeldung.als('u1');
      await ablegen(fremd('a', 'u1', 'urinet', 'fehlerhaft'));
      await ruhig();
      expect(await schlange.loeschen('gibtsnicht')).toBe(false);
      vi.spyOn(speicher, 'loeschen').mockRejectedValueOnce(new Error('IndexedDB'));
      await expect(schlange.loeschen('a')).rejects.toThrow('IndexedDB');
      expect(await schlange.loeschen('a')).toBe(true);
      expect(speicher.inhalt.size).toBe(0);
    });

    it('löscht einen fehlerhaften Eintrag des Inhabers samt Aufnahmen, nur diesen', async () => {
      anmeldung.als('u1');
      await ablegen(fremd('a', 'u1', 'urinet', 'fehlerhaft'), fremd('bb', 'u1', 'urinet', 'fehlerhaft'));
      await ruhig();
      await schlange.loeschen('a');
      expect([...speicher.inhalt.keys()]).toEqual(['bb']);
      expect(schlange.fehlerhaft().map((e) => e.id)).toEqual(['bb']);
    });

    it('löscht weder wartende noch fremde noch unbekannte Einträge über «Löschen» (kein stilles Löschen)', async () => {
      anmeldung.als('u1');
      await ablegen(fremd('a'), fremd('bb', 'u2', 'urinet', 'fehlerhaft'));
      await ruhig();
      await schlange.loeschen('a');
      await schlange.loeschen('bb');
      await schlange.loeschen('gibtsnicht');
      expect([...speicher.inhalt.keys()].sort()).toEqual(['a', 'bb']);
    });

    it('behält fehlerhafte Einträge nach Wechsel, Abmeldung und «Löschen» der Frage', async () => {
      await ablegen(fremd('a', 'u1', 'urinet', 'fehlerhaft'), fremd('bb', 'u1'));
      anmeldung.als('u2');
      await ruhig();
      await schlange.fremdeLoeschen();
      anmeldung.ende();
      await ruhig();
      expect(speicher.inhalt.get('a')).toMatchObject({ zustand: 'fehlerhaft' });
    });
  });
});
