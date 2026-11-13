import { HttpErrorResponse } from '@angular/common/http';
import { VerzeichnisEintrag } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { LEERER_FILTER, austrittMoeglich, fehlerText, filterFehler, parameter, tagesbeginn, wer } from './protokoll-logik';

describe('Zugriffsprotokoll: Logik (Aufgabe 5.1)', () => {
  it('rechnet den Tagesbeginn in Europe/Zurich nach UTC um, auch an der Zeitumstellung', () => {
    expect(tagesbeginn('2026-11-12')).toBe('2026-11-11T23:00:00.000Z');
    expect(tagesbeginn('2026-07-01')).toBe('2026-06-30T22:00:00.000Z');
    expect(tagesbeginn('2026-03-29')).toBe('2026-03-28T23:00:00.000Z');
    expect(tagesbeginn('2026-03-29', 1)).toBe('2026-03-29T22:00:00.000Z');
    expect(tagesbeginn('2026-12-31', 1)).toBe('2026-12-31T23:00:00.000Z');
    expect(tagesbeginn('')).toBeNull();
  });

  it('schliesst den Bis-Tag ein und lässt leere Filter weg', () => {
    expect(parameter({ ...LEERER_FILTER, von: '2026-11-01', bis: '2026-11-01', benutzer: ' sub-1 ', aktion: 'ANZEIGEN' }))
      .toEqual({
        von: '2026-10-31T23:00:00.000Z',
        bis: '2026-11-01T23:00:00.000Z',
        benutzer: 'sub-1',
        dokument: undefined,
        aktion: 'ANZEIGEN',
      });
    expect(parameter({ ...LEERER_FILTER, aktion: 'GIBT_ES_NICHT' }).aktion).toBeUndefined();
  });

  it('prüft Zeitraum und Dokument-ID vor der Abfrage', () => {
    expect(filterFehler({ ...LEERER_FILTER, von: '2026-11-02', bis: '2026-11-01' })).toBe(TEXTE.protokoll.vonNachBis);
    expect(filterFehler({ ...LEERER_FILTER, dokument: 'abc' })).toBe(TEXTE.protokoll.dokumentUngueltig);
    expect(filterFehler({ ...LEERER_FILTER, dokument: '0b6c3f5e-2a4d-4c1e-9f7a-1d2e3f4a5b6c' })).toBeNull();
  });

  it('zeigt Name und Rolle zur Zeit des Eintrags, ohne Kennung «System»', () => {
    expect(wer({ id: 1, zeit: '', aktion: 'ANZEIGEN', benutzer: 'sub-1', name: 'Anna Probe', rolle: 'MITARBEITER' })).toBe(
      'Anna Probe (Mitarbeiter)',
    );
    expect(wer({ id: 2, zeit: '', aktion: 'ERFASSEN', benutzer: 'token:t1', rolle: 'API_TOKEN' })).toBe(
      `${TEXTE.protokoll.unbekannt} (API-Token)`,
    );
    expect(wer({ id: 3, zeit: '', aktion: 'ERFASSEN' })).toBe(TEXTE.protokoll.system);
  });

  it('erlaubt den Austritt nur an der letzten, offenen Zeile einer Person', () => {
    const zeile = (id: number, benutzer: string, gueltigBis?: string): VerzeichnisEintrag => ({
      id,
      zeit: '',
      benutzer,
      gueltigVon: '',
      gueltigBis,
      anlass: 'AKTION',
    });
    const alt = zeile(1, 'sub-1');
    const neu = zeile(2, 'sub-1');
    const beendet = zeile(3, 'sub-2', '2026-11-12T08:00:00Z');
    const token = zeile(4, 'token:t1');
    const alle = [alt, neu, beendet, token];
    expect(austrittMoeglich(alt, alle)).toBe(false);
    expect(austrittMoeglich(neu, alle)).toBe(true);
    expect(austrittMoeglich(beendet, alle)).toBe(false);
    expect(austrittMoeglich(token, alle)).toBe(false);
  });

  it('übersetzt Fehlercodes in Meldungen ohne Technik', () => {
    const fehler = (status: number, fehlercode?: string) =>
      new HttpErrorResponse({ status, error: fehlercode ? { fehlercode } : null });
    expect(fehlerText(fehler(409, 'STATUS_KONFLIKT'))).toBe(TEXTE.protokoll.fehlercode['STATUS_KONFLIKT']);
    expect(fehlerText(fehler(401))).toBe(TEXTE.protokoll.fehlerSitzung);
    expect(fehlerText(fehler(0))).toBe(TEXTE.protokoll.fehlerVerbindung);
    expect(fehlerText(fehler(500))).toBe(TEXTE.protokoll.fehlerAllgemein);
  });
});
