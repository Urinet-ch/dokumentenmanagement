// Reine Geometrie der Handy-Erfassung (Req 4.3): Ecken prüfen und die Grösse der Arbeitsfassung bestimmen.

export interface Punkt {
  x: number;
  y: number;
}

/** Ecken des Blatts in Pixeln des Originals. */
export interface Ecken {
  obenLinks: Punkt;
  obenRechts: Punkt;
  untenRechts: Punkt;
  untenLinks: Punkt;
}

/** Lange Kante der Arbeitsfassung (design.md «Frontend», ≈ 300 dpi für A4). */
export const LANGE_KANTE = 2480;

/** Ein erkanntes Blatt muss mindestens diesen Anteil des Bilds bedecken, sonst gilt «keine Kanten». */
export const MINDESTANTEIL = 0.2;

const abstand = (a: Punkt, b: Punkt) => Math.hypot(a.x - b.x, a.y - b.y);

export function reihe(ecken: Ecken): Punkt[] {
  return [ecken.obenLinks, ecken.obenRechts, ecken.untenRechts, ecken.untenLinks];
}

/** Fläche des Vierecks (Gauss'sche Trapezformel). */
export function flaeche(ecken: Ecken): number {
  const p = reihe(ecken);
  let summe = 0;
  for (let i = 0; i < p.length; i++) {
    const n = p[(i + 1) % p.length];
    summe += p[i].x * n.y - n.x * p[i].y;
  }
  return Math.abs(summe) / 2;
}

/** Konvex und im Uhrzeigersinn (Bildkoordinaten, y nach unten), also ein gültiges Blatt ohne gekreuzte Kanten. */
export function konvex(ecken: Ecken): boolean {
  const p = reihe(ecken);
  return p.every((a, i) => {
    const b = p[(i + 1) % 4];
    const c = p[(i + 2) % 4];
    return (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0;
  });
}

/**
 * Prüft das Ergebnis der Kantenerkennung: alle vier Ecken vorhanden, im Bild, konvex und gross genug. Sonst `null`,
 * und die Arbeitsfassung ist das ganze Bild (Req 4.3).
 */
export function pruefen(ecken: Partial<Ecken> | null | undefined, breite: number, hoehe: number): Ecken | null {
  if (!ecken?.obenLinks || !ecken.obenRechts || !ecken.untenRechts || !ecken.untenLinks) {
    return null;
  }
  const vollstaendig = ecken as Ecken;
  const imBild = reihe(vollstaendig).every((p) => p.x >= 0 && p.y >= 0 && p.x <= breite && p.y <= hoehe);
  if (!imBild || !konvex(vollstaendig) || flaeche(vollstaendig) < MINDESTANTEIL * breite * hoehe) {
    return null;
  }
  return vollstaendig;
}

export function skalieren(ecken: Ecken, faktor: number): Ecken {
  const s = (p: Punkt) => ({ x: p.x * faktor, y: p.y * faktor });
  return {
    obenLinks: s(ecken.obenLinks),
    obenRechts: s(ecken.obenRechts),
    untenRechts: s(ecken.untenRechts),
    untenLinks: s(ecken.untenLinks),
  };
}

/** Ecken des ganzen Bilds (Startwert beim Anpassen, wenn keine Kanten erkannt wurden). */
export function ganzesBild(breite: number, hoehe: number): Ecken {
  return {
    obenLinks: { x: 0, y: 0 },
    obenRechts: { x: breite, y: 0 },
    untenRechts: { x: breite, y: hoehe },
    untenLinks: { x: 0, y: hoehe },
  };
}

/**
 * Grösse der Arbeitsfassung: Seitenverhältnis aus den längeren gegenüberliegenden Kanten (ohne Ecken das ganze Bild),
 * die lange Kante auf `langeKante` gebracht.
 */
export function zielGroesse(
  ecken: Ecken | null,
  breite: number,
  hoehe: number,
  langeKante = LANGE_KANTE,
): { breite: number; hoehe: number } {
  const b = ecken ? Math.max(abstand(ecken.obenLinks, ecken.obenRechts), abstand(ecken.untenLinks, ecken.untenRechts)) : breite;
  const h = ecken ? Math.max(abstand(ecken.obenLinks, ecken.untenLinks), abstand(ecken.obenRechts, ecken.untenRechts)) : hoehe;
  const faktor = langeKante / Math.max(b, h);
  return { breite: Math.max(1, Math.round(b * faktor)), hoehe: Math.max(1, Math.round(h * faktor)) };
}
