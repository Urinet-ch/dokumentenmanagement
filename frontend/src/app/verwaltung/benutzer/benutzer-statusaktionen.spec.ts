import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerFortsetzen, BenutzerFortsetzung } from './benutzer-fortsetzung';
import { BenutzerStatusaktionen } from './benutzer-statusaktionen';
import { BenutzerStatusdialogErgebnis } from './benutzer-statusdialog';

describe('Benutzerstatusaktionen', () => {
  const api = {
    apiV1BenutzerBenutzerIdSperrenPost: vi.fn(),
    apiV1BenutzerBenutzerIdEntsperrenPost: vi.fn(),
    apiV1BenutzerBenutzerIdZugangZuruecksetzenPost: vi.fn(),
  };
  const oeffnen = vi.fn();
  const stepUp = { starten: vi.fn() };
  const stepUpFehler = () => new HttpErrorResponse({ status: 401, error: { fehlercode: 'STEP_UP_NOETIG' } });
  const fortsetzung = (aktion: BenutzerFortsetzung['aktion'], ziel = 'ziel', grund?: string): BenutzerFortsetzung => ({
    id: 'f', benutzer: 'akteur', mandant: 'urinet', aktion, ziel, grund, erstellt: Date.now(),
  });
  let geschlossen: Subject<BenutzerStatusdialogErgebnis | null>;

  beforeEach(() => {
    geschlossen = new Subject();
    stepUp.starten.mockReset().mockReturnValue(true);
    oeffnen.mockReset().mockReturnValue({ afterClosed: () => geschlossen, close: vi.fn() });
    api.apiV1BenutzerBenutzerIdSperrenPost.mockReset().mockReturnValue(of(null));
    api.apiV1BenutzerBenutzerIdEntsperrenPost.mockReset().mockReturnValue(of(null));
    api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost.mockReset().mockReturnValue(of(null));
    TestBed.configureTestingModule({
      imports: [BenutzerStatusaktionen],
      providers: [
        { provide: BenutzerverwaltungService, useValue: api },
        { provide: MatDialog, useValue: { open: oeffnen } },
        { provide: BenutzerFortsetzen, useValue: stepUp },
      ],
    });
  });

  function starten(person = { id: 'ziel', benutzername: 'alex', status: 'AKTIV' }) {
    const fixture = TestBed.createComponent(BenutzerStatusaktionen);
    fixture.componentRef.setInput('person', person);
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, element };
  }

  function klicken(element: HTMLElement, text: string) {
    [...element.querySelectorAll('button')].find((button) => button.textContent?.trim() === text)!.click();
  }

  it('sperrt nach bestätigtem Grund genau das angezeigte Ziel', () => {
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.sperren.titel);
    fixture.detectChanges();
    expect([...element.querySelectorAll('button')].every((button) => button.disabled)).toBe(true);
    geschlossen.next({ grund: 'AUSTRITT' });
    expect(api.apiV1BenutzerBenutzerIdSperrenPost).toHaveBeenCalledExactlyOnceWith('ziel', { grund: 'AUSTRITT' });
    expect(ergebnis).toHaveBeenCalledWith({ id: 'ziel', aktion: 'sperren' });
  });

  it('führt bei Abbruch oder Zielwechsel keine Aktion aus', () => {
    const { fixture, element } = starten();
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.sperren.titel);
    geschlossen.next(null);
    fixture.detectChanges();
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.sperren.titel);
    fixture.componentRef.setInput('person', { id: 'andere', benutzername: 'bea', status: 'AKTIV' });
    fixture.detectChanges();
    geschlossen.next({ grund: 'AUSTRITT' });
    expect(api.apiV1BenutzerBenutzerIdSperrenPost).not.toHaveBeenCalled();
  });

  it('zeigt einen Fehler statt Erfolg und gibt die Aktion wieder frei', () => {
    api.apiV1BenutzerBenutzerIdEntsperrenPost.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { fehlercode: 'STATUS_KONFLIKT' } })),
    );
    const { fixture, element } = starten({ id: 'ziel', benutzername: 'alex', status: 'GESPERRT' });
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.entsperren.titel);
    geschlossen.next({});
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.statusKonflikt);
    expect(ergebnis).not.toHaveBeenCalled();
    expect(element.querySelector('button')!.disabled).toBe(false);
  });

  it('bietet bei deaktiviertem Konto trotz temporärer Sperre kein Entsperren an', () => {
    const { fixture, element } = starten({ id: 'ziel', benutzername: 'alex', status: 'DEAKTIVIERT' });
    fixture.componentRef.setInput('person', {
      id: 'ziel', benutzername: 'alex', status: 'DEAKTIVIERT', voruebergehendGesperrt: true,
    });
    fixture.detectChanges();
    expect(element.textContent).not.toContain(TEXTE.benutzerStatusdialog.aktionen.entsperren.titel);
    expect(element.textContent).toContain(TEXTE.benutzerStatusdialog.aktionen.reaktivieren.titel);
  });

  it('merkt bei STEP_UP_NOETIG nur Aktion, Ziel und Grundcode und startet die frische Anmeldung', () => {
    api.apiV1BenutzerBenutzerIdSperrenPost.mockReturnValue(throwError(stepUpFehler));
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.sperren.titel);
    geschlossen.next({ grund: 'AUSTRITT' });
    expect(stepUp.starten).toHaveBeenCalledExactlyOnceWith(
      { aktion: 'sperren', ziel: 'ziel', grund: 'AUSTRITT' }, expect.any(Function));
    expect(ergebnis).not.toHaveBeenCalled();
  });

  it('sendet die Aktion nach der frischen Anmeldung genau einmal und leitet nicht erneut weiter', () => {
    api.apiV1BenutzerBenutzerIdSperrenPost.mockReturnValue(throwError(stepUpFehler));
    const fixture = TestBed.createComponent(BenutzerStatusaktionen);
    fixture.componentRef.setInput('person', { id: 'ziel', benutzername: 'alex', status: 'AKTIV' });
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.componentRef.setInput('fortsetzung', fortsetzung('sperren', 'ziel', 'AUSTRITT'));
    fixture.detectChanges();
    fixture.componentRef.setInput('person', { id: 'ziel', benutzername: 'alex', status: 'AKTIV', totp: true });
    fixture.detectChanges();
    expect(api.apiV1BenutzerBenutzerIdSperrenPost).toHaveBeenCalledExactlyOnceWith('ziel', { grund: 'AUSTRITT' });
    expect(stepUp.starten).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent)
      .toContain(TEXTE.benutzerFortsetzung.wiederholtAbgelehnt);
  });

  it('führt die Fortsetzung nicht aus, wenn Ziel oder Zustand nicht mehr passen', () => {
    for (const [ziel, status] of [['anderes', 'AKTIV'], ['ziel', 'DEAKTIVIERT']]) {
      api.apiV1BenutzerBenutzerIdSperrenPost.mockClear();
      const fixture = TestBed.createComponent(BenutzerStatusaktionen);
      fixture.componentRef.setInput('person', { id: 'ziel', benutzername: 'alex', status });
      fixture.componentRef.setInput('akteurId', 'akteur');
      fixture.componentRef.setInput('fortsetzung', fortsetzung('sperren', ziel, 'AUSTRITT'));
      fixture.detectChanges();
      expect(api.apiV1BenutzerBenutzerIdSperrenPost).not.toHaveBeenCalled();
    }
  });

  it('sendet «Zugang zurücksetzen» mit Option Passkeys nur, wenn sie gewählt wurde, und zeigt die Anzahl im Dialog (Req 10.5)', () => {
    const { fixture, element } = starten({ id: 'ziel', benutzername: 'alex', status: 'AKTIV', passkeyAnzahl: 2 } as never);
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.zugangZuruecksetzen.titel);
    expect(oeffnen).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ data: { aktion: 'zugangZuruecksetzen', benutzername: 'alex', passkeys: 2 } }));
    geschlossen.next({ passkeys: true });
    fixture.detectChanges();
    expect(api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost).toHaveBeenCalledExactlyOnceWith('ziel', { passkeys: true });
  });

  it('sendet ohne die Option einen leeren Körper wie bisher', () => {
    const { element } = starten();
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.zugangZuruecksetzen.titel);
    geschlossen.next({});
    expect(api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost).toHaveBeenCalledExactlyOnceWith('ziel', {});
  });

  it('merkt bei STEP_UP_NOETIG die Option Passkeys nur als Code und setzt sie nach der Anmeldung einmal fort (Req 10.5)', () => {
    api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost.mockReturnValueOnce(throwError(stepUpFehler));
    const { element } = starten();
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.zugangZuruecksetzen.titel);
    geschlossen.next({ passkeys: true });
    expect(stepUp.starten).toHaveBeenCalledExactlyOnceWith(
      { aktion: 'zugangZuruecksetzen', ziel: 'ziel', grund: undefined, passkeys: true }, expect.any(Function));

    api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost.mockClear();
    const fixture = TestBed.createComponent(BenutzerStatusaktionen);
    fixture.componentRef.setInput('person', { id: 'ziel', benutzername: 'alex', status: 'AKTIV' });
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.componentRef.setInput('fortsetzung', { ...fortsetzung('zugangZuruecksetzen'), passkeys: true });
    fixture.detectChanges();
    expect(api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost).toHaveBeenCalledExactlyOnceWith('ziel', { passkeys: true });
  });

  it('zeigt BESTAETIGUNG_NOETIG als eigene Meldung ohne Wiederholung', () => {
    api.apiV1BenutzerBenutzerIdSperrenPost.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { fehlercode: 'BESTAETIGUNG_NOETIG', auswirkung: [{ schluessel: 'x', text: 'Folge.' }] },
    })));
    const { fixture, element } = starten();
    klicken(element, TEXTE.benutzerStatusdialog.aktionen.sperren.titel);
    geschlossen.next({ grund: 'AUSTRITT' });
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.bestaetigungNoetig);
    expect(api.apiV1BenutzerBenutzerIdSperrenPost).toHaveBeenCalledTimes(1);
  });
});
