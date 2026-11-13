import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { Dokument, DokumentAenderung, Dokumentart, Kontakt } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentPort } from './dokument-port';
import { MetadatenFormular } from './metadaten-formular';

const T = TEXTE.dokument.bearbeiten;

const ARTEN: Dokumentart[] = [
  {
    code: 'BEHOERDE',
    name: 'Behördenschreiben',
    gruppen: [],
    pflichtfelder: ['titel', 'datum'],
    zusatzSchema: { type: 'object', properties: { aktenzeichen: { type: 'string' }, frist: { type: 'string', format: 'date' } } },
    vertraulichkeit: 'NORMAL',
    gesetzlichePflicht: false,
    aktiv: true,
  },
  {
    code: 'QUITTUNG',
    name: 'Quittung',
    gruppen: ['finanz'],
    pflichtfelder: ['titel', 'datum', 'betrag'],
    zusatzSchema: {},
    vertraulichkeit: 'INTERN',
    gesetzlichePflicht: true,
    aktiv: true,
  },
];

const DOKUMENT: Dokument = {
  id: 'd1',
  art: 'BEHOERDE',
  titel: 'Brief Gemeinde',
  datum: '2026-11-08',
  eingang: '2026-11-08T08:00:00Z',
  quelle: 'UPLOAD',
  tags: [],
  vertraulichkeit: 'NORMAL',
  status: 'ARCHIVIERT',
  fristAbgelaufen: false,
  geschaeftsjahr: 2026,
  zusatz: { aktenzeichen: 'AZ-1' },
  version: 4,
  warnungen: [],
  pdfaFehlt: false,
  unsicher: [],
  versionen: [],
};

class Attrappe extends DokumentPort {
  antwort: Observable<Dokument> = of(DOKUMENT);
  aufrufe: { id: string; version: number; aenderung: DokumentAenderung }[] = [];
  lesen = () => of(DOKUMENT);
  dokumentarten = () => of(ARTEN);
  adresse = () => throwError(() => new Error('nicht erwartet'));
  vorschau = () => throwError(() => new Error('nicht erwartet'));
  kontakte = (): Observable<Kontakt[]> => of([]);
  aendern(id: string, version: number, aenderung: DokumentAenderung): Observable<Dokument> {
    this.aufrufe.push({ id, version, aenderung });
    return this.antwort;
  }
}

