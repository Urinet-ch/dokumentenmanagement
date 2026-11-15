import { ApiToken } from '../../gemeinsam/api';

/** Zeitpunkt in ms; fehlend oder ungültig sortiert als frühester Wert. */
function zeit(wert: string | null | undefined): number {
  const ms = wert ? Date.parse(wert) : NaN;
  return Number.isNaN(ms) ? Number.NEGATIVE_INFINITY : ms;
}

export function sortiereApiTokens(tokens: readonly ApiToken[]): ApiToken[] {
  return [...tokens].sort((a, b) => {
    const status = Number(b.aktiv === true) - Number(a.aktiv === true);
    if (status !== 0) {
      return status;
    }
    return zeit(a.zuletzt) - zeit(b.zuletzt) || zeit(a.erstellt) - zeit(b.erstellt);
  });
}

export function tokenStatus(token: ApiToken): 'aktiv' | 'widerrufen' {
  return token.aktiv === true ? 'aktiv' : 'widerrufen';
}

export function scopeText(scope: string | undefined): string {
  return scope || '–';
}
