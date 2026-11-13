import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../../../gemeinsam/api';
import { Eintrag } from './eintrag';
import { WarteschlangeUpload } from './warteschlange-upload';

describe('WarteschlangeUpload (Req 4.7, 4.9)', () => {
  let http: HttpTestingController;
  let upload: WarteschlangeUpload;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('')] });
    http = TestBed.inject(HttpTestingController);
    upload = TestBed.inject(WarteschlangeUpload);
  });

  afterEach(() => http.verify());

  const eintrag: Eintrag = {
    id: 'e1',
    // 08:15 in Zürich (Winterzeit), unabhängig vom Zeitpunkt des Uploads
    aufgenommen: '2026-11-13T07:15:30.000Z',
    seiten: [
      { original: new Blob(['roh1'], { type: 'image/jpeg' }), arbeitsfassung: new Blob(['ab1'], { type: 'image/jpeg' }) },
      { original: new Blob(['roh2'], { type: 'image/jpeg' }), arbeitsfassung: new Blob(['ab2'], { type: 'image/jpeg' }) },
    ],
    fehlversuche: 0,
    zustand: 'wartet',
  };

  it('sendet Rohaufnahmen als Originale und Zuschnitte als Arbeitsfassungen, als ein Dokument', async () => {
    const antwort = upload.senden(eintrag);
    const anfrage = http.expectOne({ method: 'POST', url: '/api/v1/dokumente' });
    const formular = anfrage.request.body as FormData;
    const originale = formular.getAll('dateien') as File[];
    const arbeitsfassungen = formular.getAll('arbeitsfassungen') as File[];
    expect(originale.map((d) => d.name)).toEqual(['Aufnahme 13.11.2026 08:15', 'Aufnahme 13.11.2026 08:15']);
    expect(await Promise.all(originale.map((d) => d.text()))).toEqual(['roh1', 'roh2']);
    expect(arbeitsfassungen.map((d) => d.name)).toEqual([
      'Aufnahme 13.11.2026 08:15 Seite 1.jpg',
      'Aufnahme 13.11.2026 08:15 Seite 2.jpg',
    ]);
    expect(await Promise.all(arbeitsfassungen.map((d) => d.text()))).toEqual(['ab1', 'ab2']);
    expect(formular.get('zusammenfassen')).toBe('true');
    anfrage.flush([{ datei: 'Aufnahme 13.11.2026 08:15', status: 'NEU', id: 'd1' }], { status: 202, statusText: 'Accepted' });
    expect(await antwort).toEqual({ art: 'angenommen', ergebnisse: [{ datei: 'Aufnahme 13.11.2026 08:15', status: 'NEU', id: 'd1' }] });
  });

  it('liefert Status und Meldung einer Ablehnung', async () => {
    const antwort = upload.senden(eintrag);
    http
      .expectOne('/api/v1/dokumente')
      .flush({ detail: 'Zu gross.', ergebnisse: [] }, { status: 413, statusText: 'Payload Too Large' });
    expect(await antwort).toEqual({ art: 'abgelehnt', status: 413, detail: 'Zu gross.', ergebnisse: [] });
  });

  it('meldet einen Netzwerkfehler als Status 0', async () => {
    const antwort = upload.senden(eintrag);
    http.expectOne('/api/v1/dokumente').error(new ProgressEvent('error'));
    expect(await antwort).toEqual({ art: 'abgelehnt', status: 0, detail: undefined, ergebnisse: undefined });
  });
});
