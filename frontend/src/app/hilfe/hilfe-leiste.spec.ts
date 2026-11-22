import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HilfeLeiste } from './hilfe-leiste.service';

@Component({
 template: `
  <main [attr.inert]="hilfe.offen() ? '' : null">
   <h1 class="seitenkopf-titel">Seitentitel</h1>
   <button #ausloeser aria-label="Hilfe">Hilfe</button>
  </main>
 `,
})
class Gastgeber {
 readonly hilfe = inject(HilfeLeiste);
 @ViewChild('ausloeser', { read: ElementRef }) ausloeser!: ElementRef<HTMLButtonElement>;
}

describe('HilfeLeiste (Spec 07, Req 4.3, 4.5)', () => {
 const angemeldet = signal(false);
 const benutzer = signal<{ mandant: string | null; rolle: Rolle | null } | null>(null);

 function dienst(): HilfeLeiste {
  TestBed.configureTestingModule({
   providers: [{ provide: AnmeldungService, useValue: { angemeldet, benutzer } }],
  });
  return TestBed.inject(HilfeLeiste);
 }

 beforeEach(() => {
  angemeldet.set(false);
  benutzer.set(null);
 });

 it('entfernt inert vor der Fokusrückgabe und fokussiert genau einmal', async () => {
  TestBed.configureTestingModule({
   imports: [Gastgeber],
   providers: [{ provide: AnmeldungService, useValue: { angemeldet, benutzer } }],
  });
  const fixture = TestBed.createComponent(Gastgeber);
  fixture.detectChanges();
  const hilfe = fixture.componentInstance.hilfe;
  const knopf = fixture.componentInstance.ausloeser.nativeElement;
  const main = fixture.nativeElement.querySelector('main') as HTMLElement;
  const originalFocus = knopf.focus.bind(knopf);
  const fokusInertZustaende: boolean[] = [];
  vi.spyOn(knopf, 'focus').mockImplementation((options) => {
   fokusInertZustaende.push(main.hasAttribute('inert'));
   originalFocus(options);
  });

  hilfe.oeffnen('suche', knopf);
  fixture.detectChanges();
  expect(main.hasAttribute('inert')).toBe(true);
  expect(hilfe.anker()).toBe('suche');

  hilfe.schliessen();
  fixture.detectChanges();
  await fixture.whenStable();

  expect(main.hasAttribute('inert')).toBe(false);
  expect(fokusInertZustaende).toEqual([false]);
  expect(document.activeElement).toBe(knopf);
  hilfe.drawerGeschlossen();
  await fixture.whenStable();
  expect(fokusInertZustaende).toEqual([false]);
 });

 it('fokussiert bei entferntem Auslöser den Seitentitel', async () => {
  TestBed.configureTestingModule({
   imports: [Gastgeber],
   providers: [{ provide: AnmeldungService, useValue: { angemeldet, benutzer } }],
  });
  const fixture = TestBed.createComponent(Gastgeber);
  fixture.detectChanges();
  const hilfe = fixture.componentInstance.hilfe;
  const knopf = fixture.componentInstance.ausloeser.nativeElement;
  const titel = fixture.nativeElement.querySelector('h1') as HTMLElement;

  hilfe.oeffnen(null, knopf);
  fixture.detectChanges();
  knopf.remove();
  hilfe.schliessen();
  fixture.detectChanges();
  await fixture.whenStable();

  expect(document.activeElement).toBe(titel);
 });

 it('wechselt den Abschnitt, ohne zu schliessen, und bleibt offen', () => {
  const hilfe = dienst();
  hilfe.oeffnen(null, document.createElement('button'));
  hilfe.wechseln('erfassen');
  expect(hilfe.anker()).toBe('erfassen');
  expect(hilfe.offen()).toBe(true);
  hilfe.schliessen();
  expect(hilfe.offen()).toBe(false);
 });

 it('liefert die Rolle nur mit Anmeldung, Mandant und Rolle', () => {
  const hilfe = dienst();
  expect(hilfe.rolle()).toBeNull();
  benutzer.set({ mandant: 'urinet', rolle: 'inhaber' });
  expect(hilfe.rolle()).toBeNull();
  angemeldet.set(true);
  expect(hilfe.rolle()).toBe('inhaber');
  benutzer.set({ mandant: null, rolle: 'inhaber' });
  expect(hilfe.rolle()).toBeNull();
  benutzer.set({ mandant: 'urinet', rolle: null });
  expect(hilfe.rolle()).toBeNull();
 });
});
