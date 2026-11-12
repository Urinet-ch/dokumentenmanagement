import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideApi } from './api';
import { AnmeldungService } from './anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from './anmeldung/rechte';
import { HttpEingangskorbZaehler } from './eingangskorb-zaehler';

describe('HttpEingangskorbZaehler (Req 8.6)', () => {
  function zaehler(rolle: Rolle) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        HttpEingangskorbZaehler,
        {
          provide: AnmeldungService,
          useValue: { darf: (a: Aktion) => darf(rolle, a), benutzer: signal({ rolle }) },
        },
      ],
    });
    const dienst = TestBed.inject(HttpEingangskorbZaehler);
    TestBed.tick();
    return { dienst, http: TestBed.inject(HttpTestingController) };
  }

  it('zählt über «gesamt» des Eingangskorbs', () => {
    const { dienst, http } = zaehler('inhaber');
    http
      .expectOne('/api/v1/eingangskorb?groesse=1&seite=0')
      .flush({ eintraege: [], gesamt: 4, seite: 0, groesse: 1 });
    expect(dienst.anzahl()).toBe(4);
    dienst.auffrischen();
    http
      .expectOne('/api/v1/eingangskorb?groesse=1&seite=0')
      .flush({ eintraege: [], gesamt: 3, seite: 0, groesse: 1 });
    expect(dienst.anzahl()).toBe(3);
    http.verify();
  });

  it('fragt ohne Recht A5 nicht und zeigt keinen Zähler', () => {
    const { dienst, http } = zaehler('treuhaender');
    http.expectNone('/api/v1/eingangskorb?groesse=1&seite=0');
    expect(dienst.anzahl()).toBeNull();
  });

  it('zeigt bei einem Fehler keinen Zähler', () => {
    const { dienst, http } = zaehler('inhaber');
    http
      .expectOne('/api/v1/eingangskorb?groesse=1&seite=0')
      .flush(null, { status: 500, statusText: 'Fehler' });
    expect(dienst.anzahl()).toBeNull();
  });
});