describe('MetadatenFormular (Req 10.1)', () => {
  let fixture: ComponentFixture<MetadatenFormular>;
  let port: Attrappe;
  let element: HTMLElement;
  let rolle: Rolle;

  async function oeffnen(dokument: Dokument = DOKUMENT) {
    TestBed.configureTestingModule({
      imports: [MetadatenFormular],
      providers: [
        { provide: DokumentPort, useValue: port },
        { provide: AnmeldungService, useValue: { darf: (a: Parameters<typeof darf>[1]) => darf(rolle, a) } },
      ],
    });
    fixture = TestBed.createComponent(MetadatenFormular);
    fixture.componentRef.setInput('dokument', dokument);
    fixture.componentRef.setInput('arten', ARTEN);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  beforeEach(() => {
    port = new Attrappe();
    rolle = 'inhaber';
  });

  function eingeben(selektor: string, wert: string) {
    const feld = element.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(selektor)!;
    feld.value = wert;
    feld.dispatchEvent(new Event(feld instanceof HTMLSelectElement ? 'change' : 'input'));
  }

  async function speichern() {
    element.querySelector<HTMLButtonElement>('[data-test=speichern]')!.click();
    await fixture.whenStable();
  }

  const fehler = () => element.querySelector('[data-test=formular-fehler]')?.textContent?.trim();

  it('zeigt Zusatzfelder aus dem Schema und ohne Finanzgruppe keine Finanzangaben', async () => {
    await oeffnen();
    expect(element.querySelector<HTMLInputElement>('#bearbeiten-zusatz-aktenzeichen')!.value).toBe('AZ-1');
    expect(element.querySelector<HTMLInputElement>('#bearbeiten-zusatz-frist')!.type).toBe('date');
    expect(element.querySelector('[data-test=finanz]')).toBeNull();
    expect(element.querySelector('#bearbeiten-grund')!.getAttribute('aria-required')).toBe('true');
  });

  it('zeigt nach einem Artwechsel die Finanzangaben der neuen Art', async () => {
    await oeffnen();
    eingeben('#bearbeiten-art', 'QUITTUNG');
    await fixture.whenStable();
    expect(element.querySelector('[data-test=finanz]')).not.toBeNull();
    expect(element.querySelector('#bearbeiten-zusatz-aktenzeichen')).toBeNull();
  });

  it('sendet ohne Grund nichts und markiert das Feld', async () => {
    await oeffnen();
    eingeben('#bearbeiten-titel', 'Brief Gemeinde Seedorf');
    await speichern();
    expect(port.aufrufe).toEqual([]);
    expect(fehler()).toBe(T.grundFehlt);
    expect(element.querySelector('#bearbeiten-grund')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('speichert die Änderung mit Grund und der geladenen Version', async () => {
    await oeffnen();
    const gespeichert: Dokument[] = [];
    fixture.componentInstance.gespeichert.subscribe((d) => gespeichert.push(d));
    eingeben('#bearbeiten-titel', 'Brief Gemeinde Seedorf');
    eingeben('#bearbeiten-zusatz-frist', '2026-12-01');
    eingeben('#bearbeiten-grund', 'Frist ergänzt');
    await speichern();
    expect(port.aufrufe).toEqual([
      {
        id: 'd1',
        version: 4,
        aenderung: {
          titel: 'Brief Gemeinde Seedorf',
          zusatz: { aktenzeichen: 'AZ-1', frist: '2026-12-01' },
          grund: 'Frist ergänzt',
        },
      },
    ]);
    expect(gespeichert).toEqual([DOKUMENT]);
  });

  it('zeigt die Vertraulichkeit nur mit A8', async () => {
    rolle = 'mitarbeiter';
    await oeffnen();
    expect(element.querySelector('#bearbeiten-vertraulichkeit')).toBeNull();
    TestBed.resetTestingModule();
    rolle = 'inhaber';
    await oeffnen();
    expect(element.querySelector('#bearbeiten-vertraulichkeit')).not.toBeNull();
  });

  it('markiert fehlende Pflichtfelder aus der Antwort des Archivs', async () => {
    port.antwort = throwError(
      () => new HttpErrorResponse({ status: 422, error: { fehlercode: 'PFLICHTFELD_FEHLT', felder: ['datum', 'zusatz.frist'] } }),
    );
    await oeffnen();
    eingeben('#bearbeiten-datum', '');
    eingeben('#bearbeiten-grund', 'Datum unklar');
    await speichern();
    expect(fehler()).toBe(T.fehlerPflichtfelder(`Belegdatum, ${TEXTE.eingangskorb.zusatzFeld('frist')}`));
    expect(element.querySelector('#bearbeiten-datum')!.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('#bearbeiten-zusatz-frist')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('meldet einen Konflikt (412) an die Ansicht, damit sie neu lädt', async () => {
    port.antwort = throwError(
      () => new HttpErrorResponse({ status: 412, error: { fehlercode: 'VERSION_KONFLIKT' } }),
    );
    await oeffnen();
    const veraltet: string[] = [];
    fixture.componentInstance.veraltet.subscribe((m) => veraltet.push(m));
    eingeben('#bearbeiten-titel', 'Neu');
    eingeben('#bearbeiten-grund', 'Korrektur');
    await speichern();
    expect(veraltet).toEqual([T.fehlercode['VERSION_KONFLIKT']]);
  });

  it('meldet 403 ohne Recht verständlich', async () => {
    port.antwort = throwError(() => new HttpErrorResponse({ status: 403, error: { fehlercode: 'ZUGRIFF_VERWEIGERT' } }));
    await oeffnen();
    eingeben('#bearbeiten-titel', 'Neu');
    eingeben('#bearbeiten-grund', 'Korrektur');
    await speichern();
    expect(fehler()).toBe(T.fehlercode['NICHT_ERLAUBT']);
  });
});
