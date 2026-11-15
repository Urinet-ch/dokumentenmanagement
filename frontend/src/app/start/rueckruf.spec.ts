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

  async function starten(query: Record<string, string>, fehlgeschlagen = false) {
    const rueckrufVerarbeiten = vi
      .fn<(parameter: URLSearchParams) => Promise<string>>()
      .mockImplementation(() =>
        fehlgeschlagen
          ? Promise.reject(new Error('synthetischer Fehler'))
          : Promise.resolve('/erfassen'),
      );
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
});
