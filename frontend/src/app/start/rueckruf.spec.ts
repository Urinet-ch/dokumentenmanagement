import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Rueckruf } from './rueckruf';

describe('Rueckruf', () => {
  let anmeldung: {
    rueckrufVerarbeiten: ReturnType<typeof vi.fn>;
    anmelden: ReturnType<typeof vi.fn>;
  };
  let router: {
    navigateByUrl: ReturnType<typeof vi.fn>;
    navigate: ReturnType<typeof vi.fn>;
  };
  let fixture: ComponentFixture<Rueckruf>;
  let element: HTMLElement;

  async function starten(query: Record<string, string>, fehlgeschlagen = false, antwortFehler?: unknown) {
    const rueckrufVerarbeiten = vi
      .fn<(parameter: URLSearchParams) => Promise<string>>()
      .mockImplementation(() => {
        if (antwortFehler) {
          return Promise.reject(antwortFehler);
        }
        return fehlgeschlagen
          ? Promise.reject(new Error('synthetischer Fehler'))
          : Promise.resolve('/erfassen');
      });
    anmeldung = {
      rueckrufVerarbeiten,
      anmelden: vi.fn(),
    };
    router = {
      navigateByUrl: vi.fn().mockResolvedValue(true),
      navigate: vi.fn().mockResolvedValue(true),
    };
    TestBed.configureTestingModule({
      imports: [Rueckruf],
      providers: [
        { provide: AnmeldungService, useValue: anmeldung },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
        { provide: Router, useValue: router },
      ],
    });
    fixture = TestBed.createComponent(Rueckruf);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('übergibt die Query-Parameter und ersetzt die Adresse durch das interne Ziel', async () => {
    await starten({ code: 'code-1', state: 'state-1', iss: 'https://auth.example.invalid' });

    expect([...anmeldung.rueckrufVerarbeiten.mock.calls[0][0].entries()]).toEqual([
      ['code', 'code-1'],
      ['state', 'state-1'],
      ['iss', 'https://auth.example.invalid'],
    ]);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/erfassen', { replaceUrl: true });
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('zeigt den Fehler, entfernt Query-Parameter und erlaubt die erneute Anmeldung', async () => {
    await starten({ error: 'access_denied', state: 'state-1' }, true);

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.anmeldung.fehlgeschlagen,
    );
    expect(router.navigate).toHaveBeenCalledWith([], { replaceUrl: true, queryParams: {} });
    expect(router.navigateByUrl).not.toHaveBeenCalled();

    element.querySelector('button')!.click();
    expect(anmeldung.anmelden).toHaveBeenCalledWith('/');
  });

  it.each([
    [0, TEXTE.httpFehler.verbindung],
    [400, TEXTE.anmeldung.fehlgeschlagen],
    [401, TEXTE.anmeldung.fehlgeschlagen],
    [403, TEXTE.anmeldung.fehlgeschlagen],
    [404, TEXTE.anmeldung.fehlgeschlagen],
    [409, TEXTE.anmeldung.fehlgeschlagen],
    [412, TEXTE.anmeldung.fehlgeschlagen],
    [429, TEXTE.httpFehler.zuViele(42)],
    [503, TEXTE.httpFehler.server],
  ] as const)('zeigt bei HTTP %s die passende Meldung mit erneutem Anmelden (400, 401, 403, 404 bleiben «Anmeldung fehlgeschlagen»)', async (status, erwartet) => {
    const antwortFehler = new HttpErrorResponse({
      status,
      headers: status === 429 ? new HttpHeaders({ 'Retry-After': '42' }) : undefined,
    });
    await starten({}, false, antwortFehler);
    expect(element.querySelector('[role=alert]')?.textContent).toBe(erwartet);
    expect(element.querySelector('button')?.textContent).toContain(TEXTE.anmeldung.anmelden);
  });
});
