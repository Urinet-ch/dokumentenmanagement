const RETRY_AFTER_MAX = 3600;

/** `Retry-After` in Sekunden oder als HTTP-Datum, begrenzt auf 0–3'600; `null` ohne lesbaren Wert. */
export function retryAfterSekunden(wert: string | null, jetzt = Date.now()): number | null {
  if (wert === null || wert.trim() === '') {
    return null;
  }
  const text = wert.trim();
  // Alles ausser reinen Ziffern muss ein HTTP-Datum sein (mit Wochentag, Monatsname); sonst liest Date.parse Zahlen als Jahr
  if (!/^\d+$/.test(text) && !/[A-Za-z]{3}/.test(text)) {
    return null;
  }
  const sekunden = /^\d+$/.test(text) ? Number(text) : Math.ceil((Date.parse(text) - jetzt) / 1000);
  return Number.isNaN(sekunden) ? null : Math.min(RETRY_AFTER_MAX, Math.max(0, sekunden));
}
