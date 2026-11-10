import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { anmeldungInterceptor } from './anmeldung.interceptor';
import { AnmeldungFehler, AnmeldungService, FENSTER, OIDC_KONFIGURATION, sicheresZiel } from './anmeldung.service';
import { base64url, codeChallengeS256 } from './pkce';

const AUSSTELLER = 'https://auth.test/realms/urinet-archiv';
const TOKEN_URL = `${AUSSTELLER}/protocol/openid-connect/token`;
const ORIGIN = 'https://archiv.test';

function jwt(nutzlast: Record<string, unknown>): string {
  const teil = (wert: unknown) => base64url(new TextEncoder().encode(JSON.stringify(wert)));
  return `${teil({ alg: 'none' })}.${teil(nutzlast)}.`;
}

class SpeicherAttrappe implements Storage {
  private readonly werte = new Map<string, string>();
  get length() {
    return this.werte.size;
  }
  clear() {
    this.werte.clear();
  }
  getItem(schluessel: string) {
    return this.werte.get(schluessel) ?? null;
  }
  key(index: number) {
    return [...this.werte.keys()][index] ?? null;
  }
  removeItem(schluessel: string) {
    this.werte.delete(schluessel);
  }
  setItem(schluessel: string, wert: string) {
    this.werte.set(schluessel, wert);
  }
  inhalt() {
    return [...this.werte.values()].join('|');
  }
}

