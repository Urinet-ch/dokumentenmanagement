import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { AnmeldungService, FENSTER } from '../../gemeinsam/anmeldung/anmeldung.service';
import { BenutzerFortsetzen, istStepUp } from './benutzer-fortsetzung';

describe('Fortsetzung nach Step-up (Spec 06, Req 8.5)', () => {
  const daten = new Map<string, string>();
  const speicher = {
    getItem: (schluessel: string) => daten.get(schluessel) ?? null,
    setItem: (schluessel: string, wert: string) => void daten.set(schluessel, wert),
    removeItem: (schluessel: string) => void daten.delete(schluessel),
  };
  const anmelden = vi.fn();
  const entnehmen = vi.fn();
  let benutzer: { id: string; mandant: string } | null;

  beforeEach(() => {
    daten.clear();
    benutzer = { id: 'akteur-1', mandant: 'urinet' };
    anmelden.mockReset().mockResolvedValue(undefined);
    entnehmen.mockReset().mockReturnValue(null);
    TestBed.configureTestingModule({
      providers: [
        { provide: FENSTER, useValue: { sessionStorage: speicher, location: { origin: 'x', assign: vi.fn() } } },
        { provide: AnmeldungService, useValue: { benutzer: () => benutzer, anmelden, fortsetzungEntnehmen: entnehmen } },
      ],
    });
  });

  it('erkennt nur 401 STEP_UP_NOETIG', () => {
    expect(istStepUp(new HttpErrorResponse({ status: 401, error: { fehlercode: 'STEP_UP_NOETIG' } }))).toBe(true);
    expect(istStepUp(new HttpErrorResponse({ status: 401 }))).toBe(false);
    expect(istStepUp(new HttpErrorResponse({ status: 409, error: { fehlercode: 'STEP_UP_NOETIG' } }))).toBe(false);
  });

  it('speichert nur Aktion, Kennungen und Codes, nie Namen oder Adressen, und meldet frisch an', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    expect(fortsetzen.starten({ aktion: 'sperren', ziel: 'ziel-1', grund: 'AUSTRITT' })).toBe(true);
    const gespeichert = JSON.parse([...daten.values()][0]);
    expect(Object.keys(gespeichert).sort()).toEqual(
      ['aktion', 'benutzer', 'erstellt', 'grund', 'id', 'mandant', 'ziel']);
    expect(anmelden).toHaveBeenCalledExactlyOnceWith('/verwaltung/benutzer/ziel-1', true, gespeichert.id);
  });

  it('speichert die Option Passkeys nur als Wahrheitswert bei «Zugang zurücksetzen» (Req 10.5)', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    expect(fortsetzen.starten({ aktion: 'zugangZuruecksetzen', ziel: 'ziel-1', passkeys: true })).toBe(true);
    expect(JSON.parse([...daten.values()][0]).passkeys).toBe(true);
    daten.clear();
    expect(fortsetzen.starten({ aktion: 'sperren', ziel: 'ziel-1', passkeys: true })).toBe(false);
    expect(fortsetzen.starten({ aktion: 'zugangZuruecksetzen', ziel: 'ziel-1', passkeys: 'Gerät A' as never })).toBe(false);
    expect(daten.size).toBe(0);
  });

  it('lehnt unzulässige Inhalte ab, statt sie zu speichern', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    expect(fortsetzen.starten({ aktion: 'sperren', ziel: 'x@example.org' })).toBe(false);
    expect(fortsetzen.starten({ aktion: 'sperren', ziel: 'ziel-1', grund: 'Max Muster, privat' })).toBe(false);
    expect(fortsetzen.starten({ aktion: 'rolle', ziel: 'ziel-1' })).toBe(false);
    expect(daten.size).toBe(0);
    expect(anmelden).not.toHaveBeenCalled();
  });

  it('übernimmt nur nach dem Rückruf mit passender Kennung, einmalig', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    fortsetzen.starten({ aktion: 'rolle', ziel: 'ziel-1', etag: 'a'.repeat(64), rolle: 'mitarbeiter' });
    const id = JSON.parse([...daten.values()][0]).id;
    entnehmen.mockReturnValue(id);
    expect(fortsetzen.uebernehmen()).toMatchObject({ aktion: 'rolle', ziel: 'ziel-1', rolle: 'mitarbeiter' });
    expect(daten.size).toBe(0);
    entnehmen.mockReturnValue(id);
    expect(fortsetzen.uebernehmen()).toBe('ungueltig');
  });

  it('ohne erfolgreichen Rückruf gibt es keine Fortsetzung, auch nicht beim Neuladen', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    fortsetzen.starten({ aktion: 'sperren', ziel: 'ziel-1' });
    expect(fortsetzen.uebernehmen()).toBeNull();
  });

  it('verwirft Fortsetzungen anderer Kennung, anderer Sitzung, anderen Mandanten und nach zehn Minuten', () => {
    const fortsetzen = TestBed.inject(BenutzerFortsetzen);
    const neu = () => {
      daten.clear();
      fortsetzen.starten({ aktion: 'entsperren', ziel: 'ziel-1' });
      return JSON.parse([...daten.values()][0]).id as string;
    };
    entnehmen.mockReturnValue('fremd');
    neu();
    expect(fortsetzen.uebernehmen()).toBe('ungueltig');
    entnehmen.mockReturnValue(neu());
    benutzer = { id: 'anderer', mandant: 'urinet' };
    expect(fortsetzen.uebernehmen()).toBe('ungueltig');
    benutzer = { id: 'akteur-1', mandant: 'urinet' };
    entnehmen.mockReturnValue(neu());
    benutzer = { id: 'akteur-1', mandant: 'andere-firma' };
    expect(fortsetzen.uebernehmen()).toBe('ungueltig');
    benutzer = { id: 'akteur-1', mandant: 'urinet' };
    entnehmen.mockReturnValue(neu());
    expect(fortsetzen.uebernehmen(Date.now() + 10 * 60 * 1000 + 1000)).toBe('ungueltig');
    entnehmen.mockReturnValue(neu());
    daten.set([...daten.keys()][0], '{kaputt');
    expect(fortsetzen.uebernehmen()).toBe('ungueltig');
  });
});
