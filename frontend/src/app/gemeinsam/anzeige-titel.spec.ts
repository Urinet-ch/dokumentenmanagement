import { TestBed } from '@angular/core/testing';
import { anzeigeTitel, TitelAnzeige } from './anzeige-titel';

describe('anzeigeTitel (Req 8.11, 14.9, 14.10)', () => {
  it('nimmt den Titel vor dem Vorschlag und dem Dateinamen', () => {
    expect(anzeigeTitel('Rechnung', 'Vorschlag', 'Scan.pdf')).toEqual({
      text: 'Rechnung',
      platzhalter: false,
      dateiname: null,
    });
    expect(anzeigeTitel(null, 'Vorschlag', 'Scan.pdf').text).toBe('Vorschlag');
    expect(anzeigeTitel('', '', 'Scan.pdf').platzhalter).toBe(true);
  });

  it('setzt den Dateinamen als Platzhalter, isoliert für Meldungen', () => {
    expect(anzeigeTitel(null, null, 'Scan_Rechnung-2026.pdf')).toEqual({
      text: 'Ohne Titel · \u2068Scan_Rechnung-2026.pdf\u2069',
      platzhalter: true,
      dateiname: 'Scan_Rechnung-2026.pdf',
    });
  });

  it('bleibt ohne Dateinamen bei «Ohne Titel»', () => {
    expect(anzeigeTitel(undefined, undefined, null)).toEqual({
      text: 'Ohne Titel',
      platzhalter: false,
      dateiname: null,
    });
  });
});

describe('TitelAnzeige', () => {
  function zeigen(titel: string | null, dateiname: string | null): HTMLElement {
    const fixture = TestBed.createComponent(TitelAnzeige);
    fixture.componentRef.setInput('anzeige', anzeigeTitel(titel, null, dateiname));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('zeigt den Platzhalter kursiv in <bdi>, mit Screenreader-Text und vollem Namen im title', () => {
    const name = 'שלום <b>fett</b>.pdf';
    const el = zeigen(null, name);
    const platzhalter = el.querySelector<HTMLElement>('[data-test=titel-platzhalter]')!;
    expect(platzhalter.title).toBe(name);
    expect(el.querySelector('bdi.dateiname')!.textContent).toBe(name);
    expect(el.querySelector('b')).toBeNull();
    expect(el.querySelector('[aria-hidden=true]')!.textContent).toBe(`Ohne Titel · ${name}`);
    expect(el.querySelector('.nur-screenreader')!.textContent).toBe(`Ohne Titel, Dateiname ${name}`);
  });

  it('zeigt einen Titel als reinen Text', () => {
    const el = zeigen('Rechnung', 'Scan.pdf');
    expect(el.textContent!.trim()).toBe('Rechnung');
    expect(el.querySelector('[data-test=titel-platzhalter]')).toBeNull();
  });
});
