import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { Dokument, Dokumentart, provideApi } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  DokumentKlassifikationen,
  DokumentKlassifikationenService,
  LEERE_DOKUMENT_KLASSIFIKATIONEN,
} from '../dokument/klassifikationen';
import { EingangskorbKarte } from './eingangskorb-karte';

const T = TEXTE.eingangskorb;

function dokument(angaben: Partial<Dokument> = {}): Dokument {
  return {
    id: 'd1',
    eingang: '2026-11-08T09:30:00Z',
    quelle: 'UPLOAD',
    tags: [],
    vertraulichkeit: 'NORMAL',
    status: 'EINGANGSKORB',
    fristAbgelaufen: false,
    version: 3,
    warnungen: [],
    pdfaFehlt: false,
    unsicher: [],
    versionen: [],
    klassifikationen: {},
    klassifikationenUngueltig: [],
    ...angaben,
  };
}

function art(code: string): Dokumentart {
  return {
    code,
    name: code,
    gruppen: [],
    pflichtfelder: [],
    zusatzSchema: { type: 'object', properties: {} },
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: false,
    aktiv: true,
    selbstAusgestellt: false,
    belegnummerMuster: [],
    kiVorschlag: false,
    kiFelder: [],
    reihenfolge: 0,
  };
}

function klassifikationen(code: string, werte: readonly string[] = []): DokumentKlassifikationen {
  const definition = {
    code,
    name: code,
    aktiv: true,
    mehrfach: false,
    werte: werte.map((wert) => ({ code: wert, name: wert, aktiv: true })),
  };
  return {
    alle: [definition],
    art: [{ ...definition, pflicht: false, standardWert: null }],
  };
}

describe('EingangskorbKarte Klassifikationen (Req 18.4)', () => {
  let fixture: ComponentFixture<EingangskorbKarte>;
  let element: HTMLElement;
  let http: HttpTestingController;

  async function zeigen(
    d: Dokument,
    arten: Dokumentart[],
    daten: ReadonlyMap<string, DokumentKlassifikationen>,
  ) {
    TestBed.configureTestingModule({
      imports: [EingangskorbKarte],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        {
          provide: AnmeldungService,
          useValue: { darf: (aktion: Parameters<typeof darf>[1]) => darf('inhaber', aktion) },
        },
        {
          provide: DokumentKlassifikationenService,
          useValue: {
            fuerArt: (code: string) => of(daten.get(code) ?? LEERE_DOKUMENT_KLASSIFIKATIONEN),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EingangskorbKarte);
    fixture.componentRef.setInput('dokument', d);
    fixture.componentRef.setInput(
      'arten',
      new Map(arten.map((eintrag) => [eintrag.code, eintrag])),
    );
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function klicken(text: string) {
    const button = [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (candidate) => candidate.textContent?.trim() === text,
    );
    if (!button) {
      throw new Error(`Knopf «${text}» fehlt`);
    }
    button.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('bearbeitet Klassifikationswerte und sendet sie vor der Bestätigung', async () => {
    const bulletin = art('BULLETIN');
    const daten = klassifikationen('fachgebiet', ['technik', 'recht']);
    await zeigen(
      dokument({ art: bulletin.code, klassifikationen: { fachgebiet: ['technik'] } }),
      [bulletin],
      new Map([[bulletin.code, daten]]),
    );

    await klicken(T.aendern);
    const select = element.querySelector<HTMLSelectElement>('#d1-klassifikationen-fachgebiet')!;
    select.value = 'recht';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await klicken(T.bestaetigen);

    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
    expect(patch.request.body.klassifikationen).toEqual({ fachgebiet: ['recht'] });
    patch.flush(
      dokument({ art: bulletin.code, version: 4, klassifikationen: { fachgebiet: ['recht'] } }),
    );
    await fixture.whenStable();
    http.expectOne({ method: 'POST', url: '/api/v1/dokumente/d1/bestaetigen' }).flush(null, {
      status: 202,
      statusText: 'Accepted',
    });
    await fixture.whenStable();
  });

  it('zeigt nicht übertragbare Klassifikationen beim Artwechsel an und sendet sie nicht mit', async () => {
    const alt = art('ALT');
    const neu = art('NEU');
    await zeigen(
      dokument({ art: alt.code, klassifikationen: { alt: ['altwert'] } }),
      [alt, neu],
      new Map([
        [alt.code, klassifikationen('alt', ['altwert'])],
        [neu.code, klassifikationen('neu', ['neuwert'])],
      ]),
    );

    await klicken(T.aendern);
    const artWahl = element.querySelector<HTMLSelectElement>('#d1-art')!;
    artWahl.value = neu.code;
    artWahl.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('[data-test=klassifikation-alt]')?.textContent).toContain(
      'altwert',
    );

    await klicken(T.bestaetigen);
    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
    expect(patch.request.body.art).toBe(neu.code);
    expect(patch.request.body.klassifikationen).toEqual({});
    patch.flush(dokument({ art: neu.code, version: 4 }));
    await fixture.whenStable();
    http.expectOne({ method: 'POST', url: '/api/v1/dokumente/d1/bestaetigen' }).flush(null, {
      status: 202,
      statusText: 'Accepted',
    });
    await fixture.whenStable();
  });

  it('zeigt Regelhinweise an, ohne die Bestätigung zu verhindern', async () => {
    const bulletin = art('BULLETIN');
    await zeigen(
      dokument({
        art: bulletin.code,
        warnungen: [{ code: 'REGEL_HINWEIS', meldung: 'Hinweis prüfen' }],
      }),
      [bulletin],
      new Map([[bulletin.code, LEERE_DOKUMENT_KLASSIFIKATIONEN]]),
    );

    expect(element.querySelector('[data-test=regelwarnung]')?.textContent).toContain(
      'Hinweis prüfen',
    );
    await klicken(T.bestaetigen);
    http.expectOne({ method: 'POST', url: '/api/v1/dokumente/d1/bestaetigen' }).flush(null, {
      status: 202,
      statusText: 'Accepted',
    });
    await fixture.whenStable();
  });
});
