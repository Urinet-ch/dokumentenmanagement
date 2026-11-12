// Anzeigeformate nach product.md «Sprache und Ton»: Beträge `CHF 1'234.50`, Datum `08.11.2026`, Zeit in Europe/Zurich.

const ZEIT = new Intl.DateTimeFormat('de-CH', {
  timeZone: 'Europe/Zurich',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** Belegdatum (`YYYY-MM-DD`, LocalDate) ohne Umweg über eine Zeitzone. */
export function datum(wert: string | null | undefined): string {
  const teile = /^(\d{4})-(\d{2})-(\d{2})$/.exec(wert ?? '');
  return teile ? `${teile[3]}.${teile[2]}.${teile[1]}` : '';
}

/** Zeitpunkt (UTC, ISO 8601) in Europe/Zurich. */
export function zeitpunkt(wert: string | null | undefined): string {
  if (!wert) {
    return '';
  }
  const zeit = new Date(wert);
  return Number.isNaN(zeit.getTime()) ? '' : ZEIT.format(zeit).replace(',', '');
}

/**
 * Betrag mit Apostroph als Tausendertrennzeichen. Gerechnet wird nicht (tech.md, Regel 9): Die Zahl aus der API wird nur
 * auf zwei Stellen dargestellt.
 */
export function betrag(wert: number | null | undefined, waehrung?: string | null): string {
  if (wert === null || wert === undefined || !Number.isFinite(wert)) {
    return '';
  }
  const [ganz, rest] = Math.abs(wert).toFixed(2).split('.');
  const gruppiert = ganz.replace(/\B(?=(\d{3})+(?!\d))/g, "'");
  return `${waehrung || 'CHF'} ${wert < 0 ? '-' : ''}${gruppiert}.${rest}`;
}
