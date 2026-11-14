import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Route,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { routes } from '../app.routes';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { MENUE } from '../gemeinsam/navigation';
import { TEXTE } from '../gemeinsam/texte.de-CH';

describe('Kontakte: Navigation, Routen und Guard (Req 17.1, 17.5)', () => {
  const rolle = { wert: 'inhaber' as Rolle | null };
  const anmeldung = { angemeldet: () => true, darf: (a: Aktion) => darf(rolle.wert, a) };

  function pruefen(route: Route, url: string): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AnmeldungService, useValue: anmeldung }],
    });
    const guard = route.canActivate![0] as (
      r: ActivatedRouteSnapshot,
      s: RouterStateSnapshot,
    ) => boolean | UrlTree;
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );
  }

  it('hat den Menüpunkt «Kontakte» mit A6', () => {
    expect(MENUE.find((p) => p.pfad === '/kontakte')).toMatchObject({
      text: TEXTE.menue.kontakte,
      aktion: 'A6',
    });
  });

  it.each([
    ['inhaber', true],
    ['geschaeftsstelle', true],
    ['mitarbeiter', false],
    ['treuhaender', false],
  ] as [Rolle, boolean][])('sieht die Rolle %s den Menüpunkt: %s', (r, sichtbar) => {
    const punkt = MENUE.find((p) => p.pfad === '/kontakte')!;
    expect(darf(r, punkt.aktion)).toBe(sichtbar);
  });

  it.each(['kontakte', 'kontakte/:id'])(
    'lädt die Route %s lazy und schützt sie mit dem Recht A6',
    (pfad) => {
      const route = routes.find((r) => r.path === pfad)!;
      expect(route.loadComponent).toBeTypeOf('function');
      expect(route.component).toBeUndefined();
      expect(route.canActivate).toHaveLength(1);
    },
  );

  it.each(['kontakte', 'kontakte/:id'])(
    'lässt auf %s Inhaber und Geschäftsstelle durch',
    (pfad) => {
      const route = routes.find((r) => r.path === pfad)!;
      for (const r of ['inhaber', 'geschaeftsstelle'] as Rolle[]) {
        TestBed.resetTestingModule();
        rolle.wert = r;
        expect(pruefen(route, `/${pfad}`)).toBe(true);
      }
    },
  );

  it.each(['kontakte', 'kontakte/:id'])(
    'leitet auf %s Mitarbeiter und Treuhänder zur Startseite',
    (pfad) => {
      const route = routes.find((r) => r.path === pfad)!;
      for (const r of ['mitarbeiter', 'treuhaender'] as Rolle[]) {
        TestBed.resetTestingModule();
        rolle.wert = r;
        const ergebnis = pruefen(route, `/${pfad}`) as UrlTree;
        expect(TestBed.inject(Router).serializeUrl(ergebnis)).toBe('/');
      }
    },
  );
});
