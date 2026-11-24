import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerLoeschdialog } from './benutzer-loeschdialog';

describe('Löschdialog', () => {
  let fixture: ComponentFixture<BenutzerLoeschdialog>;
  let element: HTMLElement;
  const schliessen = vi.fn();

  beforeEach(() => {
    schliessen.mockReset();
    TestBed.configureTestingModule({
      imports: [BenutzerLoeschdialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { benutzername: 'alex', dokumente: 3, protokolleintraege: 12 } },
        { provide: MatDialogRef, useValue: { close: schliessen } },
      ],
    });
    fixture = TestBed.createComponent(BenutzerLoeschdialog);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('zeigt die Auswirkungen und benötigt Grund sowie den exakten Benutzernamen', () => {
    expect(element.textContent).toContain(TEXTE.benutzerLoeschdialog.erhalten);
    expect(element.querySelectorAll('dd')[0].textContent).toContain('3');
    expect(element.querySelectorAll('dd')[1].textContent).toContain('12');
    const eingabe = element.querySelector<HTMLInputElement>('#loeschen-bestaetigung')!;
    const grund = element.querySelector<HTMLSelectElement>('#loeschen-grund')!;
    const knopf = [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerLoeschdialog.loeschen)!;
    expect(knopf.disabled).toBe(true);

    eingabe.value = 'Alex';
    eingabe.dispatchEvent(new Event('input'));
    grund.value = 'AUSTRITT';
    grund.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(knopf.disabled).toBe(true);

    eingabe.value = 'alex';
    eingabe.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(knopf.disabled).toBe(false);
    knopf.click();
    expect(schliessen).toHaveBeenCalledOnce();
    expect(schliessen).toHaveBeenCalledWith('AUSTRITT');
  });

  it('verhindert die Bestätigung ohne Grund und erlaubt Abbrechen', () => {
    const eingabe = element.querySelector<HTMLInputElement>('#loeschen-bestaetigung')!;
    eingabe.value = 'alex';
    eingabe.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const knoepfe = [...element.querySelectorAll('button')];
    expect(knoepfe.find((button) => button.textContent?.trim() === TEXTE.benutzerLoeschdialog.loeschen)!.disabled)
      .toBe(true);
    knoepfe.find((button) => button.textContent?.trim() === TEXTE.benutzerLoeschdialog.abbrechen)!.click();
    expect(schliessen).toHaveBeenCalledWith(null);
  });
});
