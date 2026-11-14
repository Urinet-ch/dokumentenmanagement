import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DETAIL, KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';
import { KontaktBearbeiten } from './kontakt-bearbeiten';

const T = TEXTE.kontakte;

describe('Kontakte: Bearbeiten (Req 17.6)', () => {
  let port: KontakteAttrappe;
  let fixture: ComponentFixture<KontaktBearbeiten>;
  let element: HTMLElement;
  let gespeichert: number;
  let veraltet: number;
  let abgebrochen: number;

  beforeEach(async () => {
    port = new KontakteAttrappe();
    gespeichert = veraltet = abgebrochen = 0;
    TestBed.configureTestingModule({ providers: [{ provide: KontaktePort, useValue: port }] });
    fixture = TestBed.createComponent(KontaktBearbeiten);
    fixture.componentRef.setInput('kontakt', DETAIL);
    fixture.componentInstance.gespeichert.subscribe(() => gespeichert++);
    fixture.componentInstance.veraltet.subscribe(() => veraltet++);
    fixture.componentInstance.abgebrochen.subscribe(() => abgebrochen++);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  const feld = (name: string) =>
    element.querySelector<HTMLInputElement>(`[data-test=feld-${name}]`)!;
  const el = (test: string) => element.querySelector<HTMLElement>(`[data-test=${test}]`)!;
  async function eingeben(name: string, wert: string) {
    const f = feld(name);
    f.value = wert;
    f.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }
  async function speichern() {
    el('speichern').click();
    await fixture.whenStable();
  }

  it('füllt das Formular mit den Angaben des Details', () => {
    expect(feld('name').value).toBe('Muster Garage AG');
    expect(feld('ort').value).toBe('Altdorf');
    expect(feld('iban').value).toBe('CH0000000000000000000');
    expect(feld('vorname').value).toBe('');
    expect((el('feld-typ') as unknown as HTMLSelectElement).value).toBe('ORGANISATION');
  });

  it('sendet nur geänderte Felder mit If-Match der Version', async () => {
    await eingeben('ort', ' Luzern ');
    await eingeben('telefon', '');
    await speichern();
    expect(port.aktionen).toEqual([
      { art: 'aendern', id: 'k1', version: 3, daten: { ort: 'Luzern', telefon: '' } },
    ]);
    expect(gespeichert).toBe(1);
  });

  it('sendet nichts, wenn kein Feld geändert wurde', async () => {
    await speichern();
    expect(port.aktionen).toEqual([]);
    expect(el('formular-meldung').textContent).toBe(T.keineAenderung);
  });

  it('prüft Name und Höchstlängen wie das Archiv, bevor etwas gesendet wird', async () => {
    await eingeben('name', '  ');
    await eingeben('plz', 'x'.repeat(21));
    await eingeben('kategorie', 'x'.repeat(101));
    await speichern();
    expect(el('fehler-name').textContent).toBe(T.pruefung.name);
    expect(el('fehler-plz').textContent).toBe(T.pruefung.zuLang(20));
    expect(el('fehler-kategorie').textContent).toBe(T.pruefung.zuLang(100));
    expect(feld('name').getAttribute('aria-invalid')).toBe('true');
    expect(port.aktionen).toEqual([]);
    await eingeben('name', 'Neuer Name');
    await eingeben('plz', '6000');
    await eingeben('kategorie', 'Kunde');
    await speichern();
    expect(port.aktionen).toHaveLength(1);
  });

  it('zeigt einen Serverfehler 400 beim Formular und bleibt offen', async () => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 400 }));
    await eingeben('ort', 'Luzern');
    await speichern();
    expect(el('formular-meldung').textContent).toBe(T.anfrage.aendern);
    expect(gespeichert).toBe(0);
  });

  it('meldet 409 (inaktiv) verständlich', async () => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status: 409 }));
    await eingeben('ort', 'Luzern');
    await speichern();
    expect(el('formular-meldung').textContent).toBe(T.konflikt.aendern);
  });

  it.each([412, 428])('meldet bei Status %d «veraltet»', async (status) => {
    port.aktionAntwort = () => throwError(() => new HttpErrorResponse({ status }));
    await eingeben('ort', 'Luzern');
    await speichern();
    expect(veraltet).toBe(1);
    expect(feld('ort').value).toBe('Luzern');
  });

  it('verhindert doppeltes Absenden', async () => {
    port.aktionAntwort = () => new Subject();
    await eingeben('ort', 'Luzern');
    await speichern();
    await speichern();
    expect(port.aktionen).toHaveLength(1);
    expect((el('speichern') as HTMLButtonElement).disabled).toBe(true);
  });

  it('bricht ab, ohne etwas zu senden', async () => {
    await eingeben('ort', 'Luzern');
    el('bearbeiten-abbrechen').click();
    expect(abgebrochen).toBe(1);
    expect(port.aktionen).toEqual([]);
  });
});
