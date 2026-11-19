import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { Observable, of, throwError } from 'rxjs';
import { DokumentartenService } from '../../gemeinsam/api/api/dokumentarten.service';
import { KonfigurationService } from '../../gemeinsam/api/api/konfiguration.service';
import { Dokumentart } from '../../gemeinsam/api/model/dokumentart';
import { KonfigurationsVersion } from '../../gemeinsam/api/model/konfigurations-version';
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

function antwort<T>(body: T): HttpResponse<T> {
  return new HttpResponse({
    body,
    headers: new HttpHeaders({ ETag: '"konfig-7"' }),
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
  };

  async function starten(
    patchAntwort: Observable<HttpResponse<Dokumentart>> = of(antwort(ART)),
    versionen: KonfigurationsVersion[] = [],
  ) {
    dokumentartenService.apiV1DokumentartenGet.mockReset().mockReturnValue(of(antwort([ART])));
    dokumentartenService.apiV1DokumentartenPost.mockReset().mockReturnValue(of(antwort(ART)));
    dokumentartenService.apiV1DokumentartenCodePatch.mockReset().mockReturnValue(patchAntwort);
    dokumentartenService.apiV1DokumentartenCodeDelete
      .mockReset()
      .mockReturnValue(of(antwort(undefined)));
    konfigurationService.apiV1KlassifikationenGet.mockReset().mockReturnValue(of(antwort([])));
    konfigurationService.apiV1KonfigurationVersionenGet
      .mockReset()
      .mockReturnValue(of(antwort(versionen)));
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

  it('zeigt Dokumentarten, inaktive Stati, Klassifikationen und Versionen', async () => {
    await starten();

    expect(element.querySelector('h1')?.textContent).toContain(
      TEXTE.verwaltung.dokumentarten.titel,
    );
    expect(element.textContent).toContain(ART.name);
    expect(element.textContent).toContain(TEXTE.verwaltung.dokumentarten.liste);
    expect(element.textContent).toContain(TEXTE.verwaltung.klassifikationen.titel);
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
            error: { fehlercode: 'KONFIGURATION_IN_GEBRAUCH', detail: 'private value' },
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
