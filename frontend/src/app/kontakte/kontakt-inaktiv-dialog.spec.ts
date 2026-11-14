import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DETAIL, KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';
import { KontaktInaktivDialog } from './kontakt-inaktiv-dialog';

const T = TEXTE.kontakte;

describe('Kontakte: Inaktiv-Dialog (Req 17.6, 17.12, 17.17)', () => {
  let port: KontakteAttrappe;
  let fixture: ComponentFixture<KontaktInaktivDialog>;
  let element: HTMLElement;
  let erledigt: number;
  let veraltet: number;
  let geschlossen: number;

  beforeEach(async () => {
    port = new KontakteAttrappe();
    erledigt = veraltet = geschlossen = 0;
    TestBed.configureTestingModule({ providers: [{ provide: KontaktePort, useValue: port }] });
    fixture = TestBed.createComponent(KontaktInaktivDialog);
    fixture.componentRef.setInput('kontakt', { ...DETAIL, name: '<b>Muster</b>' });
    fixture.componentInstance.erledigt.subscribe(() => erledigt++);
    fixture.componentInstance.veraltet.subscribe(() => veraltet++);
    fixture.componentInstance.geschlossen.subscribe(() => geschlossen++);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  const el = (test: string) => element.querySelector<HTMLElement>(`[data-test=${test}]`)!;
  async function klick(test: string) {
    el(test).click();
    await fixture.whenStable();
  }
  async function grund(wert: string) {
    const feld = el('inaktiv-grund') as HTMLTextAreaElement;
    feld.value = wert;
    feld.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  it('fragt zurück, nennt den Namen nur als Text und sendet nichts vor der Bestätigung', () => {
    expect(element.querySelector('h2')!.textContent).toBe(T.inaktivTitel('<b>Muster</b>'));
    expect(element.querySelector('b')).toBeNull();
    expect(element.textContent).toContain(T.inaktivText);
    expect(port.aktionen).toEqual([]);
  });

  it('sendet ohne Grund null mit If-Match der Version', async () => {
    await klick('inaktiv-ausfuehren');
    expect(port.aktionen).toEqual([
      { art: 'inaktiv', id: 'k1', version: 3, daten: { grund: null } },
    ]);
    expect(erledigt).toBe(1);
  });

  it('sendet den Grund ohne Leerraum am Rand', async () => {
    await grund('  Falscher Eintrag  ');
    await klick('inaktiv-ausfuehren');
    expect(port.aktionen[0].daten).toEqual({ grund: 'Falscher Eintrag' });
  });

  it('sperrt einen Grund über 1000 Zeichen', async () => {
    await grund('x'.repeat(1001));
    expect(el('grund-fehler').textContent).toContain(T.grundZuLang);
    expect((el('inaktiv-ausfuehren') as HTMLButtonElement).disabled).toBe(true);
    await grund('x'.repeat(1000));
    expect((el('inaktiv-ausfuehren') as HTMLButtonElement).disabled).toBe(false);
  });

  it('verhindert doppeltes Absenden', async () => {
    port.aktionAntwort = () => new Subject();
    await klick('inaktiv-ausfuehren');
    await klick('inaktiv-ausfuehren');
    expect(port.aktionen).toHaveLength(1);
    expect((el('inaktiv-abbrechen') as HTMLButtonElement).disabled).toBe(true);
  });

  it('meldet 409 verständlich', async () => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 409 }));
    await klick('inaktiv-ausfuehren');
    expect(el('aktion-fehler').textContent).toBe(T.konflikt.inaktiv);
    expect(erledigt).toBe(0);
  });

  it.each([412, 428])('meldet bei Status %d «veraltet»', async (status) => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status }));
    await klick('inaktiv-ausfuehren');
    expect(veraltet).toBe(1);
  });

  it('schliesst mit «Abbrechen» und mit Escape', async () => {
    await klick('inaktiv-abbrechen');
    el('dialog').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(geschlossen).toBe(2);
    expect(port.aktionen).toEqual([]);
  });
});
