import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Chat } from './chat';
import {
  ChatEreignis,
  ChatFehler,
  ChatPort,
  GespeicherteNachricht,
  GespraechKopf,
} from './chat-port';

class ChatAttrappe extends ChatPort {
  fragen_: {
    frage: string;
    gespraechId: string | null;
    strom: Subject<ChatEreignis>;
    abgemeldet: boolean;
  }[] = [];
  liste: GespraechKopf[] = [];
  listeAbrufe = 0;
  listeFehler = false;
  verlauf = new Map<string, GespeicherteNachricht[]>();

  fragen(frage: string, gespraechId: string | null): Observable<ChatEreignis> {
    const strom = new Subject<ChatEreignis>();
    const eintrag = { frage, gespraechId, strom, abgemeldet: false };
    this.fragen_.push(eintrag);
    return new Observable<ChatEreignis>((abonnent) => {
      const abo = strom.subscribe(abonnent);
      return () => {
        eintrag.abgemeldet = true;
        abo.unsubscribe();
      };
    });
  }
  gespraeche(): Observable<GespraechKopf[]> {
    this.listeAbrufe++;
    return this.listeFehler ? throwError(() => new ChatFehler('ALLGEMEIN')) : of(this.liste);
  }
  gespraech(id: string): Observable<GespeicherteNachricht[]> {
    const n = this.verlauf.get(id);
    return n ? of(n) : throwError(() => new ChatFehler('NICHT_GEFUNDEN'));
  }
}

