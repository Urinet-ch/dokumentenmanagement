// Dateiname der Originale einer Handy-Erfassung (Req 4.9; design.md «Dateiname», Handy-Erfassung).
const AUFNAHME = new Intl.DateTimeFormat('de-CH', {
  timeZone: 'Europe/Zurich',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/**
 * «Aufnahme TT.MM.JJJJ HH:MM» in Europe/Zurich, unabhängig von der Zeitzone des Geräts. `formatToParts`, weil `format`
 * ein Komma zwischen Datum und Zeit setzt. Massgeblich ist der Zeitpunkt der ersten Seite (`aufgenommen` im Eintrag der
 * Warteschlange), nicht der Upload.
 */
export function aufnahmeName(aufgenommen: Date): string {
  const teil = Object.fromEntries(AUFNAHME.formatToParts(aufgenommen).map((t) => [t.type, t.value]));
  return `Aufnahme ${teil['day']}.${teil['month']}.${teil['year']} ${teil['hour']}:${teil['minute']}`;
}
