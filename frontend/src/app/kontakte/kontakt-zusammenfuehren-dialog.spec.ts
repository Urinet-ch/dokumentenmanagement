import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { Kontakt, KontaktZusammengefuehrt } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DETAIL, KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';
import { KontaktZusammenfuehrenDialog, Zusammengefuehrt } from './kontakt-zusammenfuehren-dialog';

const T = TEXTE.kontakte;
const ZIEL: Kontakt = { id: 'k2', name: 'Ziel AG', plz: '6000', ort: 'Luzern' };
const warten = (ms: number) => new Promise((fertig) => setTimeout(fertig, ms));

describe('Kontakte: Zusammenführen-Dialog (Req 17.6, 17.7, 17.17)', () => {
  let port: KontakteAttrappe;
  let fixture: ComponentFixture<KontaktZusammenfuehrenDialog>;
  let element: HTMLElement;
  let erledigt: Zusammengefuehrt[];
  let veraltet: number;
  let geschlossen: number;

  beforeEach(async () => {
    port = new KontakteAttrappe();
    port.suchenTreffer = [{ id: 'k1', name: 'Muster Garage AG' }, ZIEL];
    erledigt = [];
    veraltet = 0;
    geschlossen = 0;
    TestBed.configureTestingModule({ providers: [{ provide: KontaktePort, useValue: port }] });
    fixture = TestBed.createComponent(KontaktZusammenfuehrenDialog);
    fixture.componentRef.setInput('kontakt', DETAIL);
    fixture.componentInstance.erledigt.subscribe((z) => erledigt.push(z));
    fixture.componentInstance.veraltet.subscribe(() => veraltet++);
    fixture.componentInstance.geschlossen.subscribe(() => geschlossen++);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  const el = (test: string) => element.querySelector<HTMLElement>(`[data-test=${test}]`);
  const text = (test: string) => el(test)!.textContent!.replace(/\s+/g, ' ').trim();
  async function klick(test: string) {
    el(test)!.click();
    await fixture.whenStable();
  }
  async function eingeben(test: string, wert: string) {
    const feld = el(test) as HTMLInputElement | HTMLTextAreaElement;
    feld.value = wert;
    feld.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }
  async function zielWaehlen() {
    await eingeben('ziel-suche', 'Ziel');
    await warten(350);
    await fixture.whenStable();
    await klick('ziel-vorschlag');
  }

  it('zeigt Titel mit dem Namen der Quelle und fokussiert den Dialog', () => {
    expect(el('dialog')!.getAttribute('role')).toBe('dialog');
    expect(el('dialog')!.getAttribute('aria-modal')).toBe('true');
    expect(element.querySelector('h2')!.textContent).toBe(
      T.zusammenfuehrenTitel('Muster Garage AG'),
    );
    expect(document.activeElement).toBe(element.querySelector('h2'));
  });

  it('sucht das Ziel erst ab 2 Zeichen und 300 ms nach der Eingabe, ohne die Quelle', async () => {
    await eingeben('ziel-suche', 'Z');
    await warten(350);
    expect(port.suchenAnfragen).toEqual([]);
    await eingeben('ziel-suche', 'Zi');
    await eingeben('ziel-suche', 'Zie');
    await warten(100);
    expect(port.suchenAnfragen).toEqual([]);
    await warten(300);
    await fixture.whenStable();
    expect(port.suchenAnfragen).toEqual(['Zie']);
    const vorschlaege = [...element.querySelectorAll('[data-test=ziel-vorschlag]')].map((b) =>
      b.textContent!.trim(),
    );
    expect(vorschlaege).toEqual(['Ziel AG, 6000 Luzern']);
  });

  it('sagt, wenn kein aktiver Kontakt gefunden wird', async () => {
    port.suchenTreffer = [];
    await eingeben('ziel-suche', 'Nichts');
    await warten(350);
    await fixture.whenStable();
    expect(text('ziel-keine')).toBe(T.zielKeine);
  });

  it('verlangt Ziel und Grund (1 bis 1000 Zeichen), bevor es weitergeht', async () => {
    await klick('weiter');
    expect(text('auswahl-fehler')).toBe(T.zielFehlt);
    await zielWaehlen();
    await klick('weiter');
    expect(text('auswahl-fehler')).toBe(T.grundFehlt);
    await eingeben('zusammenfuehren-grund', '   ');
    await klick('weiter');
    expect(text('auswahl-fehler')).toBe(T.grundFehlt);
    await eingeben('zusammenfuehren-grund', 'x'.repeat(1001));
    await klick('weiter');
    expect(text('auswahl-fehler')).toBe(T.grundZuLang);
    expect(el('rueckfrage-text')).toBeNull();
    expect(port.aktionen).toEqual([]);
  });

  it('fragt mit Ziel, Zahlen und «nicht umkehrbar» zurück und sendet noch nichts', async () => {
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', 'Dublette aus dem Import');
    await klick('weiter');
    expect(element.querySelector('h2')!.textContent).toBe(T.rueckfrageTitel);
    expect(text('rueckfrage-text')).toBe(
      '«Muster Garage AG» wird in «Ziel AG» zusammengeführt und danach inaktiv.',
    );
    expect(text('rueckfrage-zahlen')).toBe(
      'Es wandern 7 Dokumente, 2 Verkaufschancen und 1 Korrekturbeispiel zum Zielkontakt.',
    );
    expect(text('rueckfrage-grund')).toBe('Grund: Dublette aus dem Import');
    expect(text('nicht-umkehrbar')).toBe(T.nichtUmkehrbar);
    expect(port.aktionen).toEqual([]);
    expect(erledigt).toEqual([]);
  });

  it('geht von der Rückfrage zurück zur Auswahl und behält Ziel und Grund', async () => {
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', 'Grund');
    await klick('weiter');
    await klick('rueckfrage-zurueck');
    expect(text('ziel-gewaehlt')).toContain('Ziel AG');
    expect((el('zusammenfuehren-grund') as HTMLTextAreaElement).value).toBe('Grund');
  });

  it('sendet erst nach «Zusammenführen» mit If-Match der Version und meldet das Ergebnis', async () => {
    const antwort = new Subject<KontaktZusammengefuehrt>();
    port.aktionAntwort = () => antwort;
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', '  Dublette  ');
    await klick('weiter');
    await klick('zusammenfuehren-ausfuehren');
    await klick('zusammenfuehren-ausfuehren');
    expect(port.aktionen).toEqual([
      { art: 'zusammenfuehren', id: 'k1', version: 3, daten: { ziel: 'k2', grund: 'Dublette' } },
    ]);
    expect((el('zusammenfuehren-ausfuehren') as HTMLButtonElement).disabled).toBe(true);
    expect((el('rueckfrage-abbrechen') as HTMLButtonElement).disabled).toBe(true);
    antwort.next({ ziel: 'k2', dokumente: 7, verkaufschancen: 2, korrekturbeispiele: 1 });
    expect(erledigt).toEqual([
      {
        ziel: { id: 'k2', name: 'Ziel AG' },
        zahlen: { ziel: 'k2', dokumente: 7, verkaufschancen: 2, korrekturbeispiele: 1 },
      },
    ]);
  });

  it('verhindert doppeltes Absenden', async () => {
    port.aktionAntwort = () => new Subject();
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', 'Grund');
    await klick('weiter');
    await klick('zusammenfuehren-ausfuehren');
    await klick('zusammenfuehren-ausfuehren');
    expect(port.aktionen).toHaveLength(1);
  });

  it.each([
    [409, T.konflikt.zusammenfuehren],
    [400, T.anfrage.zusammenfuehren],
    [0, T.fehlerVerbindung],
  ])('meldet Status %d verständlich und erlaubt einen neuen Versuch', async (status, meldung) => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status }));
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', 'Grund');
    await klick('weiter');
    await klick('zusammenfuehren-ausfuehren');
    expect(text('aktion-fehler')).toBe(meldung);
    expect((el('zusammenfuehren-ausfuehren') as HTMLButtonElement).disabled).toBe(false);
    expect(erledigt).toEqual([]);
  });

  it.each([412, 428])(
    'meldet bei Status %d «veraltet», damit das Detail neu geladen wird',
    async (status) => {
      port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status }));
      await zielWaehlen();
      await eingeben('zusammenfuehren-grund', 'Grund');
      await klick('weiter');
      await klick('zusammenfuehren-ausfuehren');
      expect(veraltet).toBe(1);
      expect(el('aktion-fehler')).toBeNull();
    },
  );

  it('meldet einen Fehler beim Zählen der Verweise und bleibt in der Auswahl', async () => {
    port.verweiseAntwort = throwError(() => new HttpErrorResponse({ status: 500 }));
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', 'Grund');
    await klick('weiter');
    expect(text('aktion-fehler')).toBe(T.fehlerAllgemein);
    expect(el('rueckfrage-text')).toBeNull();
  });

  it('schliesst mit Escape und mit «Abbrechen», ohne etwas zu senden', async () => {
    el('dialog')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await klick('zusammenfuehren-abbrechen');
    expect(geschlossen).toBe(2);
    expect(port.aktionen).toEqual([]);
  });

  it('zeigt Namen mit Markup in der Rückfrage nur als Text', async () => {
    port.suchenTreffer = [{ id: 'k2', name: '<img src=x onerror=alert(1)>', plz: '1', ort: 'O' }];
    port.verweiseAntwort = of({ dokumente: 1, verkaufschancen: 0, korrekturbeispiele: 0 });
    await zielWaehlen();
    await eingeben('zusammenfuehren-grund', '<b>Grund</b>');
    await klick('weiter');
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('b')).toBeNull();
    expect(text('rueckfrage-text')).toContain('<img src=x onerror=alert(1)>');
    expect(text('rueckfrage-zahlen')).toBe(
      'Es wandern 1 Dokument, 0 Verkaufschancen und 0 Korrekturbeispiele zum Zielkontakt.',
    );
  });
});
