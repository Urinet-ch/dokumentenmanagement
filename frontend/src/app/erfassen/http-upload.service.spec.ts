import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpUploadService, UPLOAD_PFAD } from './http-upload.service';
import { UploadEreignis } from './upload-port';

describe('HttpUploadService (Req 3.1, 3.7)', () => {
  let dienst: HttpUploadService;
  let http: HttpTestingController;
  const a = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
  const b = new File(['b'], 'b.jpg', { type: 'image/jpeg' });

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), HttpUploadService] });
    dienst = TestBed.inject(HttpUploadService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sendet alle Dateien in einem Multipart-Request in der Reihenfolge der Liste', () => {
    const ereignisse: UploadEreignis[] = [];
    dienst.hochladen([b, a], true).subscribe((e) => ereignisse.push(e));
    const anfrage = http.expectOne(UPLOAD_PFAD);
    expect(anfrage.request.method).toBe('POST');
    const formular = anfrage.request.body as FormData;
    expect(formular.getAll('dateien').map((d) => (d as File).name)).toEqual(['b.jpg', 'a.jpg']);
    expect(formular.get('zusammenfassen')).toBe('true');

    anfrage.event({ type: HttpEventType.UploadProgress, loaded: 1, total: 4 });
    anfrage.flush([{ datei: 'b.jpg', status: 'NEU', id: '1' }]);
    expect(ereignisse).toEqual([
      { typ: 'fortschritt', prozent: 25 },
      { typ: 'fertig', ergebnisse: [{ datei: 'b.jpg', status: 'NEU', id: '1' }] },
    ]);
  });

  it('sendet «zusammenfassen» nur, wenn gewählt', () => {
    dienst.hochladen([a], false).subscribe();
    const anfrage = http.expectOne(UPLOAD_PFAD);
    expect((anfrage.request.body as FormData).has('zusammenfassen')).toBe(false);
    anfrage.flush([]);
  });

  it('liefert die Ergebnisse je Datei auch, wenn alle abgelehnt sind', () => {
    const ereignisse: UploadEreignis[] = [];
    dienst.hochladen([a], false).subscribe((e) => ereignisse.push(e));
    const ergebnisse = [
      { datei: 'a.jpg', status: 'ABGELEHNT', fehler: { status: 415, fehlercode: 'DATEITYP_UNBEKANNT' } },
    ];
    http
      .expectOne(UPLOAD_PFAD)
      .flush({ status: 415, fehlercode: 'DATEITYP_UNBEKANNT', ergebnisse }, { status: 415, statusText: 'x' });
    expect(ereignisse).toEqual([{ typ: 'fertig', ergebnisse }]);
  });

  it('liefert Problem Details ohne Ergebnisse als Gesamtfehler', () => {
    const ereignisse: UploadEreignis[] = [];
    dienst.hochladen([a], false).subscribe((e) => ereignisse.push(e));
    http
      .expectOne(UPLOAD_PFAD)
      .flush({ status: 413, fehlercode: 'DATEI_ZU_GROSS', detail: 'Zu gross.' }, { status: 413, statusText: 'x' });
    expect(ereignisse).toEqual([
      { typ: 'fehler', fehler: { status: 413, fehlercode: 'DATEI_ZU_GROSS', detail: 'Zu gross.' } },
    ]);
  });
});
