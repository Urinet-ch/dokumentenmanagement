import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Dokument, Dokumentart, provideApi } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle, Aktion, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { EingangskorbKarte } from './eingangskorb-karte';
import { feldName } from './entwurf';

const T = TEXTE.eingangskorb;

function dokument(angaben: Partial<Dokument> = {}): Dokument {
  return {
    id: 'd1',
    eingang: '2026-11-08T09:30:00Z',
    quelle: 'UPLOAD',
    tags: [],
    vertraulichkeit: 'PERSONAL',
    status: 'EINGANGSKORB',
    fristAbgelaufen: false,
    version: 3,
    warnungen: [],
    pdfaFehlt: false,
    unsicher: [],
    versionen: [],
    ...angaben,
  };
}

const ARTEN: Dokumentart[] = [
  {
    code: 'LIEFERANTENRECHNUNG',
    name: 'Lieferantenrechnung',
    gruppen: ['finanz'],
    pflichtfelder: ['datum', 'partner', 'betrag'],
    zusatzSchema: {},
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: true,
    aktiv: true,
  },
  {
    code: 'LOHNABRECHNUNG',
    name: 'Lohnabrechnung',
    gruppen: [],
    pflichtfelder: ['datum', 'zusatz.periode'],
    zusatzSchema: {},
    vertraulichkeit: 'PERSONAL',
    gesetzlichePflicht: true,
    aktiv: true,
  },
];

const VHF_ART: Dokumentart = {
  code: 'BULLETIN',
  name: 'Bulletin',
  gruppen: [],
  pflichtfelder: [
    'zusatz.reihe',
    'zusatz.ausgabe',
    'zusatz.jahr',
    'zusatz.fachgebiet',
    'zusatz.zugriff',
    'zusatz.veroeffentlicht',
  ],
  zusatzSchema: {
    type: 'object',
    properties: {
      reihe: { type: 'string' },
      ausgabe: { type: 'string' },
      jahr: { type: 'integer' },
      fachgebiet: { type: 'string', enum: ['Bad', 'Eis', 'Technik', 'Recht', 'Allgemein'] },
      zugriff: { type: 'string', enum: ['oeffentlich', 'mitglieder', 'vorstand'], default: 'mitglieder' },
      veroeffentlicht: { type: 'boolean', default: false },
    },
  },
  vertraulichkeit: 'NORMAL',
  gesetzlichePflicht: false,
  aktiv: true,
};

