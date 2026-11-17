import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { UploadErgebnis } from './upload-port';
import { uploadProblem } from './upload-problem';

describe('uploadProblem', () => {
  it('liest Problem Details mit Ergebnissen je Datei', () => {
    const ergebnisse: UploadErgebnis[] = [{ datei: 'beleg.pdf', status: 'FEHLER' }];

    expect(
      uploadProblem(
        new HttpErrorResponse({
          status: 422,
          error: { fehlercode: 'DATEI_UNGUELTIG', detail: 'Bitte erneut hochladen', ergebnisse },
        }),
      ),
    ).toEqual({
      status: 422,
      fehlercode: 'DATEI_UNGUELTIG',
      detail: 'Bitte erneut hochladen',
      ergebnisse,
    });
  });

  it('ignoriert Text- und Null-Inhalte', () => {
    expect(
      uploadProblem(new HttpErrorResponse({ status: 502, error: 'temporär nicht erreichbar' })),
    ).toEqual({
      status: 502,
      fehlercode: undefined,
      detail: undefined,
      ergebnisse: undefined,
    });
    expect(uploadProblem(new HttpErrorResponse({ status: 400, error: null }))).toEqual({
      status: 400,
      fehlercode: undefined,
      detail: undefined,
      ergebnisse: undefined,
    });
  });

  it('liefert für einen Fehler ohne HTTP-Antwort Status 0', () => {
    expect(uploadProblem(new Error('Netzwerkfehler'))).toEqual({
      status: 0,
      fehlercode: undefined,
      detail: undefined,
      ergebnisse: undefined,
    });
  });

  it('liest Retry-After aus einer 429-Antwort', () => {
    expect(
      uploadProblem(
        new HttpErrorResponse({
          status: 429,
          headers: new HttpHeaders({ 'Retry-After': '42' }),
        }),
      ),
    ).toMatchObject({ status: 429, retryAfter: 42 });
  });

  it('ignoriert einen fehlercode, der keine Zeichenkette ist', () => {
    expect(
      uploadProblem(
        new HttpErrorResponse({
          status: 400,
          error: { fehlercode: 400, detail: 'ungültige Datei', ergebnisse: [] },
        }),
      ),
    ).toEqual({
      status: 400,
      fehlercode: undefined,
      detail: 'ungültige Datei',
      ergebnisse: [],
    });
  });

  it('ignoriert Ergebnisse, die kein Array sind', () => {
    expect(
      uploadProblem(
        new HttpErrorResponse({
          status: 400,
          error: { fehlercode: 'DATEI_UNGUELTIG', detail: 'ungültige Datei', ergebnisse: {} },
        }),
      ),
    ).toEqual({
      status: 400,
      fehlercode: 'DATEI_UNGUELTIG',
      detail: 'ungültige Datei',
      ergebnisse: undefined,
    });
  });
});