describe('Chat (Req 6.1–6.3)', () => {
  let fixture: ComponentFixture<Chat>;
  let port: ChatAttrappe;
  let element: HTMLElement;
  const t = TEXTE.chat;

  async function oeffnen() {
    fixture = TestBed.createComponent(Chat);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  beforeEach(async () => {
    port = new ChatAttrappe();
    port.liste = [
      { id: 'g1', titel: 'Offene Rechnungen im November', geaendert: '2026-11-13T23:30:00Z' },
      { id: 'g2', titel: 'Fristen 2027', geaendert: '2026-11-01T08:00:00Z' },
    ];
    TestBed.configureTestingModule({
      imports: [Chat],
      providers: [provideRouter([]), { provide: ChatPort, useValue: port }],
    });
    await oeffnen();
  });

  const feld = () => element.querySelector<HTMLTextAreaElement>('textarea[name=frage]')!;
  const sendenKnopf = () =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === t.senden,
    )!;
  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    );
  const antworten = () =>
    [...element.querySelectorAll('[data-test=antwort] .inhalt')].map((e) => e.textContent);
  const hinweis = () => element.querySelector('[data-test=hinweis]')!.textContent!.trim();

  async function eingeben(text: string) {
    feld().value = text;
    feld().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function taste(key: string, shiftKey = false) {
    const e = new KeyboardEvent('keydown', { key, shiftKey, cancelable: true });
    feld().dispatchEvent(e);
    await fixture.whenStable();
    return e;
  }

  async function senden(text: string) {
    await eingeben(text);
    sendenKnopf().click();
    await fixture.whenStable();
    return port.fragen_.at(-1)!;
  }

  async function ereignis(e: ChatEreignis) {
    port.fragen_.at(-1)!.strom.next(e);
    await fixture.whenStable();
  }

  it('zeigt die letzten Gespräche mit Titel und Datum TT.MM.JJJJ', () => {
    const eintraege = [...element.querySelectorAll('[data-test=gespraech]')];
    expect(eintraege.map((e) => e.querySelector('.titel')!.textContent)).toEqual([
      'Offene Rechnungen im November',
      'Fristen 2027',
    ]);
    // 23:30 UTC ist in Zürich schon der nächste Tag
    expect(eintraege[0].querySelector('.datum')!.textContent).toBe('14.11.2026');
    expect(eintraege[1].querySelector('.datum')!.textContent).toBe('01.11.2026');
  });

  it('zeigt die Antwort fortlaufend als reinen Text und sperrt Senden, solange gestreamt wird', async () => {
    const frage = await senden('  Welche Rechnungen sind offen?  ');
    expect(frage.frage).toBe('Welche Rechnungen sind offen?');
    expect(frage.gespraechId).toBeNull();
    expect(feld().value).toBe('');
    expect(hinweis()).toBe(t.wartet);
    await eingeben('Noch eine Frage');
    expect(sendenKnopf().disabled).toBe(true);

    await ereignis({ typ: 'text', delta: 'Zwei Rechnungen:\n' });
    expect(antworten()).toEqual(['Zwei Rechnungen:\n']);
    await ereignis({ typ: 'text', delta: "<b>CHF 1'234.50</b>" });
    expect(antworten()).toEqual(["Zwei Rechnungen:\n<b>CHF 1'234.50</b>"]);
    // kein HTML: das Tag steht als Text da
    expect(element.querySelector('[data-test=antwort] b')).toBeNull();

    await ereignis({ typ: 'fertig', gespraechId: 'g9' });
    port.fragen_.at(-1)!.strom.complete();
    await fixture.whenStable();
    expect(sendenKnopf().disabled).toBe(false);
    expect(knopf(t.abbrechen)).toBeUndefined();

    // die nächste Frage setzt das Gespräch fort
    const zweite = await senden('Und im Oktober?');
    expect(zweite.gespraechId).toBe('g9');
  });

  it('sendet mit Enter und beginnt mit Umschalt+Enter keine neue Frage', async () => {
    await eingeben('Erste Zeile');
    const umschalt = await taste('Enter', true);
    expect(umschalt.defaultPrevented).toBe(false);
    expect(port.fragen_).toHaveLength(0);
    const enter = await taste('Enter');
    expect(enter.defaultPrevented).toBe(true);
    expect(port.fragen_.map((f) => f.frage)).toEqual(['Erste Zeile']);
  });

  it('leert das Feld auch ohne Änderungserkennung zwischen Eingabe und Enter', async () => {
    feld().value = 'Schnell getippt';
    feld().dispatchEvent(new Event('input'));
    feld().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    await fixture.whenStable();
    expect(port.fragen_.map((f) => f.frage)).toEqual(['Schnell getippt']);
    expect(feld().value).toBe('');
  });

  it('sendet keine leere Frage und keine über 2000 Zeichen', async () => {
    await eingeben('   ');
    expect(sendenKnopf().disabled).toBe(true);
    await eingeben('x'.repeat(2001));
    expect(sendenKnopf().disabled).toBe(true);
    await taste('Enter');
    expect(port.fragen_).toHaveLength(0);
  });

  it('zeigt den Werkzeug-Hinweis bis zum ersten Text (Req 6.2)', async () => {
    await senden('Wie viel haben wir eingenommen?');
    await ereignis({ typ: 'werkzeug', name: 'kennzahl' });
    expect(hinweis()).toBe('zähle Belege …');
    await ereignis({ typ: 'werkzeug', name: 'neues_werkzeug' });
    expect(hinweis()).toBe('arbeite …');
    await ereignis({ typ: 'text', delta: 'CHF' });
    expect(hinweis()).toBe('');
    await ereignis({ typ: 'werkzeug', name: 'dokument_zeigen' });
    expect(hinweis()).toBe('lese Dokument …');
    await ereignis({ typ: 'text', delta: ' 100.00' });
    expect(hinweis()).toBe('');
  });

  it('macht nur Quellen auf /dokument/<id> zu Links, Datenstand und andere Links nicht', async () => {
    await senden('Quellen?');
    await ereignis({
      typ: 'quellen',
      quellen: [
        { titel: 'Rechnung 2026-004', link: '/dokument/d1' },
        { titel: 'Fremde Seite', link: 'https://example.invalid/dokument/d1' },
        { titel: 'Andere Seite', link: '/verwaltung/protokoll' },
        { titel: 'Pfad mit Punkten', link: '/dokument/../export' },
        { datenstand: { zeitpunkt: '2026-11-14T08:00:00Z', anzahl: 1234 } },
      ],
    });
    const quellen = [...element.querySelectorAll('[data-test=quelle]')];
    expect(quellen.map((q) => q.tagName)).toEqual(['A', 'SPAN', 'SPAN', 'SPAN', 'SPAN']);
    expect(quellen[0].getAttribute('href')).toBe('/dokument/d1');
    expect(quellen[4].textContent).toBe("Datenstand: 14.11.2026 09:00, 1'234 Belege");
  });

  it('zeigt die Meldung aus fehler und übernimmt die gespraechId', async () => {
    await senden('Frage');
    await ereignis({ typ: 'text', delta: 'Teil' });
    await ereignis({
      typ: 'fehler',
      meldung: 'Der Chat ist im Moment nicht verfügbar.',
      gespraechId: 'g5',
    });
    port.fragen_.at(-1)!.strom.complete();
    await fixture.whenStable();
    expect(element.querySelector('[data-test=antwort] [role=alert]')!.textContent).toBe(
      'Der Chat ist im Moment nicht verfügbar.',
    );
    expect(antworten()).toEqual(['Teil']);
    expect((await senden('Nochmals')).gespraechId).toBe('g5');
  });

  it('zeigt bei 429 die Uhrzeit, ab der es weitergeht, und legt die Frage zurück ins Feld', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-14T08:00:00Z'));
    try {
      await senden('Frage 101');
      port.fragen_.at(-1)!.strom.error(new ChatFehler('ZU_VIELE', 1200));
      await fixture.whenStable();
      expect(element.querySelector('.gespraech > [role=alert]')!.textContent).toBe(
        'Du hast in dieser Stunde 100 Fragen gestellt. Ab 09:20 geht es weiter.',
      );
      expect(feld().value).toBe('Frage 101');
      expect(element.querySelectorAll('[data-test=frage]')).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('zeigt bei 429 während einer anderen laufenden Frage einen eigenen Text', async () => {
    await senden('Frage');
    port.fragen_.at(-1)!.strom.error(new ChatFehler('ZU_VIELE', 5));
    await fixture.whenStable();
    expect(element.querySelector('.gespraech > [role=alert]')!.textContent).toBe(t.laufend);
  });

  it('bricht mit «Abbrechen» ab und behält den Teil der Antwort', async () => {
    const frage = await senden('Lange Frage');
    await ereignis({ typ: 'text', delta: 'Angefangen' });
    knopf(t.abbrechen)!.click();
    await fixture.whenStable();
    expect(frage.abgemeldet).toBe(true);
    expect(antworten()).toEqual(['Angefangen']);
    expect(element.querySelector('[data-test=antwort]')!.textContent).toContain(t.abgebrochen);
    expect(knopf(t.abbrechen)).toBeUndefined();
  });

  it('bricht beim Verlassen der Seite ab', async () => {
    const frage = await senden('Frage');
    fixture.destroy();
    expect(frage.abgemeldet).toBe(true);
  });

  it('öffnet ein Gespräch, setzt es fort und beginnt ein neues', async () => {
    port.verlauf.set('g1', [
      {
        rolle: 'BENUTZER',
        inhalt: 'Welche Rechnungen sind offen?',
        quellen: [],
        status: null,
        zeit: '',
      },
      {
        rolle: 'ASSISTENT',
        inhalt: 'Zwei.',
        quellen: [{ titel: 'Rechnung 2026-004', link: '/dokument/d1' }],
        status: 'OK',
        zeit: '',
      },
    ]);
    (element.querySelector('[data-test=gespraech]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(antworten()).toEqual(['Zwei.']);
    expect(element.querySelector('[data-test=quelle]')!.getAttribute('href')).toBe('/dokument/d1');
    expect(element.querySelector('[data-test=gespraech]')!.getAttribute('aria-current')).toBe(
      'true',
    );
    expect((await senden('Und die dritte?')).gespraechId).toBe('g1');
    await ereignis({ typ: 'fertig', gespraechId: 'g1' });
    port.fragen_.at(-1)!.strom.complete();
    await fixture.whenStable();

    knopf(t.neuesGespraech)!.click();
    await fixture.whenStable();
    expect(antworten()).toEqual([]);
    expect((await senden('Neu')).gespraechId).toBeNull();
  });

  it('meldet ein Gespräch, das es nicht mehr gibt', async () => {
    (element.querySelectorAll('[data-test=gespraech]')[1] as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(element.querySelector('.gespraech > [role=alert]')!.textContent).toBe(
      t.fehler['NICHT_GEFUNDEN'],
    );
  });

  it('lädt die Liste nach jeder beendeten Frage neu', async () => {
    expect(port.listeAbrufe).toBe(1);
    await senden('Frage');
    await ereignis({ typ: 'fertig', gespraechId: 'g3' });
    port.fragen_.at(-1)!.strom.complete();
    await fixture.whenStable();
    expect(port.listeAbrufe).toBe(2);
  });

  it('sperrt Gesprächsliste und «Neues Gespräch», solange gestreamt wird', async () => {
    await senden('Frage');
    expect(knopf(t.neuesGespraech)!.disabled).toBe(true);
    expect((element.querySelector('[data-test=gespraech]') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  it('meldet, wenn die Liste nicht geladen werden kann', async () => {
    port.listeFehler = true;
    fixture.destroy();
    await oeffnen();
    expect(element.querySelector('.gespraeche [role=alert]')!.textContent).toBe(t.fehlerListe);
  });
});
