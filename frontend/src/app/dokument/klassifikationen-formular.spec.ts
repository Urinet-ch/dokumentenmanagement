import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { KlassifikationFeld } from './klassifikationen';
import { KlassifikationenFormular } from './klassifikationen-formular';

const FELDER: KlassifikationFeld[] = [
  {
    code: 'fachgebiet',
    name: 'Fachgebiet',
    aktiv: true,
    mehrfach: false,
    pflicht: true,
    standardWert: null,
    werte: [
      { code: 'technik', name: 'Technik', aktiv: true },
      { code: 'alt', name: 'Alte Kategorie', aktiv: false },
    ],
  },
  {
    code: 'verband',
    name: 'Verband',
    aktiv: true,
    mehrfach: true,
    pflicht: false,
    standardWert: null,
    werte: [{ code: 'vhf', name: 'VHF', aktiv: true }],
  },
];

describe('KlassifikationenFormular', () => {
  let fixture: ComponentFixture<KlassifikationenFormular>;
  let element: HTMLElement;
  let aenderungen: Readonly<Record<string, readonly string[]>>[];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [KlassifikationenFormular] });
    fixture = TestBed.createComponent(KlassifikationenFormular);
    fixture.componentRef.setInput('kennung', 'test');
    fixture.componentRef.setInput('felder', FELDER);
    element = fixture.nativeElement as HTMLElement;
    aenderungen = [];
    fixture.componentInstance.geaendert.subscribe((werte) => aenderungen.push(werte));
  });

  it('zeigt gespeicherte inaktive Werte an und markiert ungueltige Klassifikationen', () => {
    fixture.componentRef.setInput('werte', { fachgebiet: ['alt'] });
    fixture.componentRef.setInput('ungueltigeCodes', ['fachgebiet']);
    fixture.detectChanges();

    const select = element.querySelector<HTMLSelectElement>('#test-fachgebiet')!;
    expect(select.value).toBe('alt');
    expect(select.querySelector('option[value="alt"]')?.textContent?.trim()).toBe('Alte Kategorie');
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('[data-test="klassifikation-alt"]')?.textContent).toContain('alt');
  });

  it('aktualisiert eine Mehrfachauswahl ohne Verlust anderer Klassifikationswerte', () => {
    fixture.componentRef.setInput('werte', { fachgebiet: ['technik'] });
    fixture.detectChanges();

    const checkbox = element.querySelector<HTMLInputElement>('input[type=checkbox][value="vhf"]')!;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));

    expect(aenderungen).toEqual([{ fachgebiet: ['technik'], verband: ['vhf'] }]);
  });

  it('besteht die axe-Prüfung für Klassifikationsfelder', async () => {
    fixture.detectChanges();

    const ergebnis = await axe.run(element, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    });

    expect(ergebnis.violations).toEqual([]);
  });
});
