import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { Observable, of, throwError } from 'rxjs';
import { DokumentartenService } from '../../gemeinsam/api/api/dokumentarten.service';
import { KonfigurationService } from '../../gemeinsam/api/api/konfiguration.service';
import { Dokumentart } from '../../gemeinsam/api/model/dokumentart';
import { KonfigurationsVersion } from '../../gemeinsam/api/model/konfigurations-version';
import { Regel } from '../../gemeinsam/api/model/regel';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { Verwaltung } from './verwaltung';

const ART: Dokumentart = {
  code: 'RECHNUNG',
  name: 'Rechnung',
  beschreibung: '',
  gruppen: ['finanz'],
  pflichtfelder: ['belegdatum'],
  zusatzSchema: { type: 'object', properties: {}, additionalProperties: false },
  vertraulichkeit: 'NORMAL',
  aufbewahrungJahre: 10,
  aufbewahrungAb: 'GESCHAEFTSJAHR',
  gesetzlichePflicht: true,
  aktiv: true,
  selbstAusgestellt: false,
  belegnummerMuster: [],
  kiVorschlag: true,
  kiFelder: [],
  reihenfolge: 1,
};

const REGEL: Regel = {
  code: 'probe_regel',
  art: 'RECHNUNG',
  typ: 'EINDEUTIG',
  parameter: { felder: ['titel'] },
  schwere: 'WARNUNG',
  meldung: 'Titel muss eindeutig sein.',
  aktiv: true,
};

function antwort<T>(body: T, etag = '"konfig-7"'): HttpResponse<T> {
  return new HttpResponse({
    body,
    headers: new HttpHeaders({ ETag: etag }),
  });
}