describe('AnmeldungService (Req 2.1)', () => {
  let dienst: AnmeldungService;
  let http: HttpTestingController;
  let speicher: SpeicherAttrappe;
  let weiterleitung: ReturnType<typeof vi.fn<(url: string) => void>>;

  beforeEach(() => {
    speicher = new SpeicherAttrappe();
    weiterleitung = vi.fn<(url: string) => void>();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([anmeldungInterceptor])),
        provideHttpClientTesting(),
        { provide: OIDC_KONFIGURATION, useValue: { aussteller: AUSSTELLER, clientId: 'archiv-pwa' } },
        {
          provide: FENSTER,
          useValue: { location: { origin: ORIGIN, assign: weiterleitung }, sessionStorage: speicher },
        },
      ],
    });
    dienst = TestBed.inject(AnmeldungService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    try {
      http.verify();
    } finally {
      // Auch nach einem Fehlschlag zurücksetzen, sonst schlagen die folgenden Tests mit fehl
      TestBed.resetTestingModule();
    }
  });

  async function anmeldungStarten(ziel = '/erfassen'): Promise<URL> {
    await dienst.anmelden(ziel);
    return new URL(weiterleitung.mock.lastCall![0]);
  }

  function idToken(adresse: URL, abweichend: Record<string, unknown> = {}): string {
    return jwt({ iss: AUSSTELLER, aud: 'archiv-pwa', nonce: adresse.searchParams.get('nonce'), ...abweichend });
  }

  const accessToken = jwt({
    sub: 'u-1',
    name: 'Fabian Aschwanden',
    mandant: 'urinet',
    realm_access: { roles: ['inhaber', 'offline_access'] },
  });

  async function anmelden(expiresIn = 300): Promise<string> {
    const adresse = await anmeldungStarten();
    const rueckruf = dienst.rueckrufVerarbeiten(
      new URLSearchParams({ code: 'code-1', state: adresse.searchParams.get('state')! }),
    );
    http.expectOne(TOKEN_URL).flush({
      access_token: accessToken,
      id_token: idToken(adresse),
      refresh_token: 'refresh-1',
      expires_in: expiresIn,
    });
    return rueckruf;
  }

  it('leitet mit PKCE S256 zu Keycloak weiter', async () => {
    const adresse = await anmeldungStarten();
    expect(adresse.origin + adresse.pathname).toBe(`${AUSSTELLER}/protocol/openid-connect/auth`);
    const p = adresse.searchParams;
    expect(p.get('client_id')).toBe('archiv-pwa');
    expect(p.get('response_type')).toBe('code');
    expect(p.get('redirect_uri')).toBe(`${ORIGIN}/anmeldung`);
    expect(p.get('code_challenge_method')).toBe('S256');
    const vorgang = JSON.parse(speicher.getItem('archiv.anmeldung.vorgang')!);
    expect(p.get('code_challenge')).toBe(await codeChallengeS256(vorgang.verifier));
    expect(p.get('state')).toBe(vorgang.state);
    expect(p.get('nonce')).toBe(vorgang.nonce);
  });

  it('tauscht den Code mit dem Verifier und hält die Tokens nur im Speicher', async () => {
    const adresse = await anmeldungStarten();
    const vorgang = JSON.parse(speicher.getItem('archiv.anmeldung.vorgang')!);
    const rueckruf = dienst.rueckrufVerarbeiten(
      new URLSearchParams({ code: 'code-1', state: adresse.searchParams.get('state')! }),
    );
    const anfrage = http.expectOne(TOKEN_URL);
    const felder = new URLSearchParams(anfrage.request.body as string);
    expect(felder.get('grant_type')).toBe('authorization_code');
    expect(felder.get('code_verifier')).toBe(vorgang.verifier);
    anfrage.flush({ access_token: accessToken, id_token: idToken(adresse), refresh_token: 'r', expires_in: 300 });

    expect(await rueckruf).toBe('/erfassen');
    expect(dienst.angemeldet()).toBe(true);
    expect(dienst.accessToken()).toBe(accessToken);
    expect(dienst.benutzer()).toEqual({ id: 'u-1', name: 'Fabian Aschwanden', mandant: 'urinet', rolle: 'inhaber' });
    expect(dienst.darf('A13')).toBe(true);
    expect(speicher.length).toBe(0);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('lehnt einen Rückruf mit fremdem state ab', async () => {
    await anmeldungStarten();
    await expect(dienst.rueckrufVerarbeiten(new URLSearchParams({ code: 'c', state: 'fremd' }))).rejects.toThrow(
      AnmeldungFehler,
    );
    expect(speicher.length).toBe(0);
    expect(dienst.angemeldet()).toBe(false);
  });

  it('lehnt einen Rückruf mit fremdem Aussteller im Parameter iss ab (RFC 9207)', async () => {
    const adresse = await anmeldungStarten();
    const state = adresse.searchParams.get('state')!;
    await expect(
      dienst.rueckrufVerarbeiten(new URLSearchParams({ code: 'c', state, iss: 'https://fremd.test' })),
    ).rejects.toThrow(AnmeldungFehler);
    http.expectNone(TOKEN_URL);
    expect(dienst.angemeldet()).toBe(false);
  });

  it('nimmt einen Rückruf mit dem eigenen Aussteller im Parameter iss an', async () => {
    const adresse = await anmeldungStarten();
    const rueckruf = dienst.rueckrufVerarbeiten(
      new URLSearchParams({ code: 'c', state: adresse.searchParams.get('state')!, iss: AUSSTELLER }),
    );
    http.expectOne(TOKEN_URL).flush({ access_token: accessToken, id_token: idToken(adresse), expires_in: 300 });
    await rueckruf;
    expect(dienst.angemeldet()).toBe(true);
  });

  it('lehnt ein ID-Token mit falscher nonce oder fremdem Aussteller ab', async () => {
    for (const falsch of [{ nonce: 'fremd' }, { iss: 'https://fremd.test' }, { aud: 'anderer-client' }]) {
      const adresse = await anmeldungStarten();
      const rueckruf = dienst.rueckrufVerarbeiten(
        new URLSearchParams({ code: 'c', state: adresse.searchParams.get('state')! }),
      );
      http.expectOne(TOKEN_URL).flush({ access_token: accessToken, id_token: idToken(adresse, falsch) });
      await expect(rueckruf).rejects.toThrow(AnmeldungFehler);
      expect(dienst.angemeldet()).toBe(false);
    }
  });

  it('hat ohne Mandant im Token keine Rechte', async () => {
    const adresse = await anmeldungStarten();
    const rueckruf = dienst.rueckrufVerarbeiten(
      new URLSearchParams({ code: 'c', state: adresse.searchParams.get('state')! }),
    );
    http.expectOne(TOKEN_URL).flush({
      access_token: jwt({ sub: 'u', realm_access: { roles: ['inhaber'] } }),
      id_token: idToken(adresse),
    });
    await rueckruf;
    expect(dienst.angemeldet()).toBe(true);
    expect(dienst.darf('A2')).toBe(false);
  });

  it('meldet bei Keycloak ab und verwirft die Tokens', async () => {
    await anmelden();
    dienst.abmelden();
    const adresse = new URL(weiterleitung.mock.lastCall![0]);
    expect(adresse.pathname).toBe('/realms/urinet-archiv/protocol/openid-connect/logout');
    expect(adresse.searchParams.get('post_logout_redirect_uri')).toBe(`${ORIGIN}/`);
    expect(adresse.searchParams.get('id_token_hint')).toBeTruthy();
    expect(dienst.zustand()).toBe('abgemeldet');
    expect(dienst.accessToken()).toBeNull();
  });

  it('beendet die Sitzung bei 401 der API', async () => {
    await anmelden();
    dienst.sitzungAbgelaufen();
    expect(dienst.zustand()).toBe('abgelaufen');
    expect(dienst.accessToken()).toBeNull();
  });

  it('erneuert das Token vor Ablauf und meldet «abgelaufen», wenn Keycloak ablehnt', async () => {
    vi.useFakeTimers();
    await anmelden(60);
    await vi.advanceTimersByTimeAsync(30_000);
    const erneuerung = http.expectOne(TOKEN_URL);
    expect(new URLSearchParams(erneuerung.request.body as string).get('grant_type')).toBe('refresh_token');
    const neu = jwt({ sub: 'u-1', mandant: 'urinet', realm_access: { roles: ['inhaber'] }, neu: true });
    erneuerung.flush({ access_token: neu, refresh_token: 'refresh-2', expires_in: 60 });
    await vi.advanceTimersByTimeAsync(0);
    expect(dienst.accessToken()).toBe(neu);

    await vi.advanceTimersByTimeAsync(30_000);
    http.expectOne(TOKEN_URL).flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });
    await vi.advanceTimersByTimeAsync(0);
    expect(dienst.zustand()).toBe('abgelaufen');
  });

  it('wiederholt die Erneuerung bei Netzfehlern, solange das Token gilt', async () => {
    vi.useFakeTimers();
    await anmelden(60);
    await vi.advanceTimersByTimeAsync(30_000);
    http.expectOne(TOKEN_URL).error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(0);
    expect(dienst.angemeldet()).toBe(true);

    await vi.advanceTimersByTimeAsync(15_000);
    http.expectOne(TOKEN_URL).error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(0);
    expect(dienst.zustand()).toBe('abgelaufen');
  });

  describe('Timer im Ruhezustand verpasst (Handy gesperrt)', () => {
    const neu = jwt({ sub: 'u-1', mandant: 'urinet', realm_access: { roles: ['inhaber'] }, neu: true });

    async function schlafen(): Promise<void> {
      vi.useFakeTimers();
      await anmelden(60);
      // Die Uhr läuft weiter, der Erneuerungs-Timer nicht (setSystemTime löst keine Timer aus)
      vi.setSystemTime(Date.now() + 10 * 60_000);
    }

    it('erneuert das abgelaufene Token, bevor die API-Anfrage rausgeht', async () => {
      await schlafen();
      let antwort: unknown;
      TestBed.inject(HttpClient).get('/api/v1/dokumente').subscribe((wert) => (antwort = wert));
      await vi.advanceTimersByTimeAsync(0);
      http.expectNone('/api/v1/dokumente');
      const erneuerung = http.expectOne(TOKEN_URL);
      expect(new URLSearchParams(erneuerung.request.body as string).get('grant_type')).toBe('refresh_token');
      erneuerung.flush({ access_token: neu, refresh_token: 'refresh-2', expires_in: 60 });
      await vi.advanceTimersByTimeAsync(0);

      const api = http.expectOne('/api/v1/dokumente');
      expect(api.request.headers.get('Authorization')).toBe(`Bearer ${neu}`);
      api.flush([]);
      expect(antwort).toEqual([]);
      expect(dienst.zustand()).toBe('angemeldet');
    });

    it('teilt eine Erneuerung zwischen gleichzeitigen API-Anfragen', async () => {
      await schlafen();
      const client = TestBed.inject(HttpClient);
      client.get('/api/v1/a').subscribe();
      client.get('/api/v1/b').subscribe();
      await vi.advanceTimersByTimeAsync(0);
      http.expectOne(TOKEN_URL).flush({ access_token: neu, refresh_token: 'refresh-2', expires_in: 60 });
      await vi.advanceTimersByTimeAsync(0);
      for (const pfad of ['/api/v1/a', '/api/v1/b']) {
        expect(http.expectOne(pfad).request.headers.get('Authorization')).toBe(`Bearer ${neu}`);
      }
    });

    it('erneuert beim Zurückkehren in den Vordergrund', async () => {
      await schlafen();
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);
      http.expectOne(TOKEN_URL).flush({ access_token: neu, refresh_token: 'refresh-2', expires_in: 60 });
      await vi.advanceTimersByTimeAsync(0);
      expect(dienst.accessToken()).toBe(neu);
    });

    it('erneuert nicht vor jeder Anfrage, solange das Token noch gilt', async () => {
      vi.useFakeTimers();
      await anmelden(300);
      TestBed.inject(HttpClient).get('/api/v1/dokumente').subscribe();
      await vi.advanceTimersByTimeAsync(0);
      http.expectNone(TOKEN_URL);
      expect(http.expectOne('/api/v1/dokumente').request.headers.get('Authorization')).toBe(`Bearer ${accessToken}`);
    });
  });

  it('lässt als Ziel nur Pfade innerhalb der App zu', () => {
    expect(sicheresZiel('/erfassen?x=1')).toBe('/erfassen?x=1');
    expect(sicheresZiel('//boese.test')).toBe('/');
    expect(sicheresZiel('/\\boese.test')).toBe('/');
    expect(sicheresZiel('https://boese.test')).toBe('/');
    expect(sicheresZiel(undefined)).toBe('/');
  });
});
