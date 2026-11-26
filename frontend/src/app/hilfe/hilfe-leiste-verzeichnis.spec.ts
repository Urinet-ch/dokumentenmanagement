import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { signal } from '@angular/core';
import { HilfeLeisteAnsicht } from './hilfe-leiste';
import { HilfeLeiste } from './hilfe-leiste.service';

describe('Hilfe-Leiste, Inhaltsverzeichnis (Spec 07, Req 4.7)', () => {
  function leiste() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AnmeldungService, useValue: { angemeldet: signal(false), benutzer: signal(null) } },
      ],
    });
    const fixture = TestBed.createComponent(HilfeLeisteAnsicht);
    fixture.detectChanges();
    return fixture;
  }

  it('ohne gewählten Abschnitt ist das Verzeichnis aufgeklappt (Knopf «Abschnitte», aria-expanded=true)', () => {
    const el = leiste().nativeElement as HTMLElement;
    const knopf = el.querySelector<HTMLButtonElement>('[data-test=hilfe-verzeichnis-knopf]')!;
    expect(knopf.textContent).toContain('Abschnitte');
    expect(knopf.getAttribute('aria-expanded')).toBe('true');
    expect(knopf.getAttribute('aria-controls')).toBe('hilfe-verzeichnis');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(false);
  });

  it('klappt mit dem Knopf zu und wieder auf', () => {
    const fixture = leiste();
    const el = fixture.nativeElement as HTMLElement;
    const knopf = el.querySelector<HTMLButtonElement>('[data-test=hilfe-verzeichnis-knopf]')!;
    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(true);
    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(false);
  });

  it('setzt beim erneuten Öffnen den Ausgangszustand passend zum aktuellen Abschnitt zurück', () => {
    const fixture = leiste();
    const hilfe = TestBed.inject(HilfeLeiste);
    const el = fixture.nativeElement as HTMLElement;
    const knopf = el.querySelector<HTMLButtonElement>('[data-test=hilfe-verzeichnis-knopf]')!;

    hilfe.anker.set('erste-schritte');
    hilfe.offen.set(true);
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('false');

    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('true');

    hilfe.offen.set(false);
    fixture.detectChanges();
    hilfe.offen.set(true);
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('false');

    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('true');

    hilfe.offen.set(false);
    fixture.detectChanges();
    hilfe.anker.set(null);
    hilfe.offen.set(true);
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('true');
  });
});
