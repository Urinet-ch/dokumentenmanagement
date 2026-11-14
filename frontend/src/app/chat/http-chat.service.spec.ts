import {
  HttpEventType,
  HttpHeaderResponse,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { anmeldungInterceptor } from '../gemeinsam/anmeldung/anmeldung.interceptor';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { ChatEreignis, ChatFehler } from './chat-port';
import { HttpChatService, sekundenAus } from './http-chat.service';

describe('HttpChatService (Spec 02, Aufgabe 7.4)', () => {
  let dienst: HttpChatService;
  let http: HttpTestingController;
  let abgelaufen: number;

  beforeEach(() => {
    abgelaufen = 0;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([anmeldungInterceptor])),
        provideHttpClientTesting(),
        HttpChatService,
        {
          provide: AnmeldungService,
          useValue: {
            gueltigesAccessToken: () => Promise.resolve('token-1'),
            sitzungAbgelaufen: () => abgelaufen++,
          },
        },
      ],
    });
    dienst = TestBed.inject(HttpChatService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Stellt eine Frage und wartet, bis der Interceptor das Token angehängt hat. */
  async function fragen(gespraechId: string | null = null) {
    const ereignisse: ChatEreignis[] = [];
    const ende: { fehler?: ChatFehler; fertig: boolean } = { fertig: false };
    const abo = dienst.fragen('Welche Rechnungen sind offen?', gespraechId).subscribe({
      next: (e) => ereignisse.push(e),
      error: (f: ChatFehler) => (ende.fehler = f),
      complete: () => (ende.fertig = true),
    });
    await Promise.resolve();
    await Promise.resolve();
    const anfrage = http.expectOne('/api/v1/chat');
    return { ereignisse, ende, abo, anfrage };
  }

  function kopf(anfrage: TestRequest, status = 200) {
    anfrage.event(new HttpHeaderResponse({ status, statusText: 'OK' }));
  }

  function teil(anfrage: TestRequest, gesamt: string) {
    anfrage.event({
      type: HttpEventType.DownloadProgress,
      loaded: gesamt.length,
      partialText: gesamt,
    });
  }

  it('sendet die Frage mit Token und Accept text/event-stream, ohne gespraechId beim ersten Mal', async () => {
    const { anfrage } = await fragen();
    expect(anfrage.request.method).toBe('POST');
    expect(anfrage.request.body).toEqual({ frage: 'Welche Rechnungen sind offen?' });
    expect(anfrage.request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(anfrage.request.headers.get('Accept')).toBe('text/event-stream');
    expect(anfrage.request.responseType).toBe('text');
    expect(anfrage.request.reportProgress).toBe(true);
    anfrage.flush('event: fertig\ndata: {"gespraechId":"g1","tokens":1}\n\n');
  });

  it('setzt ein Gespräch mit der gespraechId fort', async () => {
    const { anfrage } = await fragen('g7');
    expect(anfrage.request.body).toEqual({
      frage: 'Welche Rechnungen sind offen?',
      gespraechId: 'g7',
    });
    anfrage.flush('event: fertig\ndata: {"gespraechId":"g7","tokens":1}\n\n');
  });

  it('liefert die Ereignisse aus den Teilantworten, sobald sie vollständig sind', async () => {
    const { ereignisse, ende, anfrage } = await fragen();
    kopf(anfrage);
    let text = 'event: werkzeug\ndata: {"name":"offene_posten"}\n\nevent: text\ndata: {"del';
    teil(anfrage, text);
    expect(ereignisse).toEqual([{ typ: 'werkzeug', name: 'offene_posten' }]);
    text += 'ta":"Zwei "}\n\nevent: text\ndata: {"delta":"Rechnungen."}\n\n';
    teil(anfrage, text);
    expect(ereignisse.slice(1)).toEqual([
      { typ: 'text', delta: 'Zwei ' },
      { typ: 'text', delta: 'Rechnungen.' },
    ]);
    text += 'event: quellen\ndata: [{"titel":"Rechnung 2026-004","link":"/dokument/d1"}]\n\n';
    text += 'event: fertig\ndata: {"gespraechId":"g1","tokens":42}\n\n';
    teil(anfrage, text);
    expect(ende.fertig).toBe(false);
    anfrage.event(new HttpResponse({ status: 200, body: text }));
    expect(ereignisse.slice(3)).toEqual([
      { typ: 'quellen', quellen: [{ titel: 'Rechnung 2026-004', link: '/dokument/d1' }] },
      { typ: 'fertig', gespraechId: 'g1' },
    ]);
    expect(ende).toEqual({ fertig: true });
  });

  it('meldet eine Antwort ohne fertig oder fehler als unvollständig', async () => {
    const { ende, anfrage } = await fragen();
    anfrage.flush('event: text\ndata: {"delta":"abgeris"}\n\n');
    expect(ende.fehler?.art).toBe('ANTWORT');
  });

  it('meldet ungültiges JSON als Fehler der Antwort', async () => {
    const { ende, anfrage } = await fragen();
    kopf(anfrage);
    teil(anfrage, 'event: text\ndata: {kaputt\n\n');
    expect(ende.fehler?.art).toBe('ANTWORT');
  });

  it('beendet nach fehler und übernimmt die gespraechId', async () => {
    const { ereignisse, ende, anfrage } = await fragen();
    anfrage.flush(
      'event: fehler\ndata: {"meldung":"Der Chat ist im Moment nicht verfügbar.","gespraechId":"g3"}\n\n',
    );
    expect(ereignisse).toEqual([
      { typ: 'fehler', meldung: 'Der Chat ist im Moment nicht verfügbar.', gespraechId: 'g3' },
    ]);
    expect(ende.fertig).toBe(true);
  });

  it('beendet bei 401 die Sitzung über den Interceptor und meldet SITZUNG', async () => {
    const { ende, anfrage } = await fragen();
    anfrage.flush({ fehlercode: 'NICHT_ANGEMELDET' }, { status: 401, statusText: 'Unauthorized' });
    expect(ende.fehler?.art).toBe('SITZUNG');
    expect(abgelaufen).toBe(1);
  });

  it('meldet ein fremdes Gespräch (404) als NICHT_GEFUNDEN', async () => {
    const { ende, anfrage } = await fragen('fremd');
    anfrage.flush({ fehlercode: 'NICHT_GEFUNDEN' }, { status: 404, statusText: 'Not Found' });
    expect(ende.fehler?.art).toBe('NICHT_GEFUNDEN');
  });

  it('liest bei 429 die Sekunden aus Retry-After', async () => {
    const { ende, anfrage } = await fragen();
    anfrage.flush(
      { fehlercode: 'ZU_VIELE_ANFRAGEN' },
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '1200' } },
    );
    expect(ende.fehler?.art).toBe('ZU_VIELE');
    expect(ende.fehler?.sekunden).toBe(1200);
  });

  it('meldet 400 als ungültige Frage und Status 0 als fehlende Verbindung', async () => {
    const erste = await fragen();
    erste.anfrage.flush(
      { fehlercode: 'ANFRAGE_UNGUELTIG' },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(erste.ende.fehler?.art).toBe('UNGUELTIG');
    const zweite = await fragen();
    zweite.anfrage.error(new ProgressEvent('error'), { status: 0, statusText: '' });
    expect(zweite.ende.fehler?.art).toBe('VERBINDUNG');
  });

  it('bricht die Anfrage beim Abmelden ab (Taste «Abbrechen», Seite verlassen)', async () => {
    const { ereignisse, abo, anfrage } = await fragen();
    kopf(anfrage);
    teil(anfrage, 'event: text\ndata: {"delta":"a"}\n\n');
    abo.unsubscribe();
    expect(anfrage.cancelled).toBe(true);
    expect(ereignisse).toHaveLength(1);
  });

  it('lädt die Gesprächsliste und die Nachrichten eines Gesprächs', async () => {
    let liste: unknown;
    dienst.gespraeche().subscribe((l) => (liste = l));
    await Promise.resolve();
    await Promise.resolve();
    http
      .expectOne('/api/v1/chat/gespraeche')
      .flush([{ id: 'g1', titel: 'Offene Rechnungen', geaendert: '2026-11-14T08:00:00Z' }]);
    expect(liste).toEqual([
      { id: 'g1', titel: 'Offene Rechnungen', geaendert: '2026-11-14T08:00:00Z' },
    ]);

    let nachrichten: unknown;
    dienst.gespraech('g/1').subscribe((n) => (nachrichten = n));
    await Promise.resolve();
    await Promise.resolve();
    http.expectOne('/api/v1/chat/gespraeche/g%2F1').flush({
      nachrichten: [
        { rolle: 'BENUTZER', inhalt: 'Frage', quellen: [], zeit: '2026-11-14T08:00:00Z' },
        {
          rolle: 'ASSISTENT',
          inhalt: 'Antwort',
          quellen: [{ titel: 'R', link: '/dokument/d1' }, { unbekannt: true }],
          status: 'OK',
          zeit: '2026-11-14T08:00:05Z',
        },
      ],
    });
    expect(nachrichten).toEqual([
      {
        rolle: 'BENUTZER',
        inhalt: 'Frage',
        quellen: [],
        status: null,
        zeit: '2026-11-14T08:00:00Z',
      },
      {
        rolle: 'ASSISTENT',
        inhalt: 'Antwort',
        quellen: [{ titel: 'R', link: '/dokument/d1' }],
        status: 'OK',
        zeit: '2026-11-14T08:00:05Z',
      },
    ]);
  });

  it('meldet ein fremdes Gespräch beim Laden als NICHT_GEFUNDEN', async () => {
    let fehler: ChatFehler | undefined;
    dienst.gespraech('fremd').subscribe({ error: (f: ChatFehler) => (fehler = f) });
    await Promise.resolve();
    await Promise.resolve();
    http
      .expectOne('/api/v1/chat/gespraeche/fremd')
      .flush(null, { status: 404, statusText: 'Not Found' });
    expect(fehler?.art).toBe('NICHT_GEFUNDEN');
  });

  it('liest Retry-After als Sekunden oder HTTP-Datum', () => {
    expect(sekundenAus('90')).toBe(90);
    expect(sekundenAus(null)).toBeNull();
    expect(sekundenAus('bald')).toBeNull();
    const jetzt = Date.parse('2026-11-14T08:00:00Z');
    expect(sekundenAus('Sat, 14 Nov 2026 08:10:00 GMT', jetzt)).toBe(600);
  });
});
