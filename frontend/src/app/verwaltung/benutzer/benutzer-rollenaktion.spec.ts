import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerFortsetzen, BenutzerFortsetzung } from './benutzer-fortsetzung';
import { BenutzerRollenaktion } from './benutzer-rollenaktion';

describe('Rollenänderung', () => {
  const etag = 'a'.repeat(64);
  const inhaber: VerwaltungRolle = { code: 'inhaber', beschreibung: 'Inhaber', rechte: [] };
  const mitarbeiter: VerwaltungRolle = { code: 'mitarbeiter', beschreibung: 'Mitarbeiter', rechte: [] };
  const api = { apiV1BenutzerBenutzerIdRollePut: vi.fn() };
  const oeffnen = vi.fn();
  const stepUp = { starten: vi.fn() };
  const stepUpFehler = () => new HttpErrorResponse({ status: 401, error: { fehlercode: 'STEP_UP_NOETIG' } });
  const fortsetzung = (etagWert = etag, ziel = 'ziel'): BenutzerFortsetzung => ({
    id: 'f', benutzer: 'akteur', mandant: 'urinet', aktion: 'rolle', ziel, etag: etagWert, rolle: 'inhaber', erstellt: Date.now(),
  });
  let geschlossen: Subject<string | null>;

  beforeEach(() => {
    geschlossen = new Subject();
    stepUp.starten.mockReset().mockReturnValue(true);
    oeffnen.mockReset().mockReturnValue({ afterClosed: () => geschlossen, close: vi.fn() });
    api.apiV1BenutzerBenutzerIdRollePut.mockReset().mockReturnValue(of(new HttpResponse({ status: 204 })));
    TestBed.configureTestingModule({
      imports: [BenutzerRollenaktion],
      providers: [
        { provide: BenutzerverwaltungService, useValue: api },
        { provide: MatDialog, useValue: { open: oeffnen } },
        { provide: BenutzerFortsetzen, useValue: stepUp },
      ],
    });
  });

  function starten() {
    const fixture = TestBed.createComponent(BenutzerRollenaktion);
    fixture.componentRef.setInput('person', {
      id: 'ziel', benutzername: 'alex', etag, rollen: new Set(['MITARBEITER']),
    });
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.componentRef.setInput('rollen', [mitarbeiter, inhaber]);
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('übernimmt die Rolle erst nach Vergleich und sendet den ETag mit', () => {
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    expect(oeffnen.mock.lastCall![1].data.bisher).toEqual([mitarbeiter]);
    geschlossen.next('inhaber');
    expect(api.apiV1BenutzerBenutzerIdRollePut).toHaveBeenCalledExactlyOnceWith(
      'ziel', { rolle: 'inhaber' }, `"${etag}"`, undefined, 'response',
    );
    expect(ergebnis).toHaveBeenCalledWith({ id: 'ziel', antrag: false });
  });

  it('unterscheidet einen Antrag von einer direkt wirksamen Änderung', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(
      of(new HttpResponse({ status: 202, body: { antragId: 'antrag' } })),
    );
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('inhaber');
    expect(ergebnis).toHaveBeenCalledWith({ id: 'ziel', antrag: true });
  });

  it('führt bei Zielwechsel oder fehlendem Rechtevergleich keine Änderung aus', () => {
    const { fixture, element } = starten();
    element.querySelector('button')!.click();
    fixture.componentRef.setInput('person', {
      id: 'anderes-ziel', benutzername: 'bea', etag, rollen: new Set(['MITARBEITER']),
    });
    fixture.detectChanges();
    geschlossen.next('inhaber');
    expect(api.apiV1BenutzerBenutzerIdRollePut).not.toHaveBeenCalled();
    fixture.componentRef.setInput('rollen', [inhaber]);
    fixture.detectChanges();
    element.querySelector('button')!.click();
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerRollenaktion.unvollstaendig);
    expect(oeffnen).toHaveBeenCalledTimes(1);
  });

  it('zeigt einen Versionskonflikt statt einer Erfolgsmeldung', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 412 })),
    );
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('inhaber');
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.versionKonflikt);
    expect(ergebnis).not.toHaveBeenCalled();
  });

  it('merkt bei STEP_UP_NOETIG nur Ziel, ETag und Rollencode', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(throwError(stepUpFehler));
    const { element } = starten();
    element.querySelector('button')!.click();
    geschlossen.next('inhaber');
    expect(stepUp.starten).toHaveBeenCalledExactlyOnceWith(
      { aktion: 'rolle', ziel: 'ziel', etag, rolle: 'inhaber' }, expect.any(Function));
  });

  function mitFortsetzung(wert: BenutzerFortsetzung, personEtag = etag) {
    const fixture = TestBed.createComponent(BenutzerRollenaktion);
    fixture.componentRef.setInput('person', {
      id: 'ziel', benutzername: 'alex', etag: personEtag, rollen: new Set(['MITARBEITER']),
    });
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.componentRef.setInput('rollen', [mitarbeiter, inhaber]);
    fixture.componentRef.setInput('fortsetzung', wert);
    fixture.detectChanges();
    fixture.detectChanges();
    return fixture;
  }

  it('sendet die Rollenänderung nach der frischen Anmeldung genau einmal mit demselben ETag', () => {
    const fixture = mitFortsetzung(fortsetzung());
    expect(api.apiV1BenutzerBenutzerIdRollePut).toHaveBeenCalledExactlyOnceWith(
      'ziel', { rolle: 'inhaber' }, `"${etag}"`, undefined, 'response',
    );
    fixture.detectChanges();
    expect(api.apiV1BenutzerBenutzerIdRollePut).toHaveBeenCalledTimes(1);
  });

  it('sendet nach der Fortsetzung nichts bei geändertem ETag oder anderem Ziel', () => {
    mitFortsetzung(fortsetzung('b'.repeat(64)));
    mitFortsetzung(fortsetzung(etag, 'anderes'));
    expect(api.apiV1BenutzerBenutzerIdRollePut).not.toHaveBeenCalled();
  });

  it('leitet nach einer Fortsetzung bei erneutem STEP_UP_NOETIG nicht noch einmal weiter', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(throwError(stepUpFehler));
    const fixture = mitFortsetzung(fortsetzung());
    expect(stepUp.starten).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent)
      .toContain(TEXTE.benutzerFortsetzung.wiederholtAbgelehnt);
  });
});
