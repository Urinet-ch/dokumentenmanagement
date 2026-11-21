import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HilfeLeiste } from './hilfe-leiste.service';

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

 it('gibt den Fokus beim Schliessen an den Auslöser zurück', () => {
  const hilfe = dienst();
  const knopf = document.createElement('button');
  document.body.append(knopf);
  hilfe.oeffnen('suche', knopf);
  expect(hilfe.offen()).toBe(true);
  expect(hilfe.anker()).toBe('suche');
  hilfe.geaendert(false);
  expect(hilfe.offen()).toBe(false);
  hilfe.geschlossen();
  expect(document.activeElement).toBe(knopf);
  knopf.remove();
 });

 it('wechselt den Abschnitt, ohne zu schliessen, und bleibt bei geaendert(true) offen', () => {
  const hilfe = dienst();
  hilfe.oeffnen(null, document.createElement('button'));
  hilfe.wechseln('erfassen');
  hilfe.geaendert(true);
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
