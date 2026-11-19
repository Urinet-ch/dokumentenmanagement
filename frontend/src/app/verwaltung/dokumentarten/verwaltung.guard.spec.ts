import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';
import { verwaltungGuard } from './verwaltung.guard';

describe('verwaltungGuard', () => {
  const anmeldung = { angemeldet: vi.fn(), darf: vi.fn() };

  function pruefen(): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AnmeldungService, useValue: anmeldung }],
    });
    return TestBed.runInInjectionContext(() =>
      verwaltungGuard(
        {} as ActivatedRouteSnapshot,
        { url: '/verwaltung/dokumentarten' } as RouterStateSnapshot,
      ),
    ) as boolean | UrlTree;
  }

  it('leitet angemeldete Benutzer ohne A11 zur neutralen 404-Seite', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(false);

    const ergebnis = pruefen() as UrlTree;

    expect(TestBed.inject(Router).serializeUrl(ergebnis)).toBe('/nicht-gefunden');
  });

  it('lässt Benutzer mit A11 durch', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(true);

    expect(pruefen()).toBe(true);
    expect(anmeldung.darf).toHaveBeenCalledWith('A11');
  });
});
