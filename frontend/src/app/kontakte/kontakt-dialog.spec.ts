import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { KontaktDialog } from './kontakt-dialog';

@Component({
  imports: [KontaktDialog],
  template: `
    <button id="oeffner" (click)="offen.set(true)">Öffnen</button>
    @if (offen()) {
      <app-kontakt-dialog
        titel="Rückfrage"
        (schliessen)="offen.set(false); geschlossen = geschlossen + 1"
      >
        <button id="a">A</button>
        <button id="b">B</button>
      </app-kontakt-dialog>
    }
  `,
})
class Gastgeber {
  readonly offen = signal(false);
  geschlossen = 0;
}

describe('Kontakte: modaler Dialog (Req 17.17)', () => {
  async function oeffnen() {
    const fixture = TestBed.createComponent(Gastgeber);
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
    const oeffner = fixture.nativeElement.querySelector('#oeffner') as HTMLButtonElement;
    oeffner.focus();
    oeffner.click();
    await fixture.whenStable();
    return { fixture, oeffner };
  }

  it('hat Rolle, Titel und fokussiert den Titel; Escape schliesst und der Fokus kehrt zurück', async () => {
    const { fixture, oeffner } = await oeffnen();
    const dialog = fixture.nativeElement.querySelector('[role=dialog]') as HTMLElement;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    const titel = dialog.querySelector('h2')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe(titel.id);
    expect(document.activeElement).toBe(titel);
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.geschlossen).toBe(1);
    expect(fixture.nativeElement.querySelector('[role=dialog]')).toBeNull();
    expect(document.activeElement).toBe(oeffner);
    fixture.nativeElement.remove();
  });

  it('hält den Fokus mit Tab und Umschalt+Tab im Dialog', async () => {
    const { fixture } = await oeffnen();
    const dialog = fixture.nativeElement.querySelector('[role=dialog]') as HTMLElement;
    const a = dialog.querySelector('#a') as HTMLElement;
    const b = dialog.querySelector('#b') as HTMLElement;
    b.focus();
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    b.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(a);
    const zurueck = new KeyboardEvent('keydown', {
      key: 'Tab',
      shiftKey: true,
      bubbles: true,
      cancelable: true,
    });
    a.dispatchEvent(zurueck);
    expect(zurueck.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(b);
    fixture.nativeElement.remove();
  });
});
