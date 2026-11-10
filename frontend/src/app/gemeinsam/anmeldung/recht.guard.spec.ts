import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AnmeldungService } from './anmeldung.service';
import { rechtGuard } from './recht.guard';

describe('rechtGuard (Req 2.6)', () => {
  const anmeldung = { angemeldet: vi.fn(), darf: vi.fn() };

  function pruefen(): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AnmeldungService, useValue: anmeldung }],
    });
    return TestBed.runInInjectionContext(() =>
      rechtGuard('A11')({} as ActivatedRouteSnapshot, { url: '/verwaltung/dokumentarten' } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  it('schickt ohne Anmeldung zur Startseite und merkt sich das Ziel', () => {
    anmeldung.angemeldet.mockReturnValue(false);
    const ergebnis = pruefen() as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(ergebnis)).toBe('/?ziel=%2Fverwaltung%2Fdokumentarten');
  });

  it('schickt ohne Recht zur Startseite', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(false);
    const ergebnis = pruefen() as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(ergebnis)).toBe('/');
  });

  it('lässt mit Recht durch', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(true);
    expect(pruefen()).toBe(true);
    expect(anmeldung.darf).toHaveBeenCalledWith('A11');
  });
});
