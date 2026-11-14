import { ApiToken } from '../../gemeinsam/api';

export function sortiereApiTokens(tokens: readonly ApiToken[]): ApiToken[] {
  return [...tokens].sort((a, b) => {
    const status = Number(b.aktiv === true) - Number(a.aktiv === true);
    if (status !== 0) {
      return status;
    }
    const zuletzt = (a.zuletzt ?? '').localeCompare(b.zuletzt ?? '');
    return zuletzt || (a.erstellt ?? '').localeCompare(b.erstellt ?? '');
  });
}

export function tokenStatus(token: ApiToken): 'aktiv' | 'widerrufen' {
  return token.aktiv === true ? 'aktiv' : 'widerrufen';
}

export function scopeText(scope: string | undefined): string {
  return scope || '–';
}
