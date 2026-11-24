import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerRollendialog } from './benutzer-rollendialog';

describe('Rollenänderung bestätigen', () => {
  const schliessen = vi.fn();
  const inhaber: VerwaltungRolle = { code: 'inhaber', beschreibung: 'Inhaber', rechte: [
    { aktion: 'A11', bezeichnung: 'Protokoll verwalten', ergebnis: 'ERLAUBT', fussnoten: [] },
  ] };
  const mitarbeiter: VerwaltungRolle = { code: 'mitarbeiter', beschreibung: 'Mitarbeiter', rechte: [
    { aktion: 'A1', bezeichnung: 'Dokument erfassen', ergebnis: 'ERLAUBT', fussnoten: [] },
  ] };

  it('zeigt bisherige und neue Rechte und bestätigt nur eine geänderte Rolle', () => {
    schliessen.mockReset();
    TestBed.configureTestingModule({
      imports: [BenutzerRollendialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: {
          benutzername: 'alex', bisher: [mitarbeiter], verfuegbar: [mitarbeiter, inhaber],
        } },
        { provide: MatDialogRef, useValue: { close: schliessen } },
      ],
    });
    const fixture = TestBed.createComponent(BenutzerRollendialog);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const bestaetigen = [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerRollendialog.bestaetigen)!;
    const auswahl = element.querySelector<HTMLSelectElement>('#neue-rolle')!;
    expect(element.textContent).toContain(TEXTE.benutzerRollendialog.bisher);
    expect(element.textContent).toContain('Dokument erfassen');
    expect(bestaetigen.disabled).toBe(true);
    auswahl.value = 'mitarbeiter';
    auswahl.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(bestaetigen.disabled).toBe(true);
    auswahl.value = 'inhaber';
    auswahl.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.benutzerRollendialog.neu);
    expect(element.textContent).toContain('Protokoll verwalten');
    expect(bestaetigen.disabled).toBe(false);
    bestaetigen.click();
    expect(schliessen).toHaveBeenCalledWith('inhaber');
  });
});
