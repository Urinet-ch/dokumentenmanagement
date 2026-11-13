import { darf, rolleAusRealmRollen } from './rechte';

describe('Rechte (Req 2.2, 2.6)', () => {
  it('nimmt bei mehreren Archiv-Rollen nur die stärkste', () => {
    expect(rolleAusRealmRollen(['treuhaender', 'mitarbeiter'])).toBe('mitarbeiter');
    expect(rolleAusRealmRollen(['mitarbeiter', 'inhaber', 'offline_access'])).toBe('inhaber');
    expect(rolleAusRealmRollen(['offline_access'])).toBeNull();
    expect(rolleAusRealmRollen(undefined)).toBeNull();
  });

  it('erlaubt nur, was die Rechtematrix erlaubt', () => {
    expect(darf('treuhaender', 'A2')).toBe(true);
    expect(darf('treuhaender', 'A1')).toBe(false);
    expect(darf('mitarbeiter', 'A1')).toBe(true);
    expect(darf('mitarbeiter', 'A12')).toBe(false);
    expect(darf('geschaeftsstelle', 'A13')).toBe(false);
    expect(darf('inhaber', 'A14')).toBe(true);
    expect(darf(null, 'A2')).toBe(false);
  });

  it('erlaubt den Jahresexport nur Inhaber und Treuhänder (Spec 02, Req 7.5)', () => {
    expect(darf('inhaber', 'EXPORT')).toBe(true);
    expect(darf('treuhaender', 'EXPORT')).toBe(true);
    expect(darf('mitarbeiter', 'EXPORT')).toBe(false);
    expect(darf('geschaeftsstelle', 'EXPORT')).toBe(false);
  });

  it('erlaubt Eingangskorb-Aktionen A6–A10 nur Inhaber und Geschäftsstelle (Req 2.6)', () => {
    for (const aktion of ['A6', 'A7', 'A8', 'A9', 'A10'] as const) {
      expect(darf('inhaber', aktion)).toBe(true);
      expect(darf('geschaeftsstelle', aktion)).toBe(true);
      expect(darf('mitarbeiter', aktion)).toBe(false);
      expect(darf('treuhaender', aktion)).toBe(false);
    }
  });
});
