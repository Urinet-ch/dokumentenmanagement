import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { EingangskorbEntwurfSpeicher } from '../eingangskorb/entwurf-speicher';
import { Entwurf } from '../eingangskorb/entwurf';
import { anmeldungGuard } from '../gemeinsam/anmeldung/anmeldung.guard';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Abmeldung } from './abmeldung';

describe('Abmeldung und Zugang zu «Mein Konto» (Spec 06, Req 11.10, 11.11)', () => {
  const anmeldung = { angemeldet: vi.fn(() => true), abmelden: vi.fn() };

  beforeEach(() => {
    sessionStorage.clear();
    anmeldung.abmelden.mockClear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AnmeldungService, useValue: anmeldung }],
    });
  });

  it('verwirft die Entwürfe des Eingangskorbs und meldet bei Keycloak ab', () => {
    TestBed.inject(EingangskorbEntwurfSpeicher).speichern('u', 'd1', { tags: [] } as unknown as Entwurf, {});
    expect(sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).not.toBeNull();
    TestBed.inject(Abmeldung).abmelden();
    expect(sessionStorage.getItem('archiv.eingangskorb.entwurf.v1:u:d1')).toBeNull();
    expect(anmeldung.abmelden).toHaveBeenCalledOnce();
  });

  it('lässt angemeldete Benutzer auf /konto, ohne Recht der Rechtematrix', () => {
    expect(TestBed.runInInjectionContext(() => anmeldungGuard({} as never, { url: '/konto' } as never))).toBe(true);
  });

  it('führt ohne Anmeldung zur Startseite und merkt sich /konto als Ziel', () => {
    anmeldung.angemeldet.mockReturnValueOnce(false);
    const ziel = TestBed.runInInjectionContext(() => anmeldungGuard({} as never, { url: '/konto' } as never));
    expect(TestBed.inject(Router).serializeUrl(ziel as never)).toBe('/?ziel=%2Fkonto');
  });
});