describe('EingangskorbKarte (Req 8.1, 8.5, 8.7, 8.10)', () => {
  let fixture: ComponentFixture<EingangskorbKarte>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let erledigt: string[];
  let veraltet: string[];

  async function zeigen(d: Dokument, rolle: Rolle = 'inhaber', arten: Dokumentart[] = ARTEN) {
    TestBed.configureTestingModule({
      imports: [EingangskorbKarte],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        { provide: AnmeldungService, useValue: { darf: (a: Aktion) => darf(rolle, a) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EingangskorbKarte);
    fixture.componentRef.setInput('dokument', d);
    fixture.componentRef.setInput('arten', new Map(arten.map((a) => [a.code, a])));
    erledigt = [];
    veraltet = [];
    fixture.componentInstance.erledigt.subscribe((m) => erledigt.push(m));
    fixture.componentInstance.veraltet.subscribe((m) => veraltet.push(m));
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const knoepfe = () => [...element.querySelectorAll('button')].map((b) => b.textContent?.trim());

  async function klicken(text: string) {
    const knopf = [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    );
    if (!knopf) {
      throw new Error(`Knopf «${text}» fehlt`);
    }
    knopf.click();
    await fixture.whenStable();
  }

  async function antworten(
    url: string,
    body: Parameters<ReturnType<HttpTestingController['expectOne']>['flush']>[0],
    status = 200,
    statusText = 'OK',
  ) {
    const anfrage = http.expectOne(url);
    anfrage.flush(body, { status, statusText });
    await fixture.whenStable();
    return anfrage.request;
  }

  const MIT_VORSCHLAG = dokument({
    projekt: 'P-1',
    personBenutzer: 'u-1',
    geschaeftsjahr: 2026,
    vorschlag: {
      art: 'LIEFERANTENRECHNUNG',
      titel: 'Rechnung Muster AG',
      datum: '2026-11-02',
      partnerName: 'Muster AG',
      betrag: 1234.5,
      waehrung: 'CHF',
      vertraulichkeit: null,
    },
    unsicher: ['betrag'],
  });

  it('zeigt den Vorschlag mit «bitte prüfen» und bestätigt in einem Tipp mit dem ganzen Body', async () => {
    await zeigen(MIT_VORSCHLAG);
    expect(element.textContent).toContain('Rechnung Muster AG');
    expect(element.textContent).toContain("CHF 1'234.50");
    expect(element.textContent).toContain('02.11.2026');
    expect(element.textContent).toContain(T.partnerVorschlag('Muster AG'));
    const pruefen = element.querySelectorAll('[data-test=bitte-pruefen]');
    expect(pruefen.length).toBe(1);
    expect(pruefen[0].closest('[data-feld]')?.getAttribute('data-feld')).toBe('betrag');
    expect(knoepfe()).toEqual([T.bestaetigen, T.aendern, T.verwerfen]);

    await klicken(T.bestaetigen);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect(anfrage.headers.get('If-Match')).toBe('"3"');
    expect(anfrage.body).toEqual(
      expect.objectContaining({
        art: 'LIEFERANTENRECHNUNG',
        titel: 'Rechnung Muster AG',
        datum: '2026-11-02',
        partnerId: null,
        projekt: 'P-1',
        personBenutzer: 'u-1',
        geschaeftsjahr: 2026,
        betrag: 1234.5,
        waehrung: 'CHF',
      }),
    );
    expect(anfrage.body).not.toHaveProperty('vertraulichkeit');
    expect(anfrage.body).not.toHaveProperty('ohnePdfa');
    expect(erledigt).toEqual([T.erfolgBestaetigt('Rechnung Muster AG')]);
  });

  it('zeigt Zusatzfelder je Art und setzt die Zugriffsstufe manuell auf Mitglieder', async () => {
    await zeigen(
      dokument({
        art: 'BULLETIN',
        vorschlag: { zugriff: 'vorstand', veroeffentlicht: true, betrag: 123.45, waehrung: 'CHF' },
      }),
      'geschaeftsstelle',
      [VHF_ART],
    );
    await klicken(T.aendern);

    expect(element.querySelector('[data-test=zusatzfelder]')).not.toBeNull();
    expect(element.querySelector('#d1-zusatz-reihe')).not.toBeNull();
    expect(element.querySelector('#d1-zusatz-ausgabe')).not.toBeNull();
    expect(element.querySelector('#d1-zusatz-jahr')).not.toBeNull();
    expect(element.querySelector('#d1-zusatz-fachgebiet')).not.toBeNull();
    expect(element.querySelector<HTMLInputElement>('#d1-zusatz-veroeffentlicht')).toBeNull();
    expect(element.querySelector<HTMLSelectElement>('#d1-zusatz-zugriff')?.value).toBe('mitglieder');
    expect(
      [...element.querySelectorAll<HTMLSelectElement>('#d1-zusatz-zugriff option')].map((o) => o.value),
    ).toEqual(['', 'oeffentlich', 'mitglieder', 'vorstand']);
    expect(element.querySelector('#d1-betrag')).toBeNull();
  });

  it('speichert Zusatzfelder vor der Bestätigung und setzt veroeffentlicht nicht durch die KI', async () => {
    await zeigen(
      dokument({
        art: 'BULLETIN',
        vorschlag: { zugriff: 'vorstand', veroeffentlicht: true, betrag: 123.45, waehrung: 'CHF' },
      }),
      'geschaeftsstelle',
      [VHF_ART],
    );
    await klicken(T.bestaetigen);
    http.expectNone('/api/v1/dokumente/d1/bestaetigen');

    const textfeld = (id: string, value: string) => {
      const input = element.querySelector<HTMLInputElement>(id)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    textfeld('#d1-zusatz-reihe', 'Bulletin');
    textfeld('#d1-zusatz-ausgabe', '12');
    textfeld('#d1-zusatz-jahr', '2026');
    const fachgebiet = element.querySelector<HTMLSelectElement>('#d1-zusatz-fachgebiet')!;
    fachgebiet.value = 'Technik';
    fachgebiet.dispatchEvent(new Event('change'));
    const zugriff = element.querySelector<HTMLSelectElement>('#d1-zusatz-zugriff')!;
    zugriff.value = 'oeffentlich';
    zugriff.dispatchEvent(new Event('change'));

    await klicken(T.bestaetigen);
    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
    expect(patch.request.headers.get('If-Match')).toBe('"3"');
    expect(patch.request.body).toEqual({
      zusatz: {
        reihe: 'Bulletin',
        ausgabe: '12',
        jahr: 2026,
        fachgebiet: 'Technik',
        zugriff: 'oeffentlich',
        veroeffentlicht: false,
      },
    });
    patch.flush(dokument({ art: 'BULLETIN', version: 4, zusatz: patch.request.body.zusatz }));
    await fixture.whenStable();

    const bestaetigung = http.expectOne('/api/v1/dokumente/d1/bestaetigen');
    expect(bestaetigung.request.headers.get('If-Match')).toBe('"4"');
    expect(bestaetigung.request.body).not.toHaveProperty('zugriff');
    expect(bestaetigung.request.body).not.toHaveProperty('veroeffentlicht');
    expect(bestaetigung.request.body).not.toHaveProperty('betrag');
    expect(bestaetigung.request.body).not.toHaveProperty('waehrung');
    bestaetigung.flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(erledigt).toEqual([T.erfolgBestaetigt('Ohne Titel')]);
  });

  const zusatzAusfuellen = (jahr: string) => {
    const textfeld = (id: string, value: string) => {
      const input = element.querySelector<HTMLInputElement>(id)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    textfeld('#d1-zusatz-reihe', 'Bulletin');
    textfeld('#d1-zusatz-ausgabe', '12');
    textfeld('#d1-zusatz-jahr', jahr);
    const fachgebiet = element.querySelector<HTMLSelectElement>('#d1-zusatz-fachgebiet')!;
    fachgebiet.value = 'Technik';
    fachgebiet.dispatchEvent(new Event('change'));
  };

  it('sendet die gewählte Art mit, wenn sie nur im Vorschlag steht (Review #229, M1)', async () => {
    await zeigen(dokument({ vorschlag: { art: 'BULLETIN' } }), 'geschaeftsstelle', [VHF_ART]);
    await klicken(T.bestaetigen);
    zusatzAusfuellen('2026');
    await klicken(T.bestaetigen);
    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
    expect(patch.request.body.art).toBe('BULLETIN');
    expect(patch.request.body.zusatz.jahr).toBe(2026);
    patch.flush(dokument({ art: 'BULLETIN', version: 4, zusatz: patch.request.body.zusatz }));
    await fixture.whenStable();
    http.expectOne('/api/v1/dokumente/d1/bestaetigen');
  });

  it('verwirft ungültige Zahlen im Zusatzfeld, statt sie zu kürzen (Review #229, S2)', async () => {
    await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [VHF_ART]);
    await klicken(T.bestaetigen);
    zusatzAusfuellen('20x4');
    await klicken(T.bestaetigen);
    http.expectNone({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
    expect(element.textContent).toContain(T.zusatzUngueltig(feldName('zusatz.jahr')));
  });

  it('zeigt dem Mitarbeiter keine Aktion (Req 2.6, A6–A10)', async () => {
    await zeigen(MIT_VORSCHLAG, 'mitarbeiter');
    expect(element.querySelectorAll('button').length).toBe(0);
    expect(element.textContent).toContain(T.keineAktion);
  });

  it('fragt bei fehlender PDF/A-Fassung nach und sendet dann ohnePdfa (Req 5.9)', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG', pdfaFehlt: true }));
    expect(element.querySelector('[data-test=hinweis-pdfa]')).not.toBeNull();
    await klicken(T.bestaetigen);
    http.expectNone('/api/v1/dokumente/d1/bestaetigen');
    expect(element.querySelector('[data-test=rueckfrage]')?.textContent).toContain(
      T.rueckfragePdfa,
    );
    await klicken(T.rueckfragePdfaJa);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect(anfrage.body).toEqual(expect.objectContaining({ ohnePdfa: true }));
  });

  it('fragt bei abgelaufener Frist nach und bestätigt den Entscheid (Req 9.8)', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG' }));
    await klicken(T.bestaetigen);
    await antworten(
      '/api/v1/dokumente/d1/bestaetigen',
      { fehlercode: 'FRIST_ABGELAUFEN' },
      409,
      'Conflict',
    );
    expect(element.querySelector('[data-test=rueckfrage]')?.textContent).toContain(
      T.rueckfrageFrist,
    );
    await klicken(T.rueckfrageFristJa);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect(anfrage.body).toEqual(expect.objectContaining({ fristAbgelaufenAkzeptiert: true }));
  });

  it('öffnet bei fehlenden Pflichtfeldern das Formular und markiert sie (Req 8.3)', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG' }));
    await klicken(T.bestaetigen);
    await antworten(
      '/api/v1/dokumente/d1/bestaetigen',
      { fehlercode: 'PFLICHTFELD_FEHLT', felder: ['partner', 'qr_referenz'] },
      422,
      'Unprocessable Entity',
    );
    expect(element.querySelector('[data-test=formular]')).not.toBeNull();
    expect(element.querySelector('[data-test=fehler]')?.textContent).toContain(
      T.fehlerPflichtfelder(`${T.feld['partner']}, ${T.feld['qrReferenz']}`),
    );
    expect(element.querySelector('#d1-partner')?.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('#d1-qrReferenz')?.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('#d1-partner')?.getAttribute('aria-required')).toBe('true');
  });

  it('verlangt ohne Art zuerst die Wahl der Art', async () => {
    await zeigen(dokument());
    await klicken(T.bestaetigen);
    http.expectNone('/api/v1/dokumente/d1/bestaetigen');
    expect(element.querySelector('[data-test=fehler]')?.textContent).toContain(T.artWaehlen);
    expect(element.querySelector('#d1-art')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('meldet einen Versionskonflikt der Seite zum Neuladen', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG' }));
    await klicken(T.bestaetigen);
    await antworten(
      '/api/v1/dokumente/d1/bestaetigen',
      { fehlercode: 'VERSION_KONFLIKT' },
      412,
      'Precondition Failed',
    );
    expect(veraltet).toEqual([T.fehlercode['VERSION_KONFLIKT']]);
  });

  it('verwirft nur mit Grund nach Rückfrage (Req 8.5)', async () => {
    await zeigen(dokument());
    await klicken(T.verwerfen);
    expect(element.textContent).toContain(T.rueckfrageVerwerfen);
    await klicken(T.verwerfenJa);
    http.expectNone('/api/v1/dokumente/d1/verwerfen');
    expect(element.querySelector('[data-test=fehler]')?.textContent).toContain(T.grundFehlt);
    const grund = element.querySelector<HTMLTextAreaElement>('#d1-grund')!;
    grund.value = 'Doppelt erfasst';
    grund.dispatchEvent(new Event('input'));
    await klicken(T.verwerfenJa);
    const anfrage = await antworten('/api/v1/dokumente/d1/verwerfen', null, 204, 'No Content');
    expect(anfrage.body).toEqual({ grund: 'Doppelt erfasst' });
    expect(anfrage.headers.get('If-Match')).toBe('"3"');
    expect(erledigt.length).toBe(1);
  });

  it('zeigt bei FEHLER den Fehlertext, «erneut verarbeiten» und «verwerfen» (Req 8.7)', async () => {
    await zeigen(dokument({ status: 'FEHLER', fehler: 'OCR fehlgeschlagen' }));
    expect(element.querySelector('[data-test=fehlertext]')?.textContent).toContain(
      'OCR fehlgeschlagen',
    );
    expect(knoepfe()).toEqual([T.erneutVerarbeiten, T.verwerfen]);
    await klicken(T.erneutVerarbeiten);
    await antworten('/api/v1/dokumente/d1/wiederholen', null, 202, 'Accepted');
    expect(erledigt).toEqual([T.erfolgWiederholt(T.ohneTitel)]);
  });

  it('zeigt ohne Titel und Vorschlag den Dateinamen als Platzhalter und sendet ihn nie als Titel (Req 8.11)', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG', dateiname: 'Scan_Rechnung-2026.pdf' }));
    const titel = element.querySelector('h2')!;
    expect(titel.querySelector('bdi')!.textContent).toBe('Scan_Rechnung-2026.pdf');
    expect(titel.querySelector('.nur-screenreader')!.textContent).toBe(
      'Ohne Titel, Dateiname Scan_Rechnung-2026.pdf',
    );
    await klicken(T.aendern);
    expect(element.querySelector<HTMLInputElement>('#d1-titel')!.value).toBe('');
    await klicken(T.bestaetigen);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect((anfrage.body as { titel?: unknown }).titel ?? null).toBeNull();
    expect(JSON.stringify(anfrage.body)).not.toContain('Scan_Rechnung');
    expect(erledigt).toEqual([T.erfolgBestaetigt('Ohne Titel · \u2068Scan_Rechnung-2026.pdf\u2069')]);
  });

  it('nimmt den vorgeschlagenen Titel vor dem Dateinamen (Req 8.11)', async () => {
    await zeigen({ ...MIT_VORSCHLAG, dateiname: 'Scan.pdf' });
    expect(element.querySelector('h2')!.textContent).toContain('Rechnung Muster AG');
    expect(element.querySelector('[data-test=titel-platzhalter]')).toBeNull();
  });

  it('bietet bei ARCHIVIERUNG_FEHLER nur «Archivierung wiederholen» (Req 8.7)', async () => {
    await zeigen(dokument({ status: 'ARCHIVIERUNG_FEHLER', fehler: 'Ablage nicht erreichbar' }));
    expect(knoepfe()).toEqual([T.archivierungWiederholen]);
  });

  it('warnt bei bekannter Belegnummer mit Link zum vorhandenen Dokument (Req 8.10)', async () => {
    await zeigen(dokument({ warnungen: [{ code: 'BELEGNUMMER_BEKANNT', vorhandenId: 'd0' }] }));
    const warnung = element.querySelector('[data-test=warnung-belegnummer]')!;
    expect(warnung.textContent).toContain(T.warnungBelegnummer);
    expect(warnung.querySelector('a')?.getAttribute('href')).toBe('/dokument/d0');
  });

  it('lädt die Vorschau als Bild mit Alternativtext (Req 8.1)', async () => {
    const erstellen = vi.fn(() => 'blob:vorschau');
    const freigeben = vi.fn();
    Object.assign(URL, { createObjectURL: erstellen, revokeObjectURL: freigeben });
    await zeigen(dokument({ titel: 'Beleg', seiten: 2 }));
    await antworten('/api/v1/dokumente/d1/vorschau?seite=1', new Blob(['png']));
    const bild = element.querySelector<HTMLImageElement>('[data-test=vorschau]')!;
    expect(bild.getAttribute('src')).toBe('blob:vorschau');
    expect(bild.alt).toBe(T.vorschau('Beleg'));
    fixture.destroy();
    expect(freigeben).toHaveBeenCalledWith('blob:vorschau');
  });

  it('zeigt die Vertraulichkeit nur mit Recht A8 und sendet sie nur bei einer Wahl (Req 2.22)', async () => {
    await zeigen(dokument({ art: 'LIEFERANTENRECHNUNG' }));
    await klicken(T.aendern);
    const wahl = element.querySelector<HTMLSelectElement>('#d1-vertraulichkeit')!;
    wahl.value = 'INTERN';
    wahl.dispatchEvent(new Event('change'));
    await klicken(T.bestaetigen);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect(anfrage.body).toEqual(expect.objectContaining({ vertraulichkeit: 'INTERN' }));
  });
  it('behält gespeicherte Finanz- und Vertragsangaben beim Bestätigen (Review PR #68, M1)', async () => {
    await zeigen(
      dokument({
        art: 'LIEFERANTENRECHNUNG',
        finanz: {
          belegnummer: 'F-1',
          richtung: 'AUSGABE',
          betrag: 99.9,
          waehrung: 'EUR',
          faelligkeit: '2026-12-01',
          qrReferenz: 'QR-1',
          konto: '4000-TEST',
        },
        vertrag: {
          gueltigAb: '2026-01-01',
          gueltigBis: '2027-12-31',
          kuendigungsfrist: '3 Monate',
          naechsteFrist: '2027-09-30',
        },
        vorschlag: { betrag: 1234.5, waehrung: 'CHF', belegnummer: 'X-9' },
        unsicher: ['betrag'],
      }),
    );
    expect(element.textContent).toContain('EUR 99.90');
    expect(element.querySelectorAll('[data-test=bitte-pruefen]').length).toBe(0);
    await klicken(T.bestaetigen);
    const anfrage = await antworten('/api/v1/dokumente/d1/bestaetigen', null, 202, 'Accepted');
    expect(anfrage.body).toEqual(
      expect.objectContaining({
        belegnummer: 'F-1',
        richtung: 'AUSGABE',
        betrag: 99.9,
        waehrung: 'EUR',
        faelligkeit: '2026-12-01',
        qrReferenz: 'QR-1',
        konto: '4000-TEST',
        gueltigAb: '2026-01-01',
        gueltigBis: '2027-12-31',
        kuendigungsfrist: '3 Monate',
        naechsteFrist: '2027-09-30',
      }),
    );
  });

  it('nennt fehlende Pflicht-Zusatzfelder und verlinkt auf die Dokumentansicht (Review PR #68, S1)', async () => {
    await zeigen(dokument({ art: 'LOHNABRECHNUNG' }));
    const hinweis = element.querySelector('[data-test=hinweis-zusatz]');
    expect(hinweis?.textContent).toContain(T.hinweisZusatz(T.zusatzFeld('periode')));
    expect(hinweis?.textContent).toContain(T.zusatzErgaenzen);
    expect(hinweis?.querySelector('a')?.getAttribute('href')).toBe('/dokument/d1');
    await klicken(T.bestaetigen);
    await antworten(
      '/api/v1/dokumente/d1/bestaetigen',
      { fehlercode: 'PFLICHTFELD_FEHLT', felder: ['zusatz.periode'] },
      422,
      'Unprocessable Entity',
    );
    expect(element.querySelector('[data-test=fehler]')?.textContent).toContain(
      `${T.fehlerPflichtfelder(T.zusatzFeld('periode'))} ${T.zusatzBleibtImKorb}`,
    );
  });

  it('gibt eine Art, die nur im Vorschlag steht, als ?art= an die Dokumentansicht weiter (Review #106)', async () => {
    await zeigen(dokument({ vorschlag: { art: 'LOHNABRECHNUNG' } }));
    const hinweis = element.querySelector('[data-test=hinweis-zusatz]');
    expect(hinweis?.textContent).toContain(T.zusatzErgaenzenArt('Lohnabrechnung'));
    expect(hinweis?.querySelector('a')?.getAttribute('href')).toBe('/dokument/d1?art=LOHNABRECHNUNG');
  });

  it('gibt eine inaktive Vorschlags-Art nicht weiter und verlangt zuerst eine aktive Art (Review #107)', async () => {
    const arten = ARTEN.map((a) => (a.code === 'LOHNABRECHNUNG' ? { ...a, aktiv: false } : a));
    await zeigen(dokument({ vorschlag: { art: 'LOHNABRECHNUNG' } }), 'inhaber', arten);
    const hinweis = element.querySelector('[data-test=hinweis-zusatz]');
    expect(hinweis?.querySelector('[data-test=art-inaktiv]')?.textContent).toBe(T.zusatzArtInaktiv('Lohnabrechnung'));
    expect(hinweis?.textContent).not.toContain(T.zusatzErgaenzenArt('Lohnabrechnung'));
    expect(hinweis?.querySelector('a')?.getAttribute('href')).toBe('/dokument/d1');
  });

  it('zeigt keinen Zusatz-Hinweis, wenn das Zusatzfeld am Dokument steht', async () => {
    await zeigen(dokument({ art: 'LOHNABRECHNUNG', zusatz: { periode: '2026-10' } }));
    expect(element.querySelector('[data-test=hinweis-zusatz]')).toBeNull();
  });

  it('leert die vorgeschlagene Vertraulichkeit beim Wechsel der Art, ausser der Benutzer hat sie gewählt (Review PR #68, K3)', async () => {
    await zeigen(
      dokument({ vorschlag: { art: 'LIEFERANTENRECHNUNG', vertraulichkeit: 'INTERN' } }),
    );
    await klicken(T.aendern);
    const art = element.querySelector<HTMLSelectElement>('#d1-art')!;
    const vertraulichkeit = () => element.querySelector<HTMLSelectElement>('#d1-vertraulichkeit')!;
    const waehlen = async (select: HTMLSelectElement, wert: string) => {
      select.value = wert;
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();
    };
    expect(vertraulichkeit().value).toBe('INTERN');
    await waehlen(art, 'LOHNABRECHNUNG');
    expect(vertraulichkeit().value).toBe('');
    await waehlen(art, 'LIEFERANTENRECHNUNG');
    expect(vertraulichkeit().value).toBe('INTERN');
    await waehlen(vertraulichkeit(), 'NORMAL');
    await waehlen(art, 'LOHNABRECHNUNG');
    expect(vertraulichkeit().value).toBe('NORMAL');
  });

  describe('Partnersuche (Req 8, Folge Review #117)', () => {
    const MUELLER = [{ id: 'p2', name: 'Müller' }];
    const istKontaktAbruf = (r: { url: string }) => r.url === '/api/v1/kontakte';
    const trefferliste = () => element.querySelector('[data-test=partner-treffer]');

    async function oeffnen() {
      // Ohne `seiten`, damit kein Vorschau-Abruf läuft.
      await zeigen(dokument());
      await klicken(T.aendern);
    }

    function eingeben(wert: string) {
      const feld = element.querySelector<HTMLInputElement>('[data-test=partner-suche]')!;
      feld.value = wert;
      feld.dispatchEvent(new Event('input'));
    }

    function tippen(selektor: string) {
      element.querySelector<HTMLButtonElement>(selektor)!.click();
      fixture.detectChanges();
    }

    function abruf(q: string) {
      const anfrage = http.expectOne(istKontaktAbruf);
      // Der generierte Client kodiert den Parameter schon beim Setzen.
      expect(decodeURIComponent(anfrage.request.params.get('q') ?? '')).toBe(q);
      return anfrage;
    }

    it('zeigt nach Wählen und Entfernen keine Antwort eines noch laufenden Abrufs (wie Review #107)', async () => {
      await oeffnen();
      vi.useFakeTimers();
      try {
        eingeben('Mü');
        await vi.advanceTimersByTimeAsync(400);
        abruf('Mü').flush(MUELLER);
        fixture.detectChanges();
        expect(trefferliste()).not.toBeNull();
        eingeben('Mül');
        await vi.advanceTimersByTimeAsync(350);
        const offen = abruf('Mül');
        tippen('[data-test=partner-treffer] button');
        tippen('[data-test=partner-gewaehlt]');
        expect(offen.cancelled).toBe(true);
        expect(trefferliste()).toBeNull();
        await vi.advanceTimersByTimeAsync(1000);
        fixture.detectChanges();
        expect(trefferliste()).toBeNull();
        http.expectNone(istKontaktAbruf);
      } finally {
        vi.useRealTimers();
      }
    });

    it('schickt nach Wählen während der Wartezeit und Entfernen keinen Abruf (wie Review #106)', async () => {
      await oeffnen();
      vi.useFakeTimers();
      try {
        eingeben('Mü');
        await vi.advanceTimersByTimeAsync(400);
        abruf('Mü').flush(MUELLER);
        fixture.detectChanges();
        eingeben('Mül');
        tippen('[data-test=partner-treffer] button');
        tippen('[data-test=partner-gewaehlt]');
        await vi.advanceTimersByTimeAsync(400);
        fixture.detectChanges();
        http.expectNone(istKontaktAbruf);
        expect(trefferliste()).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });

    it('sucht nach dem Entfernen mit derselben Eingabe erneut', async () => {
      await oeffnen();
      vi.useFakeTimers();
      try {
        for (let i = 0; i < 2; i++) {
          eingeben('Mül');
          await vi.advanceTimersByTimeAsync(400);
          abruf('Mül').flush(MUELLER);
          fixture.detectChanges();
          expect(trefferliste()).not.toBeNull();
          tippen('[data-test=partner-treffer] button');
          tippen('[data-test=partner-gewaehlt]');
        }
      } finally {
        vi.useRealTimers();
      }
    });

    it('sucht unter 2 Zeichen nicht', async () => {
      await oeffnen();
      vi.useFakeTimers();
      try {
        eingeben('M');
        await vi.advanceTimersByTimeAsync(400);
        fixture.detectChanges();
        http.expectNone(istKontaktAbruf);
        expect(trefferliste()).toBeNull();
        expect(element.textContent).not.toContain(T.partnerKeine);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('Listenfelder (Arrays) im Zusatz-Schema', () => {
    const VERBAND_ART: Dokumentart = {
      ...VHF_ART,
      pflichtfelder: [...VHF_ART.pflichtfelder, 'zusatz.verband'],
      zusatzSchema: {
        type: 'object',
        properties: {
          ...(VHF_ART.zusatzSchema as { properties: object }).properties,
          verband: {
            type: 'array',
            items: { type: 'string', enum: ['vhf', 'gsk'] },
            'x-labels': { vhf: 'VHF', gsk: 'GSK' },
            minItems: 1,
            uniqueItems: true,
          },
          stichworte: { type: 'array', items: { type: 'string' } },
        },
      },
    };
    const feldAusfuellen = () => {
      const textfeld = (id: string, value: string) => {
        const input = element.querySelector<HTMLInputElement>(id)!;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      };
      textfeld('#d1-zusatz-reihe', 'Bulletin');
      textfeld('#d1-zusatz-ausgabe', '12');
      textfeld('#d1-zusatz-jahr', '2026');
      const fachgebiet = element.querySelector<HTMLSelectElement>('#d1-zusatz-fachgebiet')!;
      fachgebiet.value = 'Technik';
      fachgebiet.dispatchEvent(new Event('change'));
    };
    const checkbox = (wert: string) =>
      element.querySelector<HTMLInputElement>(`[data-test=listenfeld] input[type=checkbox][value=${wert}]`)!;
    async function umschalten(wert: string) {
      checkbox(wert).click();
      await fixture.whenStable();
    }

    it('zeigt ein Mehrfachfeld als Checkbox-Gruppe mit Beschriftung, Pflichtstern und Hinweis', async () => {
      await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.aendern);
      const gruppe = element.querySelector<HTMLFieldSetElement>('fieldset[data-test=listenfeld]')!;
      expect(gruppe.querySelector('legend')?.textContent).toContain('Verband');
      expect(gruppe.querySelector('legend')?.textContent).toContain('*');
      expect([...gruppe.querySelectorAll('label')].map((l) => l.textContent?.trim())).toEqual(['VHF', 'GSK']);
      expect(checkbox('vhf').checked).toBe(false);
      expect(checkbox('vhf').getAttribute('aria-required')).toBe('true');
      expect(gruppe.textContent).toContain('Mindestens einen Wert auswählen.');
    });

    it('zeigt die gespeicherten Werte als gewählt', async () => {
      await zeigen(dokument({ art: 'BULLETIN', zusatz: { verband: ['gsk'] } }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.aendern);
      expect(checkbox('vhf').checked).toBe(false);
      expect(checkbox('gsk').checked).toBe(true);
    });

    it('meldet ein leeres Pflicht-Listenfeld und sendet nichts', async () => {
      await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.bestaetigen);
      feldAusfuellen();
      await klicken(T.bestaetigen);
      http.expectNone({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
      expect(element.textContent).toContain(T.fehlerPflichtfelder(feldName('zusatz.verband')));
      expect(checkbox('vhf').getAttribute('aria-invalid')).toBe('true');
    });

    it('zeigt ohne x-labels den Rohwert; der Wert der Checkbox bleibt vhf', async () => {
      const ohne = {
        ...VERBAND_ART,
        zusatzSchema: {
          type: 'object',
          properties: {
            ...(VERBAND_ART.zusatzSchema as { properties: object }).properties,
            verband: { type: 'array', items: { type: 'string', enum: ['vhf', 'gsk'] }, minItems: 1 },
          },
        },
      };
      await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [ohne]);
      await klicken(T.aendern);
      const gruppe = element.querySelector<HTMLFieldSetElement>('fieldset[data-test=listenfeld]')!;
      expect([...gruppe.querySelectorAll('label')].map((l) => l.textContent?.trim())).toEqual(['vhf', 'gsk']);
    });

    it('sendet die gewählten Werte als Array an PATCH und bestätigt danach', async () => {
      await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.bestaetigen);
      feldAusfuellen();
      await umschalten('gsk');
      await umschalten('vhf');
      await klicken(T.bestaetigen);
      const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
      expect(patch.request.body.zusatz.verband).toEqual(['vhf', 'gsk']);
      patch.flush(dokument({ art: 'BULLETIN', version: 4, zusatz: patch.request.body.zusatz }));
      await fixture.whenStable();
      http.expectOne('/api/v1/dokumente/d1/bestaetigen').flush(null, { status: 202, statusText: 'Accepted' });
      await fixture.whenStable();
      expect(erledigt).toHaveLength(1);
    });

    it('lässt Auswahl wieder abwählen und meldet dann den Pflichtfehler', async () => {
      await zeigen(dokument({ art: 'BULLETIN', zusatz: { verband: ['vhf'] } }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.bestaetigen);
      feldAusfuellen();
      await umschalten('vhf');
      await klicken(T.bestaetigen);
      http.expectNone({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
      expect(element.textContent).toContain(T.fehlerPflichtfelder(feldName('zusatz.verband')));
    });

    it('bearbeitet eine Liste freier Texte mit Hinzufügen (Knopf und Enter) und Entfernen', async () => {
      await zeigen(dokument({ art: 'BULLETIN', zusatz: { verband: ['vhf'] } }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.aendern);
      const eingabe = element.querySelector<HTMLInputElement>('#d1-zusatz-stichworte')!;
      eingabe.value = 'Eis';
      eingabe.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
      await fixture.whenStable();
      eingabe.value = 'Bad';
      await klicken('Hinzufügen');
      const eintraege = () =>
        [...element.querySelectorAll('.eintraege li > span:first-child')].map((e) => e.textContent?.trim());
      expect(eintraege()).toEqual(['Eis', 'Bad']);
      await klicken('Entfernen');
      expect(eintraege()).toEqual(['Bad']);
    });

    it('übernimmt einen getippten, nicht bestätigten Eintrag beim Verlassen des Felds', async () => {
      await zeigen(dokument({ art: 'BULLETIN', zusatz: { verband: ['vhf'] } }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.aendern);
      const eingabe = element.querySelector<HTMLInputElement>('#d1-zusatz-stichworte')!;
      eingabe.value = 'Rechnung';
      eingabe.dispatchEvent(new Event('blur'));
      await fixture.whenStable();
      expect([...element.querySelectorAll('.eintraege li > span:first-child')].map((e) => e.textContent?.trim())).toEqual([
        'Rechnung',
      ]);
      expect(eingabe.value).toBe('');
    });

    it('zeigt einen Altwert ausserhalb des Schemas als gewählt und lässt ihn abwählen', async () => {
      await zeigen(dokument({ art: 'BULLETIN', zusatz: { verband: ['alt', 'vhf'] } }), 'geschaeftsstelle', [VERBAND_ART]);
      await klicken(T.aendern);
      expect(checkbox('alt').checked).toBe(true);
      await umschalten('gsk');
      expect(checkbox('alt').checked).toBe(true);
    });

    it('erkennt ein Pflichtfeld, das nur im Schema unter `required` steht (Seed vhf-gsk)', async () => {
      const nurSchema: Dokumentart = {
        ...VERBAND_ART,
        pflichtfelder: VHF_ART.pflichtfelder,
        zusatzSchema: { ...(VERBAND_ART.zusatzSchema as object), required: ['verband'] },
      };
      await zeigen(dokument({ art: 'BULLETIN' }), 'geschaeftsstelle', [nurSchema]);
      await klicken(T.aendern);
      expect(checkbox('vhf').getAttribute('aria-required')).toBe('true');
      await klicken(T.bestaetigen);
      feldAusfuellen();
      await klicken(T.bestaetigen);
      http.expectNone({ method: 'PATCH', url: '/api/v1/dokumente/d1' });
      expect(element.textContent).toContain(T.fehlerPflichtfelder(feldName('zusatz.verband')));
    });
  });
});
