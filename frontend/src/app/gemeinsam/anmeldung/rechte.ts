// Rechtematrix (Spec 01, Req 2.6) für die Oberfläche: Sie blendet nur aus, das Backend prüft jede Aktion selbst.

export type Rolle = 'inhaber' | 'geschaeftsstelle' | 'mitarbeiter' | 'treuhaender';

// Rangfolge nach Req 2.2: Bei mehreren Archiv-Rollen gilt nur die stärkste, die Rechte werden nicht vereinigt.
const RANGFOLGE: readonly Rolle[] = ['inhaber', 'geschaeftsstelle', 'mitarbeiter', 'treuhaender'];

// EXPORT: Jahresexport (Spec 02, Req 7.1, 7.5), keine Zeile der Rechtematrix, aber dieselbe Prüfung im Backend
export type Aktion = 'A1' | 'A2' | 'A5' | 'A6' | 'A7' | 'A8' | 'A9' | 'A10' | 'A11' | 'A12' | 'A13' | 'A14' | 'EXPORT';

// Rollen, für die die Aktion nicht 403 ist (A2: alle Rollen; Einschränkungen nach Vertraulichkeit macht das Backend).
const ERLAUBT: Record<Aktion, readonly Rolle[]> = {
  A1: ['inhaber', 'mitarbeiter', 'geschaeftsstelle'],
  A2: ['inhaber', 'mitarbeiter', 'treuhaender', 'geschaeftsstelle'],
  A5: ['inhaber', 'mitarbeiter', 'geschaeftsstelle'],
  A6: ['inhaber', 'geschaeftsstelle'],
  A7: ['inhaber', 'geschaeftsstelle'],
  A8: ['inhaber', 'geschaeftsstelle'],
  A9: ['inhaber', 'geschaeftsstelle'],
  A10: ['inhaber', 'geschaeftsstelle'],
  A11: ['inhaber'],
  A12: ['inhaber'],
  A13: ['inhaber'],
  A14: ['inhaber'],
  EXPORT: ['inhaber', 'treuhaender'],
};

export function rolleAusRealmRollen(rollen: readonly unknown[] | undefined): Rolle | null {
  if (!Array.isArray(rollen)) {
    return null;
  }
  return RANGFOLGE.find((rolle) => rollen.includes(rolle)) ?? null;
}

export function darf(rolle: Rolle | null, aktion: Aktion): boolean {
  return rolle !== null && ERLAUBT[aktion].includes(rolle);
}
