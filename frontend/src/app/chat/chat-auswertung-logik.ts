import { TEXTE } from '../gemeinsam/texte.de-CH';

export function datumEingabe(datum: Date): string {
  const jahr = datum.getFullYear();
  const monat = String(datum.getMonth() + 1).padStart(2, '0');
  const tag = String(datum.getDate()).padStart(2, '0');
  return `${jahr}-${monat}-${tag}`;
}

function datumGueltig(wert: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(wert)) return false;
  const datum = new Date(`${wert}T12:00:00`);
  return !Number.isNaN(datum.getTime()) && datumEingabe(datum) === wert;
}

export function datumVorZwoelfMonaten(wert: string): string {
  const datum = new Date(`${wert}T12:00:00`);
  const tag = datum.getDate();
  datum.setDate(1);
  datum.setFullYear(datum.getFullYear() - 1);
  const monatsende = new Date(datum);
  monatsende.setMonth(monatsende.getMonth() + 1);
  monatsende.setDate(0);
  datum.setDate(Math.min(tag, monatsende.getDate()));
  return datumEingabe(datum);
}

export function zeitraumFehler(von: string, bis: string, heute = new Date()): string | null {
  if (!von || !bis) return TEXTE.chat.auswertung.zeitraumFehlt;
  if (!datumGueltig(von) || !datumGueltig(bis) || bis < von) {
    return TEXTE.chat.auswertung.zeitraumUngueltig;
  }
  if (bis > datumEingabe(heute)) return TEXTE.chat.auswertung.zukunft;

  return von < datumVorZwoelfMonaten(bis) ? TEXTE.chat.auswertung.zeitraumZuLang : null;
}
