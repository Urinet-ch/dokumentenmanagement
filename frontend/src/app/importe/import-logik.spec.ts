import { HttpErrorResponse } from '@angular/common/http';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  entscheideAnfrage,
  fehlerText,
  kontaktText,
  pflichtErfuellt,
  profilGilt,
  zahl,
  zeit,
  zuordnen,
  zuordnungAnfrage,
} from './import-logik';

describe('Import-Logik (Aufgabe 4.4)', () => {
  it('ordnet jedes Feld höchstens einer Spalte zu (Req 13.2)', () => {
    let felder = zuordnen({}, 0, 'name');
    felder = zuordnen(felder, 1, 'plz');
    felder = zuordnen(felder, 2, 'name');
    expect(felder).toEqual({ plz: 1, name: 2 });
    expect(zuordnen(felder, 1, null)).toEqual({ name: 2 });
    expect(zuordnen(felder, 2, 'ort')).toEqual({ plz: 1, ort: 2 });
  });

  it('verlangt Name oder Organisation bzw. Titel (Req 13.10)', () => {
    expect(pflichtErfuellt('kontakt', { plz: 0 })).toBe(false);
    expect(pflichtErfuellt('kontakt', { organisation: 3 })).toBe(true);
    expect(pflichtErfuellt('verkaufschance', { name: 0 })).toBe(false);
    expect(pflichtErfuellt('verkaufschance', { titel: 0 })).toBe(true);
  });

  it('sendet das Profil nur, solange alle Profilfelder unverändert zugeordnet sind', () => {
    const vorschlag = { name: 0, plz: 1 };
    expect(profilGilt('kontakt', vorschlag, { name: 0, plz: 1, ort: 2 })).toBe(true);
    expect(profilGilt('kontakt', vorschlag, { name: 0 })).toBe(false);
    expect(profilGilt('kontakt', vorschlag, { name: 0, plz: 2 })).toBe(false);
    expect(profilGilt('verkaufschance', vorschlag, vorschlag)).toBe(false);

    expect(zuordnungAnfrage('l1', 'kontakt', vorschlag, true, { name: 0, plz: 1 })).toEqual({
      laufId: 'l1',
      profil: '21.CRM',
      felder: { name: 0, plz: 1 },
    });
    // Ein entferntes Profilfeld käme mit dem Profil auf dem Server zurück, darum ohne Profil senden
    expect(zuordnungAnfrage('l1', 'kontakt', vorschlag, true, { name: 0 })).toEqual({
      laufId: 'l1',
      profil: null,
      felder: { name: 0 },
    });
  });

  it('sendet nur getroffene Dubletten-Entscheide (Req 13.5, 13.6)', () => {
    const entscheide = new Map<number, 'NEU' | 'UEBERSPRINGEN' | `ZUSAMMENFUEHREN:${string}`>([
      [3, 'NEU'],
      [7, 'ZUSAMMENFUEHREN:abc'],
    ]);
    expect(entscheideAnfrage(entscheide)).toEqual({ '3': 'NEU', '7': 'ZUSAMMENFUEHREN:abc' });
    expect(entscheideAnfrage(new Map())).toEqual({});
  });

  it('formatiert Zahlen mit Apostroph und Zeiten in Europe/Zurich', () => {
    expect(zahl(1234567)).toBe("1'234'567");
    expect(zahl(null)).toBe('0');
    expect(zeit('2026-11-08T23:30:00Z')).toBe('09.11.2026, 00:30');
  });

  it('beschreibt Kontakte kurz', () => {
    expect(kontaktText({ name: 'Muster AG', plz: '6460', ort: 'Altdorf', crmId: '42' }, 'x')).toBe(
      '«Muster AG», 6460 Altdorf, ID 42',
    );
    expect(kontaktText(undefined, '12345678-aaaa')).toBe('Kontakt 12345678');
  });

  it('übersetzt Fehlerantworten ohne technische Einzelheiten', () => {
    const problem = (status: number, error: unknown) => new HttpErrorResponse({ status, error });
    expect(fehlerText(problem(0, null))).toBe(TEXTE.importe.fehlerVerbindung);
    expect(fehlerText(problem(401, null))).toBe(TEXTE.importe.fehlerSitzung);
    expect(fehlerText(problem(422, { fehlercode: 'CSV_LEER' }))).toBe(TEXTE.importe.fehlercode['CSV_LEER']);
    expect(fehlerText(problem(422, { fehlercode: 'CSV_LEER', detail: 'keine Datenzeilen' }))).toBe('keine Datenzeilen');
    expect(fehlerText(problem(500, 'kaputt'))).toBe(TEXTE.importe.fehlerAllgemein);
  });
});
