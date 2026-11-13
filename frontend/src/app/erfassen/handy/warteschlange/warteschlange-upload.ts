import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DokumenteService } from '../../../gemeinsam/api';
import { aufnahmeName } from '../../../gemeinsam/aufnahme-name';
import { UploadErgebnis } from '../../upload-port';
import { Ausgang, Eintrag } from './eintrag';

/** Sendet einen Eintrag der Warteschlange als ein Dokument an POST /api/v1/dokumente (design.md «Upload», Req 4.7). */
@Injectable({ providedIn: 'root' })
export class WarteschlangeUpload {
  private readonly dokumente = inject(DokumenteService);

  async senden(eintrag: Eintrag): Promise<Ausgang> {
    // Req 4.9: Name aus der ersten Seite, nicht aus dem Zeitpunkt des Uploads
    const name = aufnahmeName(new Date(eintrag.aufgenommen));
    const originale = eintrag.seiten.map((s) => new File([s.original], name, { type: s.original.type }));
    const arbeitsfassungen = eintrag.seiten.map(
      (s, i) => new File([s.arbeitsfassung], `${name} Seite ${i + 1}.jpg`, { type: 'image/jpeg' }),
    );
    try {
      const ergebnisse = await firstValueFrom(this.dokumente.apiV1DokumentePost(originale, arbeitsfassungen, true));
      return { art: 'angenommen', ergebnisse: ergebnisse ?? [] };
    } catch (fehler) {
      const antwort = fehler instanceof HttpErrorResponse ? fehler : null;
      const problem = typeof antwort?.error === 'object' && antwort.error !== null ? (antwort.error as Record<string, unknown>) : {};
      return {
        art: 'abgelehnt',
        status: antwort?.status ?? 0,
        detail: typeof problem['detail'] === 'string' ? problem['detail'] : undefined,
        ergebnisse: Array.isArray(problem['ergebnisse']) ? (problem['ergebnisse'] as UploadErgebnis[]) : undefined,
      };
    }
  }
}
