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
});
