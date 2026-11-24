import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerStatusAktion, BenutzerStatusdialog } from './benutzer-statusdialog';

describe('Bestätigung von Benutzeraktionen', () => {
  const schliessen = vi.fn();

  function starten(aktion: BenutzerStatusAktion, passkeys?: number) {
    schliessen.mockReset();
    TestBed.configureTestingModule({
      imports: [BenutzerStatusdialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { aktion, benutzername: 'alex', passkeys } },
        { provide: MatDialogRef, useValue: { close: schliessen } },
      ],
    });
    const fixture = TestBed.createComponent(BenutzerStatusdialog);
    fixture.detectChanges();
    return fixture;
  }

  it('erfordert beim Deaktivieren einen kategorisierten Grund', () => {
    const fixture = starten('deaktivieren');
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain(TEXTE.benutzerStatusdialog.aktionen.deaktivieren.wirkung);
    const bestaetigen = [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerStatusdialog.bestaetigen)!;
    expect(bestaetigen.disabled).toBe(true);
    const grund = element.querySelector<HTMLSelectElement>('#status-grund')!;
    grund.value = 'AUSTRITT';
    grund.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(bestaetigen.disabled).toBe(false);
    bestaetigen.click();
    expect(schliessen).toHaveBeenCalledWith({ grund: 'AUSTRITT' });
  });

  it('erklärt den E-Mail-Versand und verlangt beim Zugang zurücksetzen keinen Grund', () => {
    const fixture = starten('zugangZuruecksetzen');
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain(TEXTE.benutzerStatusdialog.aktionen.zugangZuruecksetzen.wirkung);
    expect(element.querySelector('#status-grund')).toBeNull();
    [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerStatusdialog.bestaetigen)!.click();
    expect(schliessen).toHaveBeenCalledWith({});
  });

  it('bietet beim Zugang zurücksetzen die Option «Passkeys löschen» nur mit Passkeys an, standardmässig aus (Req 10.5)', () => {
    const ohne = starten('zugangZuruecksetzen', 0).nativeElement as HTMLElement;
    expect(ohne.querySelector('input[name=passkeys]')).toBeNull();
    TestBed.resetTestingModule();
    const fixture = starten('zugangZuruecksetzen', 2);
    const element = fixture.nativeElement as HTMLElement;
    const option = element.querySelector<HTMLInputElement>('input[name=passkeys]')!;
    expect(option.checked).toBe(false);
    expect(element.textContent).toContain('Auch die 2 Passkeys löschen');
    expect(element.textContent).toContain(TEXTE.benutzerStatusdialog.passkeysHinweis);
    const bestaetigen = () => [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerStatusdialog.bestaetigen)!.click();
    bestaetigen();
    expect(schliessen).toHaveBeenLastCalledWith({});
    option.click();
    fixture.detectChanges();
    bestaetigen();
    expect(schliessen).toHaveBeenLastCalledWith({ passkeys: true });
  });

  it('zeigt die Option bei anderen Aktionen nie', () => {
    const element = starten('sperren', 2).nativeElement as HTMLElement;
    expect(element.querySelector('input[name=passkeys]')).toBeNull();
  });

  it('übernimmt beim Abbrechen keine Aktion', () => {
    const fixture = starten('sperren');
    const element = fixture.nativeElement as HTMLElement;
    [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerStatusdialog.abbrechen)!.click();
    expect(schliessen).toHaveBeenCalledWith(null);
  });
});