describe('Verwaltung der Dokumentarten', () => {
  let fixture: ComponentFixture<Verwaltung>;
  let element: HTMLElement;

  const dokumentartenService = {
    apiV1DokumentartenGet: vi.fn(),
    apiV1DokumentartenPost: vi.fn(),
    apiV1DokumentartenCodePatch: vi.fn(),
    apiV1DokumentartenCodeDelete: vi.fn(),
  };
  const konfigurationService = {
    apiV1KlassifikationenGet: vi.fn(),
    apiV1KonfigurationVersionenGet: vi.fn(),
    apiV1KlassifikationenPost: vi.fn(),
    apiV1KlassifikationenCodePatch: vi.fn(),
    apiV1KlassifikationenCodeDelete: vi.fn(),
    apiV1KlassifikationenCodeWertePost: vi.fn(),
    apiV1KlassifikationenCodeWerteWertPatch: vi.fn(),
    apiV1KlassifikationenCodeWerteWertDelete: vi.fn(),
    apiV1DokumentartenArtKlassifikationenPost: vi.fn(),
    apiV1DokumentartenArtKlassifikationenCodePatch: vi.fn(),
    apiV1DokumentartenArtKlassifikationenCodeDelete: vi.fn(),
    apiV1KonfigurationVersionenNummerGet: vi.fn(),
    apiV1DokumentartenArtKlassifikationenGet: vi.fn(),
    apiV1KonfigurationPruefenPost: vi.fn(),
    apiV1KonfigurationVergleichenPost: vi.fn(),
    apiV1KonfigurationPut: vi.fn(),
    apiV1KonfigurationGet: vi.fn(),
    apiV1RegelnGet: vi.fn(),
    apiV1RegelnPost: vi.fn(),
    apiV1RegelnCodePatch: vi.fn(),
  };

  async function starten(
    patchAntwort: Observable<HttpResponse<Dokumentart>> = of(antwort(ART)),
    versionen: KonfigurationsVersion[] = [],
    arten: Dokumentart[] = [ART],
    regelnEtag = '"konfig-7"',
  ) {
    dokumentartenService.apiV1DokumentartenGet.mockReset().mockReturnValue(of(antwort(arten)));
    dokumentartenService.apiV1DokumentartenPost.mockReset().mockReturnValue(of(antwort(ART)));
    dokumentartenService.apiV1DokumentartenCodePatch.mockReset().mockReturnValue(patchAntwort);
    dokumentartenService.apiV1DokumentartenCodeDelete
      .mockReset()
      .mockReturnValue(of(antwort(undefined)));
    konfigurationService.apiV1KlassifikationenGet.mockReset().mockReturnValue(of(antwort([])));
    konfigurationService.apiV1KonfigurationVersionenGet
      .mockReset()
      .mockReturnValue(of(antwort(versionen)));
    konfigurationService.apiV1RegelnGet
      .mockReset()
      .mockReturnValue(of(antwort([REGEL], regelnEtag)));
    konfigurationService.apiV1RegelnPost
      .mockReset()
      .mockReturnValue(of(antwort({ ...REGEL, betroffeneDokumente: 0 })));
    konfigurationService.apiV1RegelnCodePatch
      .mockReset()
      .mockReturnValue(of(antwort({ ...REGEL, betroffeneDokumente: 0 })));
    konfigurationService.apiV1DokumentartenArtKlassifikationenGet
      .mockReset()
      .mockReturnValue(of(antwort([])));

    TestBed.configureTestingModule({
      imports: [Verwaltung],
      providers: [
        { provide: DokumentartenService, useValue: dokumentartenService },
        { provide: KonfigurationService, useValue: konfigurationService },
      ],
    });
    fixture = TestBed.createComponent(Verwaltung);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function knopf(text: string): HTMLButtonElement {
    return [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (eintrag) => eintrag.textContent?.trim() === text,
    )!;
  }

  function eingabe(labelText: string): HTMLInputElement {
    const label = [...element.querySelectorAll<HTMLLabelElement>('label')].find((eintrag) =>
      eintrag.textContent?.includes(labelText),
    );
    return label?.querySelector('input')!;
  }

  function textbereich(labelText: string): HTMLTextAreaElement {
    const label = [...element.querySelectorAll<HTMLLabelElement>('label')].find((eintrag) =>
      eintrag.textContent?.includes(labelText),
    );
    return label?.querySelector('textarea')!;
  }

  function auswahl(labelText: string): HTMLSelectElement {
    const label = [...element.querySelectorAll<HTMLLabelElement>('label')].find((eintrag) =>
      eintrag.textContent?.includes(labelText),
    );
    return label?.querySelector('select')!;
  }

  it('zeigt Dokumentarten, inaktive Stati, Klassifikationen und Versionen', async () => {
    await starten();

    expect(element.querySelector('h1')?.textContent).toContain(
      TEXTE.verwaltung.dokumentarten.titel,
    );
    expect(element.textContent).toContain(ART.name);
    expect(element.textContent).toContain(TEXTE.verwaltung.dokumentarten.liste);
    expect(element.textContent).toContain(TEXTE.verwaltung.klassifikationen.titel);
    expect(element.textContent).toContain(REGEL.code);
    expect(element.textContent).toContain(TEXTE.verwaltung.regeln.titel);
    expect(element.textContent).toContain(TEXTE.verwaltung.versionen.titel);
    expect(element.textContent).toContain(TEXTE.verwaltung.datei.titel);
  });

  it('besteht die axe-Prüfung für die Verwaltungsseite', async () => {
    await starten();

    const ergebnis = await axe.run(element, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    });

    expect(ergebnis.violations).toEqual([]);
  });

  it('sendet den aktuellen If-Match-Wert und erhält Formulareingaben bei 412', async () => {
    await starten(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 412,
            error: { detail: 'CH0000000000000000000' },
          }),
      ),
    );

    element.querySelector<HTMLButtonElement>('.auswahl')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    const name = eingabe(TEXTE.verwaltung.dokumentarten.name);
    name.value = 'Geänderte Rechnung';
    name.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.speichern).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.speichern).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(dokumentartenService.apiV1DokumentartenCodePatch).toHaveBeenCalledExactlyOnceWith(
      ART.code,
      '"konfig-7"',
      expect.objectContaining({ name: 'Geänderte Rechnung' }),
      'response',
    );
    expect(eingabe(TEXTE.verwaltung.dokumentarten.name).value).toBe('Geänderte Rechnung');
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerKonflikt,
    );
    expect(element.textContent).not.toContain('CH0000000000000000000');
  });

  it('meldet 409 ohne Serverdetails und empfiehlt Deaktivieren statt Löschen', async () => {
    await starten();
    dokumentartenService.apiV1DokumentartenCodeDelete.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { fehlercode: 'DOKUMENTART_IN_GEBRAUCH', detail: 'private value' },
          }),
      ),
    );

    element.querySelector<HTMLButtonElement>('.auswahl')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.loeschen).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.loeschen).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerGebrauch,
    );
    expect(element.textContent).not.toContain('private value');
  });

  async function ersteArtSpeichern(): Promise<void> {
    element.querySelector<HTMLButtonElement>('.auswahl')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    const name = eingabe(TEXTE.verwaltung.dokumentarten.name);
    name.value = 'Geänderter Name';
    name.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  async function speichernBestaetigen(): Promise<void> {
    knopf(TEXTE.verwaltung.speichern).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.speichern).click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('behält im JSON-Modus die Pflichtfelder zusatz.* und das Schema beim Speichern', async () => {
    const lohn: Dokumentart = {
      ...ART,
      code: 'LOHNABRECHNUNG',
      pflichtfelder: ['titel', 'zusatz.periode', 'zusatz.person'],
      zusatzSchema: {
        type: 'object',
        properties: {
          periode: { type: 'string', pattern: '^\\d{4}-\\d{2}$' },
          person: { type: 'string', minLength: 1 },
        },
        additionalProperties: false,
      },
    };
    await starten(of(antwort(lohn)), [], [lohn]);

    await ersteArtSpeichern();
    await speichernBestaetigen();

    const body = dokumentartenService.apiV1DokumentartenCodePatch.mock.calls[0][2];
    expect(body.pflichtfelder).toEqual(['titel', 'zusatz.periode', 'zusatz.person']);
    expect(body.zusatzSchema).toEqual(lohn.zusatzSchema);
  });

  it('behält im Feld-Modus Pflichtfelder zusatz.* ohne neues required im Schema', async () => {
    const bank: Dokumentart = {
      ...ART,
      code: 'BANK',
      pflichtfelder: ['titel', 'zusatz.periode_von', 'zusatz.periode_bis'],
      zusatzSchema: {
        type: 'object',
        properties: {
          periode_von: { type: 'string', format: 'date' },
          periode_bis: { type: 'string', format: 'date' },
        },
        additionalProperties: false,
      },
    };
    await starten(of(antwort(bank)), [], [bank]);

    await ersteArtSpeichern();
    await speichernBestaetigen();

    const body = dokumentartenService.apiV1DokumentartenCodePatch.mock.calls[0][2];
    expect(body.pflichtfelder).toEqual(bank.pflichtfelder);
    expect(body.zusatzSchema).toEqual(bank.zusatzSchema);
  });

  it('speichert eine über das Zahlenfeld geänderte Aufbewahrungsdauer', async () => {
    await starten();

    await ersteArtSpeichern();
    const jahre = eingabe(TEXTE.verwaltung.dokumentarten.aufbewahrungJahre);
    jahre.value = '11';
    jahre.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    await speichernBestaetigen();

    expect(dokumentartenService.apiV1DokumentartenCodePatch).toHaveBeenCalledExactlyOnceWith(
      ART.code,
      '"konfig-7"',
      expect.objectContaining({ aufbewahrungJahre: 11 }),
      'response',
    );
  });

  it('übernimmt keinen neueren ETag aus den Zuordnungen und speichert mit dem Stand der Liste', async () => {
    await starten();
    konfigurationService.apiV1DokumentartenArtKlassifikationenGet.mockReturnValue(
      of(antwort([], '"konfig-9"')),
    );

    await ersteArtSpeichern();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerKonflikt,
    );
    await speichernBestaetigen();

    expect(dokumentartenService.apiV1DokumentartenCodePatch.mock.calls[0][1]).toBe('"konfig-7"');
  });

  it('speichert nicht, solange die Listen verschiedene Stände haben', async () => {
    await starten(of(antwort(ART)), [], [ART], '"konfig-8"');
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerKonflikt,
    );

    await ersteArtSpeichern();
    await speichernBestaetigen();

    expect(dokumentartenService.apiV1DokumentartenCodePatch).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerLaden,
    );
  });

  it('lädt die Versionsliste nach dem Speichern einer Art neu', async () => {
    await starten(of(antwort(ART, '"konfig-8"')));
    await ersteArtSpeichern();
    konfigurationService.apiV1KonfigurationVersionenGet.mockReturnValue(
      of(antwort([], '"konfig-8"')),
    );
    konfigurationService.apiV1DokumentartenArtKlassifikationenGet.mockReturnValue(
      of(antwort([], '"konfig-8"')),
    );
    await speichernBestaetigen();

    expect(konfigurationService.apiV1KonfigurationVersionenGet).toHaveBeenCalledTimes(2);
    expect(element.querySelector('[role=alert]')).toBeNull();
  });

  it('legt Regeln mit If-Match an und zeigt die Zahl betroffener Dokumente', async () => {
    await starten();
    konfigurationService.apiV1RegelnPost.mockReturnValue(
      of(antwort({ ...REGEL, code: 'probe_neu', betroffeneDokumente: 2 })),
    );

    knopf(TEXTE.verwaltung.regeln.neueRegel).click();
    fixture.detectChanges();
    const code = eingabe(TEXTE.verwaltung.regeln.code);
    code.value = 'probe_neu';
    code.dispatchEvent(new Event('input', { bubbles: true }));
    const art = auswahl(TEXTE.verwaltung.regeln.art);
    art.value = 'RECHNUNG';
    art.dispatchEvent(new Event('change', { bubbles: true }));
    const typ = auswahl(TEXTE.verwaltung.regeln.typ);
    typ.value = 'EINDEUTIG';
    typ.dispatchEvent(new Event('change', { bubbles: true }));
    const parameter = textbereich(TEXTE.verwaltung.regeln.parameter);
    parameter.value = '{"felder":["titel"]}';
    parameter.dispatchEvent(new Event('input', { bubbles: true }));
    const meldung = textbereich(TEXTE.verwaltung.regeln.meldung);
    meldung.value = 'Titel muss eindeutig sein.';
    meldung.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    knopf(TEXTE.verwaltung.speichern).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(konfigurationService.apiV1RegelnPost).toHaveBeenCalledExactlyOnceWith(
      '"konfig-7"',
      expect.objectContaining({
        code: 'probe_neu',
        art: 'RECHNUNG',
        typ: 'EINDEUTIG',
        parameter: { felder: ['titel'] },
        meldung: 'Titel muss eindeutig sein.',
      }),
      'response',
    );
    expect(element.textContent).toContain(TEXTE.verwaltung.regeln.gespeichert);
    expect(element.textContent).toContain(TEXTE.verwaltung.regeln.betroffene(2));
  });

  it('weist ungültige Regelparameter zurück, ohne sie an die API zu senden', async () => {
    await starten();

    knopf(TEXTE.verwaltung.regeln.neueRegel).click();
    fixture.detectChanges();
    const code = eingabe(TEXTE.verwaltung.regeln.code);
    code.value = 'probe_neu';
    code.dispatchEvent(new Event('input', { bubbles: true }));
    const meldung = textbereich(TEXTE.verwaltung.regeln.meldung);
    meldung.value = 'Titel muss eindeutig sein.';
    meldung.dispatchEvent(new Event('input', { bubbles: true }));
    const parameter = textbereich(TEXTE.verwaltung.regeln.parameter);
    parameter.value = '[';
    parameter.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    knopf(TEXTE.verwaltung.speichern).click();
    fixture.detectChanges();

    expect(konfigurationService.apiV1RegelnPost).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.regeln.parameterUngueltig,
    );
  });

  it('behält Regeländerungen bei einem Versionskonflikt im Formular', async () => {
    await starten();
    konfigurationService.apiV1RegelnCodePatch.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 412,
            error: { detail: 'private value' },
          }),
      ),
    );

    element.querySelector<HTMLButtonElement>('#regeln .auswahl')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const meldung = textbereich(TEXTE.verwaltung.regeln.meldung);
    meldung.value = 'Geänderte Meldung';
    meldung.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.speichern).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(konfigurationService.apiV1RegelnCodePatch).toHaveBeenCalledExactlyOnceWith(
      REGEL.code,
      '"konfig-7"',
      expect.objectContaining({ meldung: 'Geänderte Meldung' }),
      'response',
    );
    expect(textbereich(TEXTE.verwaltung.regeln.meldung).value).toBe('Geänderte Meldung');
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.fehlerKonflikt,
    );
    expect(element.textContent).not.toContain('private value');
  });

  it('vergleicht zwei Konfigurationsversionen und zeigt keine Kennungen', async () => {
    const versionen: KonfigurationsVersion[] = [
      {
        version: 6,
        zeit: '2026-11-18T08:00:00Z',
        wer: 'Inhaber',
        quelle: 'APP',
        sha256: 'a'.repeat(64),
      },
      {
        version: 7,
        zeit: '2026-11-19T08:00:00Z',
        wer: 'Inhaber',
        quelle: 'APP',
        sha256: 'b'.repeat(64),
      },
    ];
    await starten(of(antwort(ART)), versionen);
    konfigurationService.apiV1KonfigurationVersionenNummerGet.mockImplementation((nummer: number) =>
      of(
        antwort({
          inhalt: {
            kennungen: { iban: nummer === 6 ? 'synthetic-old' : 'synthetic-new' },
            geschaeftsjahrEnde: nummer === 6 ? '12-31' : '06-30',
          },
        }),
      ),
    );

    knopf(TEXTE.verwaltung.versionen.vergleichen).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(konfigurationService.apiV1KonfigurationVersionenNummerGet).toHaveBeenNthCalledWith(
      1,
      6,
      'response',
    );
    expect(konfigurationService.apiV1KonfigurationVersionenNummerGet).toHaveBeenNthCalledWith(
      2,
      7,
      'response',
    );
    expect(element.textContent).toContain(TEXTE.verwaltung.versionen.werteVerdeckt);
    expect(element.textContent).not.toContain('synthetic-old');
    expect(element.textContent).not.toContain('synthetic-new');
  });

  it('spielt eine Datei erst nach Prüfung und Vergleich ein und verdeckt Kennungen in der Vorschau', async () => {
    await starten();
    const inhalt = JSON.stringify({ basisVersion: 7 });
    konfigurationService.apiV1KonfigurationPruefenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationVergleichenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [
            {
              pfad: '/kennungen/iban',
              alt: 'sensitive-old',
              neu: 'sensitive-new',
              betroffeneDokumente: 3,
            },
          ],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationPut.mockReturnValue(of(antwort({})));

    const dateiFeld = element.querySelector<HTMLInputElement>('input[type=file]')!;
    const datei = new File([inhalt], 'private-dateiname.json', { type: 'application/json' });
    Object.defineProperty(dateiFeld, 'files', { value: [datei] });
    dateiFeld.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.textContent).not.toContain(datei.name);
    knopf(TEXTE.verwaltung.datei.pruefen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(konfigurationService.apiV1KonfigurationPut).not.toHaveBeenCalled();

    knopf(TEXTE.verwaltung.datei.vergleichen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.verwaltung.versionen.werteVerdeckt);
    expect(element.textContent).not.toContain('sensitive-old');
    expect(element.textContent).not.toContain('sensitive-new');
    expect(element.textContent).toContain('3');

    const grund = eingabe(TEXTE.verwaltung.datei.grund);
    grund.value = 'Geprüfte Anpassung';
    grund.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielen).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielenBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(konfigurationService.apiV1KonfigurationPut).toHaveBeenCalledExactlyOnceWith(
      inhalt,
      'Geprüfte Anpassung',
      'response',
    );
    expect(element.textContent).toContain(TEXTE.verwaltung.datei.einspielenErfolgreich);
  });

  it('lädt nach dem Einspielen Listen und offenes Formular neu und speichert mit dem neuen Stand', async () => {
    await starten();
    konfigurationService.apiV1KonfigurationPruefenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationVergleichenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationPut.mockReturnValue(of(antwort({}, '"konfig-8"')));
    await ersteArtSpeichern();

    const neu = { ...ART, name: 'Rechnung aus Datei' };
    dokumentartenService.apiV1DokumentartenGet.mockReturnValue(of(antwort([neu], '"konfig-8"')));
    konfigurationService.apiV1KlassifikationenGet.mockReturnValue(of(antwort([], '"konfig-8"')));
    konfigurationService.apiV1RegelnGet.mockReturnValue(of(antwort([REGEL], '"konfig-8"')));
    konfigurationService.apiV1KonfigurationVersionenGet.mockReturnValue(
      of(antwort([], '"konfig-8"')),
    );
    konfigurationService.apiV1DokumentartenArtKlassifikationenGet.mockReturnValue(
      of(antwort([], '"konfig-8"')),
    );
    const dateiFeld = element.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(dateiFeld, 'files', {
      value: [new File(['{"basisVersion":7}'], 'k.json', { type: 'application/json' })],
    });
    dateiFeld.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.pruefen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.vergleichen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    const grund = element.querySelector<HTMLInputElement>(
      '#konfigurationsdatei label input:not([type=file])',
    )!;
    grund.value = 'Geprüfte Anpassung';
    grund.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielen).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielenBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(dokumentartenService.apiV1DokumentartenGet).toHaveBeenCalledTimes(2);
    expect(eingabe(TEXTE.verwaltung.dokumentarten.name).value).toBe('Rechnung aus Datei');
    await speichernBestaetigen();
    expect(dokumentartenService.apiV1DokumentartenCodePatch).toHaveBeenCalledExactlyOnceWith(
      ART.code,
      '"konfig-8"',
      expect.objectContaining({ name: 'Rechnung aus Datei' }),
      'response',
    );
  });

  it('spielt keinen Grund mit Zeichen ausserhalb von Latin-1 ein', async () => {
    await starten();
    konfigurationService.apiV1KonfigurationPruefenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationVergleichenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationPut.mockReset();
    const dateiFeld = element.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(dateiFeld, 'files', {
      value: [new File(['{"basisVersion":7}'], 'k.json', { type: 'application/json' })],
    });
    dateiFeld.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.pruefen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.vergleichen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    const grund = element.querySelector<HTMLInputElement>(
      '#konfigurationsdatei label input:not([type=file])',
    )!;
    grund.value = 'Anpassung – Jahresabschluss';
    grund.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielen).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielenBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(konfigurationService.apiV1KonfigurationPut).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.datei.grundZeichen,
    );
  });

  it('verwirft nach 409 KONFIGURATION_VERALTET die Vorschau, behält die Datei und verlangt einen neuen Vergleich', async () => {
    await starten();
    const inhalt = JSON.stringify({ basisVersion: 7 });
    konfigurationService.apiV1KonfigurationPruefenPost.mockReturnValue(
      of(antwort({ basisVersion: 7, gueltig: true, befunde: [], aenderungen: [] })),
    );
    konfigurationService.apiV1KonfigurationVergleichenPost.mockReturnValue(
      of(
        antwort({
          basisVersion: 7,
          aktuelleVersion: 7,
          gueltig: true,
          befunde: [],
          aenderungen: [],
        }),
      ),
    );
    konfigurationService.apiV1KonfigurationPut.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { fehlercode: 'KONFIGURATION_VERALTET', detail: 'private value' },
          }),
      ),
    );

    const dateiFeld = element.querySelector<HTMLInputElement>('input[type=file]')!;
    const datei = new File([inhalt], 'private-dateiname.json', { type: 'application/json' });
    Object.defineProperty(dateiFeld, 'files', { value: [datei] });
    dateiFeld.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.pruefen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.vergleichen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    const grund = eingabe(TEXTE.verwaltung.datei.grund);
    grund.value = 'Geprüfte Anpassung';
    grund.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielen).click();
    fixture.detectChanges();
    knopf(TEXTE.verwaltung.datei.einspielenBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.verwaltung.datei.fehlerVeraltet,
    );
    expect(element.textContent).not.toContain('private value');
    expect(element.textContent).toContain(TEXTE.verwaltung.datei.dateiBereit);
    expect(element.textContent).not.toContain(TEXTE.verwaltung.datei.einspielen);
    expect(konfigurationService.apiV1KonfigurationPut).toHaveBeenCalledOnce();
  });
});
