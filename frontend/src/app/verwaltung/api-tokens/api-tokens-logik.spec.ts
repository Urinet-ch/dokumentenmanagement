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

  it('sortiert nach zuletzt aufsteigend, auch bei Offsets und Sekundenbruchteilen', () => {
    const tokens: ApiToken[] = [
      { id: 'c', aktiv: true, zuletzt: '2026-11-15T10:00:00+01:00', erstellt: '2026-01-01T00:00:00Z' },
      { id: 'b', aktiv: true, zuletzt: '2026-03-01T10:00:00.123Z', erstellt: '2026-01-01T00:00:00Z' },
      { id: 'a', aktiv: true, zuletzt: '2026-03-01T10:00:00Z', erstellt: '2026-01-01T00:00:00Z' },
      { id: 'd', aktiv: true, zuletzt: '2026-11-15T09:30:00Z', erstellt: '2026-01-01T00:00:00Z' },
    ];
    expect(sortiereApiTokens(tokens).map((t) => t.id)).toEqual(['a', 'b', 'c', 'd']);
    expect(sortiereApiTokens([...tokens].reverse()).map((t) => t.id)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('sortiert ungenutzte nach erstellt und widerrufene untereinander wie aktive', () => {
    const tokens: ApiToken[] = [
      { id: 'w2', aktiv: false, zuletzt: '2026-05-01T00:00:00Z', erstellt: '2026-01-01T00:00:00Z' },
      { id: 'u2', aktiv: true, zuletzt: null, erstellt: '2026-02-01T00:00:00Z' },
      { id: 'w1', aktiv: false, zuletzt: '2026-04-01T00:00:00Z', erstellt: '2026-01-01T00:00:00Z' },
      { id: 'u1', aktiv: true, zuletzt: null, erstellt: '2026-01-01T00:00:00Z' },
      { id: 'w0', aktiv: false, zuletzt: null, erstellt: '2026-03-01T00:00:00Z' },
    ];
    expect(sortiereApiTokens(tokens).map((t) => t.id)).toEqual(['u1', 'u2', 'w0', 'w1', 'w2']);
  });

  it('liefert Status und unbekannte Scopes ohne Abbruch als Text', () => {
    expect(tokenStatus({ aktiv: true })).toBe('aktiv');
    expect(tokenStatus({ aktiv: false })).toBe('widerrufen');
    expect(scopeText('webseite-lesen')).toBe('webseite-lesen');
    expect(scopeText(undefined)).toBe('–');
  });
});
