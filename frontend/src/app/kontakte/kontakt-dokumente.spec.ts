import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { Dokumentart, Suchergebnis, Suchtreffer } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DOKUMENTE_GROESSE } from './kontakt-logik';
import { KontakteAttrappe } from './kontakte-attrappe';
import { KontaktePort } from './kontakte-port';
import { KontaktDokumente } from './kontakt-dokumente';

const T = TEXTE.kontakte;

class FehlerhafteArtenAttrappe extends KontakteAttrappe {
  override dokumentarten() {
    return throwError(() => new Error('synthetischer Fehler'));
  }
}

describe('KontaktDokumente', () => {
  let port: KontakteAttrappe;
  let fixture: ComponentFixture<KontaktDokumente>;
  let element: HTMLElement;

  async function starten(kontaktId = 'k1', kontaktePort = new KontakteAttrappe()) {
    port = kontaktePort;
    TestBed.configureTestingModule({
      imports: [KontaktDokumente],
      providers: [provideRouter([]), { provide: KontaktePort, useValue: port }],
    });
    fixture = TestBed.createComponent(KontaktDokumente);
    fixture.componentRef.setInput('kontaktId', kontaktId);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function ergebnis(treffer: Suchtreffer[], gesamt = 40): Suchergebnis {
    return { treffer, gesamt, seite: 0, groesse: DOKUMENTE_GROESSE };
  }

  function treffer(id: string, titel: string, art: string | null = 'QUITTUNG'): Suchtreffer {
    return {
      id,
      art,
      titel,
      eingang: '2026-11-08T09:30:00Z',
      status: 'ARCHIVIERT',
      vertraulichkeit: 'NORMAL',
      tags: [],
      auszug: [],
    };
  }

  async function klicken(test: string) {
    const knopf = element.querySelector<HTMLButtonElement>(`[data-test=${test}]`);
    if (!knopf) {
      throw new Error(`Knopf «${test}» fehlt`);
    }
    knopf.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('lädt die erste Seite des Kontakts', async () => {
    await starten();

    expect(port.dokumentAnfragen).toEqual([{ id: 'k1', seite: 0, groesse: DOKUMENTE_GROESSE }]);
    port.dokumentAntworten[0].next(ergebnis([treffer('d1', 'Rechnung 2026')]));
    fixture.detectChanges();

    expect(element.querySelector('[data-test=dokument]')?.textContent).toContain('Rechnung 2026');
    expect(
      element.querySelector<HTMLButtonElement>('[data-test=dokumente-vorherige]')?.disabled,
    ).toBe(true);
  });

  it('setzt beim Kontaktwechsel die Seite auf null zurück', async () => {
    await starten();
    port.dokumentAntworten[0].next(ergebnis([treffer('d1', 'Seite eins')]));
    fixture.detectChanges();
    await klicken('dokumente-naechste');
    expect(port.dokumentAnfragen.at(-1)).toEqual({
      id: 'k1',
      seite: 1,
      groesse: DOKUMENTE_GROESSE,
    });

    fixture.componentRef.setInput('kontaktId', 'k2');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(port.dokumentAnfragen.at(-1)).toEqual({
      id: 'k2',
      seite: 0,
      groesse: DOKUMENTE_GROESSE,
    });
  });

  it('verwirft die verspätete Antwort der Vorseite', async () => {
    await starten();
    const alteSeite = port.dokumentAntworten[0];
    alteSeite.next(ergebnis([treffer('alt', 'Veraltetes Ergebnis')]));
    fixture.detectChanges();
    await klicken('dokumente-naechste');

    port.dokumentAntworten[1].next(ergebnis([treffer('neu', 'Aktuelles Ergebnis')]));
    fixture.detectChanges();
    alteSeite.next(ergebnis([treffer('alt', 'Veraltetes Ergebnis')]));
    fixture.detectChanges();

    expect(element.textContent).toContain('Aktuelles Ergebnis');
    expect(element.textContent).not.toContain('Veraltetes Ergebnis');
  });

  it('blättert vor und zurück, aber nie vor Seite null', async () => {
    await starten();
    port.dokumentAntworten[0].next(ergebnis([treffer('d1', 'Seite eins')]));
    fixture.detectChanges();
    expect(
      element.querySelector<HTMLButtonElement>('[data-test=dokumente-vorherige]')?.disabled,
    ).toBe(true);

    await klicken('dokumente-naechste');
    port.dokumentAntworten[1].next(ergebnis([treffer('d2', 'Seite zwei')]));
    fixture.detectChanges();
    await klicken('dokumente-vorherige');

    expect(port.dokumentAnfragen.map((a) => a.seite)).toEqual([0, 1, 0]);
    expect(
      element.querySelector<HTMLButtonElement>('[data-test=dokumente-vorherige]')?.disabled,
    ).toBe(true);
  });

  it('zeigt einen Fehler und lädt dieselbe Seite erneut', async () => {
    await starten();
    port.dokumentAntworten[0].error(new Error('synthetischer Fehler'));
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(T.dokumenteFehler);

    const erneut = element.querySelector<HTMLButtonElement>('[data-test=dokumente-fehler] button')!;
    erneut.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(port.dokumentAnfragen).toEqual([
      { id: 'k1', seite: 0, groesse: DOKUMENTE_GROESSE },
      { id: 'k1', seite: 0, groesse: DOKUMENTE_GROESSE },
    ]);
    port.dokumentAntworten[1].next(ergebnis([treffer('d1', 'Erfolgreich geladen')]));
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')).toBeNull();
    expect(element.textContent).toContain('Erfolgreich geladen');
  });

  it('verwendet bei fehlerhafter Artenabfrage den Art-Code oder «ohne Art»', async () => {
    await starten('k1', new FehlerhafteArtenAttrappe());
    port.dokumentAntworten[0].next(
      ergebnis([treffer('d1', 'Unbekannte Art', 'ART_NEU'), treffer('d2', 'Keine Art', null)]),
    );
    fixture.detectChanges();

    expect(element.textContent).toContain('ART_NEU');
    expect(element.textContent).toContain(T.ohneArt);
  });
});
