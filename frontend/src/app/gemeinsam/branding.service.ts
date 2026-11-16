import { HttpClient } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { inject, Injectable, signal } from '@angular/core';
import { catchError, map, of } from 'rxjs';

export interface Branding {
  name: string;
  logoUrl: string | null;
  primaerfarbe: string;
  sprache: string;
}

export const STANDARD_BRANDING: Branding = {
  name: 'Archiv',
  logoUrl: null,
  primaerfarbe: '#eeb111',
  sprache: 'de-CH',
};

const TEXT_DUNKEL = '#05070c';
const TEXT_HELL = '#f2efe7';
/** WCAG 2.1 AA für normalen Text. */
const MIN_KONTRAST = 4.5;
const FARBE = /^#[\da-f]{6}$/i;
const NAME = /^[\p{L}\p{N}][\p{L}\p{N} &'’().,/-]{0,79}$/u;
const LOGO_PFAD = /(?:\.png|\.jpe?g|\.webp)$/i;
const LOGO_DATEN = /^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/;

function logo(wert: unknown): string | null {
  if (typeof wert !== 'string' || wert.length > 140_000) {
    return null;
  }
  if (LOGO_DATEN.test(wert)) {
    return wert;
  }
  try {
    // Nur Pfade auf der eigenen Origin: keine Anfragen an Drittserver.
    const url = new URL(wert, 'https://archiv.invalid');
    const eigenerPfad = wert.startsWith('/') && !wert.startsWith('//') && !url.search && !url.hash;
    return eigenerPfad && LOGO_PFAD.test(url.pathname) ? wert : null;
  } catch (error) {
    if (error instanceof TypeError) {
      return null;
    }
    throw error;
  }
}

function sicher(antwort: Partial<Branding> | null): Branding {
  if (!antwort || typeof antwort.name !== 'string' || !NAME.test(antwort.name)) {
    return STANDARD_BRANDING;
  }
  const gewuenscht =
    typeof antwort.primaerfarbe === 'string' && FARBE.test(antwort.primaerfarbe)
      ? antwort.primaerfarbe.toLowerCase()
      : STANDARD_BRANDING.primaerfarbe;
  const primaerfarbe =
    Math.max(kontrast(gewuenscht, TEXT_DUNKEL), kontrast(gewuenscht, TEXT_HELL)) >= MIN_KONTRAST
      ? gewuenscht
      : STANDARD_BRANDING.primaerfarbe;
  const sprache = antwort.sprache === 'de-CH' ? antwort.sprache : STANDARD_BRANDING.sprache;
  const logoUrl = logo(antwort.logoUrl);
  return { name: antwort.name, logoUrl, primaerfarbe, sprache };
}

function luminanz(farbe: string): number {
  const kanal = (start: number) => {
    const wert = Number.parseInt(farbe.slice(start, start + 2), 16) / 255;
    return wert <= 0.04045 ? wert / 12.92 : ((wert + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * kanal(1) + 0.7152 * kanal(3) + 0.0722 * kanal(5);
}

function kontrast(farbeA: string, farbeB: string): number {
  const a = luminanz(farbeA);
  const b = luminanz(farbeB);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

export function textfarbe(farbe: string): string {
  return kontrast(farbe, TEXT_DUNKEL) >= kontrast(farbe, TEXT_HELL) ? TEXT_DUNKEL : TEXT_HELL;
}

@Injectable({ providedIn: 'root' })
export class BrandingService {
  readonly branding = signal(STANDARD_BRANDING);

  private readonly http = inject(HttpClient);
  private readonly dokument = inject(DOCUMENT);

  constructor() {
    const domain = this.dokument.defaultView?.location.hostname ?? '';
    this.http
      .get<Partial<Branding>>('/api/v1/branding', { params: { domain } })
      .pipe(
        map(sicher),
        catchError(() => of(STANDARD_BRANDING)),
      )
      .subscribe((branding) => {
        this.branding.set(branding);
        this.dokument.documentElement.style.setProperty('--brand-primary', branding.primaerfarbe);
        this.dokument.documentElement.style.setProperty('--brand-text', textfarbe(branding.primaerfarbe));
        this.dokument.documentElement.lang = branding.sprache;
      });
  }
}
