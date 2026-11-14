import { ApiToken } from '../../gemeinsam/api';
import { scopeText, sortiereApiTokens, tokenStatus } from './api-tokens-logik';

describe('API-Token-Logik', () => {
  it('sortiert aktive vor widerrufenen, ungenutzte zuerst und Gleichstände nach Erstellung', () => {
    const tokens: ApiToken[] = [
      { id: 'widerrufen', aktiv: false, zuletzt: null, erstellt: '2026-01-01T00:00:00Z' },
      { id: 'neu', aktiv: true, zuletzt: null, erstellt: '2026-02-01T00:00:00Z' },
      {
        id: 'frueh',
        aktiv: true,
        zuletzt: '2026-03-01T00:00:00Z',
        erstellt: '2026-01-01T00:00:00Z',
      },
      {
        id: 'spaet',
        aktiv: true,
        zuletzt: '2026-03-01T00:00:00Z',
        erstellt: '2026-02-01T00:00:00Z',
      },
    ];

    expect(sortiereApiTokens(tokens).map((token) => token.id)).toEqual([
      'neu',
      'frueh',
      'spaet',
      'widerrufen',
    ]);
    expect(tokens[0].id).toBe('widerrufen');
  });

  it('liefert Status und unbekannte Scopes ohne Abbruch als Text', () => {
    expect(tokenStatus({ aktiv: true })).toBe('aktiv');
    expect(tokenStatus({ aktiv: false })).toBe('widerrufen');
    expect(scopeText('webseite-lesen')).toBe('webseite-lesen');
    expect(scopeText(undefined)).toBe('–');
  });
});
