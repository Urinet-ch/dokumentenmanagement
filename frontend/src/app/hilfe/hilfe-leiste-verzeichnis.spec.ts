import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { signal } from '@angular/core';
import { HilfeLeisteAnsicht } from './hilfe-leiste';

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

  it('startet zugeklappt: Knopf «Abschnitte» mit aria-expanded=false und verstecktem Verzeichnis', () => {
    const el = leiste().nativeElement as HTMLElement;
    const knopf = el.querySelector<HTMLButtonElement>('[data-test=hilfe-verzeichnis-knopf]')!;
    expect(knopf.textContent).toContain('Abschnitte');
    expect(knopf.getAttribute('aria-expanded')).toBe('false');
    expect(knopf.getAttribute('aria-controls')).toBe('hilfe-verzeichnis');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(true);
  });

  it('klappt mit dem Knopf auf und wieder zu', () => {
    const fixture = leiste();
    const el = fixture.nativeElement as HTMLElement;
    const knopf = el.querySelector<HTMLButtonElement>('[data-test=hilfe-verzeichnis-knopf]')!;
    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(false);
    knopf.click();
    fixture.detectChanges();
    expect(knopf.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector<HTMLElement>('#hilfe-verzeichnis')!.hidden).toBe(true);
  });
});
