import { ApiToken } from '../../gemeinsam/api';

const ZEITZONE = 'Europe/Zurich';
const JAHR_MS = 365 * 24 * 60 * 60 * 1000;
const DATUMSFORMAT = new Intl.DateTimeFormat('sv-SE', {
  timeZone: ZEITZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** Zeitpunkt in ms; fehlend oder ungültig sortiert als frühester Wert. */
function zeit(wert: string | null | undefined): number {
  const ms = wert ? Date.parse(wert) : NaN;
  return Number.isNaN(ms) ? Number.NEGATIVE_INFINITY : ms;
}

export function heuteInZurich(): string {
  return DATUMSFORMAT.format(Date.now());
}

export function spaetestesAblaufdatum(heute = heuteInZurich()): string {
  const [jahr, monat, tag] = heute.split('-').map(Number);
  const neuesJahr = jahr + 5;
  const letzterTag = new Date(Date.UTC(neuesJahr, monat, 0)).getUTCDate();
  return `${neuesJahr}-${String(monat).padStart(2, '0')}-${String(Math.min(tag, letzterTag)).padStart(2, '0')}`;
}

export function ablaufdatumGueltig(datum: string, heute = heuteInZurich()): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(datum)) {
    return false;
  }
  const geprueft = new Date(`${datum}T00:00:00Z`);
  return (
    !Number.isNaN(geprueft.getTime()) &&
    geprueft.toISOString().slice(0, 10) === datum &&
    datum >= heute &&
    datum <= spaetestesAblaufdatum(heute)
  );
}

/** Ende des ausgewählten Tages, angezeigt als einschliessliches Datum in Europe/Zurich. */
export function ablaufdatumText(token: ApiToken): string | null {
  const zeitpunkt = zeit(token.gueltigBis);
  if (!Number.isFinite(zeitpunkt)) {
    return null;
  }
  const ende = token.scope === 'generator' || token.scope === 'import' ? zeitpunkt - 1 : zeitpunkt;
  return DATUMSFORMAT.format(ende);
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

export function tokenStatus(token: ApiToken, jetzt = Date.now()): 'aktiv' | 'abgelaufen' | 'widerrufen' {
  if (token.aktiv !== true) {
    return 'widerrufen';
  }
  const ablauf = zeit(token.gueltigBis);
  return Number.isFinite(ablauf) && ablauf <= jetzt ? 'abgelaufen' : 'aktiv';
}

export function langeUngenutzt(token: ApiToken, jetzt = Date.now()): boolean {
  const letzterZugriff = zeit(token.zuletzt ?? token.erstellt);
  return token.aktiv === true && !token.gueltigBis && Number.isFinite(letzterZugriff)
    && jetzt - letzterZugriff >= JAHR_MS;
}

export function scopeText(scope: string | undefined): string {
  return scope || '–';
}
