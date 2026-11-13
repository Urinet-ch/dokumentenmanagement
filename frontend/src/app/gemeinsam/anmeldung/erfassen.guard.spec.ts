import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { ONLINE } from '../online';
import { AnmeldungService } from './anmeldung.service';
import { erfassenGuard } from './erfassen.guard';

describe('erfassenGuard (Req 2.6, 4.4)', () => {
  const anmeldung = { angemeldet: vi.fn(), darf: vi.fn() };
  const online = signal(true);

  function pruefen(): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: ONLINE, useValue: online },
      ],
    });
    return TestBed.runInInjectionContext(() =>
      erfassenGuard({} as ActivatedRouteSnapshot, { url: '/erfassen' } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  const url = (ergebnis: boolean | UrlTree) => TestBed.inject(Router).serializeUrl(ergebnis as UrlTree);

  it('lässt ohne Anmeldung offline durch (Warteschlange)', () => {
    anmeldung.angemeldet.mockReturnValue(false);
    online.set(false);
    expect(pruefen()).toBe(true);
  });

  it('schickt ohne Anmeldung online zur Anmeldung und merkt sich das Ziel', () => {
    anmeldung.angemeldet.mockReturnValue(false);
    online.set(true);
    expect(url(pruefen())).toBe('/?ziel=%2Ferfassen');
  });

  it('schickt angemeldet ohne Recht A1 zur Startseite, auch offline', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    online.set(false);
    anmeldung.darf.mockReturnValue(false);
    expect(url(pruefen())).toBe('/');
    expect(anmeldung.darf).toHaveBeenCalledWith('A1');
  });

  it('lässt angemeldet mit Recht A1 durch', () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(true);
    expect(pruefen()).toBe(true);
  });
});
