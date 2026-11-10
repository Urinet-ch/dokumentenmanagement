import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { anmeldungInterceptor } from './anmeldung.interceptor';
import { AnmeldungService } from './anmeldung.service';

describe('anmeldungInterceptor (Req 2.1)', () => {
  let http: HttpClient;
  let kontrolle: HttpTestingController;
  const anmeldung = { accessToken: () => 'token-1', sitzungAbgelaufen: vi.fn() };

  beforeEach(() => {
    anmeldung.sitzungAbgelaufen.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([anmeldungInterceptor])),
        provideHttpClientTesting(),
        { provide: AnmeldungService, useValue: anmeldung },
      ],
    });
    http = TestBed.inject(HttpClient);
    kontrolle = TestBed.inject(HttpTestingController);
  });

  afterEach(() => kontrolle.verify());

  it('sendet das Token nur an die eigene API', () => {
    http.get('/api/v1/dokumente').subscribe();
    http.get('https://archiv-auth.apps.urinet.ch/realms/x').subscribe();
    http.get('https://fremd.test/api/v1/x').subscribe();
    expect(kontrolle.expectOne('/api/v1/dokumente').request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(kontrolle.expectOne('https://archiv-auth.apps.urinet.ch/realms/x').request.headers.has('Authorization')).toBe(
      false,
    );
    expect(kontrolle.expectOne('https://fremd.test/api/v1/x').request.headers.has('Authorization')).toBe(false);
  });

  it('beendet die Sitzung bei 401, nicht bei 403', () => {
    http.get('/api/v1/a').subscribe({ error: () => undefined });
    http.get('/api/v1/b').subscribe({ error: () => undefined });
    kontrolle.expectOne('/api/v1/a').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(anmeldung.sitzungAbgelaufen).not.toHaveBeenCalled();
    kontrolle.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(anmeldung.sitzungAbgelaufen).toHaveBeenCalledOnce();
  });
});
