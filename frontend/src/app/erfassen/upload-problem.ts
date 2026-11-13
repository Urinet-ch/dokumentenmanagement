import { HttpErrorResponse } from '@angular/common/http';
import { UploadErgebnis, UploadFehler } from './upload-port';

export interface UploadProblem extends UploadFehler {
  /** Sind alle Dateien abgelehnt, trägt Problem Details die Ergebnisse je Datei (design.md «Upload»). */
  ergebnisse?: UploadErgebnis[];
}

/** Liest Status und Problem Details (RFC 9457) aus einem Fehler von POST /api/v1/dokumente; Status 0 ohne Antwort. */
export function uploadProblem(fehler: unknown): UploadProblem {
  const antwort = fehler instanceof HttpErrorResponse ? fehler : null;
  const inhalt: unknown = antwort?.error;
  const problem = typeof inhalt === 'object' && inhalt !== null ? (inhalt as Record<string, unknown>) : {};
  return {
    status: antwort?.status ?? 0,
    fehlercode: typeof problem['fehlercode'] === 'string' ? problem['fehlercode'] : undefined,
    detail: typeof problem['detail'] === 'string' ? problem['detail'] : undefined,
    ergebnisse: Array.isArray(problem['ergebnisse']) ? (problem['ergebnisse'] as UploadErgebnis[]) : undefined,
  };
}
