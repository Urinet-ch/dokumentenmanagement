import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { MandantFunktionen } from '../mandant-funktionen';
import { AnmeldungService } from './anmeldung.service';
import { wissenGuard } from './wissen.guard';

describe('wissenGuard (Spec 04, Req 22.5)', () => {
  const anmeldung = {
    angemeldet: vi.fn(),
    darf: vi.fn(),
    benutzer: () => ({ id: 'u1', name: 'Test', mandant: 'urinet', rolle: 'inhaber' }),
  };
  const verfuegbar = signal(false);
  let erfuellen: () => void = () => undefined;
  const funktionen = {
    fuer: vi.fn(),
    wissenVerfuegbar: verfuegbar,
    bereit: vi.fn(() => new Promise<void>((weiter) => (erfuellen = weiter))),
  };

  function pruefen(): Promise<boolean | UrlTree> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AnmeldungService, useValue: anmeldung },
        { provide: MandantFunktionen, useValue: funktionen },
      ],
    });
    return TestBed.runInInjectionContext(() =>
      wissenGuard('A15')({} as ActivatedRouteSnapshot, { url: '/wissen' } as RouterStateSnapshot),
    ) as Promise<boolean | UrlTree>;
  }

  function url(ergebnis: boolean | UrlTree): string {
    return TestBed.inject(Router).serializeUrl(ergebnis as UrlTree);
  }

  beforeEach(() => {
    vi.clearAllMocks();
    verfuegbar.set(false);
  });

  it('schickt ohne Anmeldung zur Startseite und merkt sich das Ziel', async () => {
    anmeldung.angemeldet.mockReturnValue(false);
    expect(url(await pruefen())).toBe('/?ziel=%2Fwissen');
    expect(funktionen.fuer).not.toHaveBeenCalled();
  });

  it('wartet auf die Funktionen und zeigt ohne Freischaltung «Seite nicht gefunden»', async () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(true);
    let fertig = false;
    const ergebnis = pruefen().then((wert) => {
      fertig = true;
      return wert;
    });
    await Promise.resolve();
    expect(funktionen.fuer).toHaveBeenCalledWith('urinet|u1');
    expect(fertig).toBe(false);
    erfuellen();
    expect(url(await ergebnis)).toBe('/nicht-gefunden');
  });

  it('lässt bei Freischaltung und Recht durch', async () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(true);
    verfuegbar.set(true);
    const ergebnis = pruefen();
    await Promise.resolve();
    erfuellen();
    expect(await ergebnis).toBe(true);
    expect(anmeldung.darf).toHaveBeenCalledWith('A15');
  });

  it('schickt bei Freischaltung ohne Recht zur Startseite wie rechtGuard', async () => {
    anmeldung.angemeldet.mockReturnValue(true);
    anmeldung.darf.mockReturnValue(false);
    verfuegbar.set(true);
    const ergebnis = pruefen();
    await Promise.resolve();
    erfuellen();
    expect(url(await ergebnis)).toBe('/');
  });
});
