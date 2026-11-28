import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Subscription, map, switchMap } from 'rxjs';
import { Symbol } from '../gemeinsam/symbol';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ChatBewertung } from './chat-bewertung';
import {
  ChatEreignis,
  ChatFehler,
  ChatModus,
  ChatPort,
  GespraechKopf,
  NachrichtStatus,
  Quelle,
} from './chat-port';
import {
  FRAGE_MAX,
  datenstandText,
  dokumentLink,
  fehlerText,
  istDatenstand,
  tag,
  werkzeugHinweis,
  wissenLink,
} from './chat-logik';

/** Eine Nachricht im Verlauf; `fehler` ist die Meldung zu einer Antwort, die nicht fertig wurde. */
export interface Nachricht {
  id?: string;
  analyseMoeglich?: boolean;
  entwurfId?: string;
  rolle: 'BENUTZER' | 'ASSISTENT';
  inhalt: string;
  quellen: Quelle[];
  status: NachrichtStatus | null;
  fehler: string | null;
}

/** Chip einer Quelle: Link nur auf `/dokument/<id>`, sonst ein Text ohne Link. */
interface Chip {
  text: string;
  link: string | null;
  art: 'dokument' | 'wissen' | 'datenstand';
}

/**
 * Seite «Chat» (Spec 02, Aufgabe 7.4; Req 6.1–6.3): Frage stellen, Antwort fortlaufend als reiner Text, Werkzeug-Hinweis,
 * Quellen-Chips und die letzten 20 eigenen Gespräche. Was der Chat sehen darf, entscheidet das Archiv (Req 5.1, 5.2).
 */
@Component({
  selector: 'app-chat',
  imports: [MatButtonModule, RouterLink, Symbol, ChatBewertung],
  templateUrl: './chat.html',
  styleUrl: './chat.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Chat {
  private readonly port = inject(ChatPort);
  protected readonly anmeldung = inject(AnmeldungService);

  protected readonly texte = TEXTE.chat;
  protected readonly modusTexte = TEXTE.chatModus;
  protected readonly neuLadenText = TEXTE.eingangskorb.neuLaden;
  protected readonly tag = tag;
  protected readonly frageMax = FRAGE_MAX;

  protected readonly mobil = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((z) => z.matches)),
    { initialValue: true },
  );
  private readonly listeGeoeffnet = signal<boolean | null>(null);
  protected readonly listeOffen = computed(() => this.listeGeoeffnet() ?? !this.mobil());

  protected readonly gespraeche = signal<GespraechKopf[] | null>(null);
  /** Wissen freigeschaltet und Recht A15 (Spec 04, Req 22.5): sonst keine Moduswahl und keine Gespräche im Modus WISSEN. */
  protected readonly wissen = computed(() => this.anmeldung.darf('A15'));
  protected readonly sichtbareGespraeche = computed(() => {
    const liste = this.gespraeche();
    return liste === null || this.wissen() ? liste : liste.filter((kopf) => kopf.modus !== 'WISSEN');
  });
  protected readonly listeFehler = signal(false);
  protected readonly gespraechId = signal<string | null>(null);
  protected readonly modus = signal<ChatModus>('ARCHIV');
  protected readonly nachrichten = signal<Nachricht[]>([]);
  protected readonly laedt = signal(false);
  protected readonly laeuft = signal(false);
  protected readonly hinweis = signal<string | null>(null);
  protected readonly eingabe = signal('');
  protected readonly fehler = signal<string | null>(null);
  protected readonly analyseFehler = signal<string | null>(null);
  protected readonly analyseSendet = signal<string | null>(null);
  /**
   * Die Ansicht zeigt Nachrichten, zu denen die Seite keine `gespraechId` kennt (erste Frage ohne `fertig` oder `fehler`
   * beendet, 404): Die nächste Frage beginnt beim Archiv ein neues Gespräch, darum leert die Seite dann die Ansicht.
   */
  protected readonly neuBeginnen = signal(false);

  protected readonly sendenMoeglich = computed(() => {
    const frage = this.eingabe().trim();
    return !this.laeuft() && !this.laedt() && frage.length > 0 && frage.length <= FRAGE_MAX;
  });
  /** Noch kein Text und kein Werkzeug-Hinweis: «Antwort wird vorbereitet …». */
  protected readonly wartet = computed(
    () =>
      this.laeuft() && this.hinweis() === null && (this.nachrichten().at(-1)?.inhalt ?? '') === '',
  );

  private readonly feld = viewChild<ElementRef<HTMLTextAreaElement>>('feld');
  private frage?: Subscription;
  private laden?: Subscription;
  private liste?: Subscription;
  private analyse?: Subscription;
  private analyseStatus?: Subscription;

  constructor() {
    // Seite verlassen bricht eine laufende Frage ab (die Anfrage wird beendet)
    inject(DestroyRef).onDestroy(() => {
      this.frage?.unsubscribe();
      this.laden?.unsubscribe();
      this.liste?.unsubscribe();
      this.analyse?.unsubscribe();
      this.analyseStatus?.unsubscribe();
    });
    this.listeLaden();
  }

  protected chips(quellen: readonly Quelle[]): Chip[] {
    return quellen.flatMap((q): Chip[] => {
      if (istDatenstand(q)) return [{ text: datenstandText(q), link: null, art: 'datenstand' }];
      if ('wissen' in q) {
        // Ohne Freischaltung (Spec 04, Req 22.7) liefert das Archiv die Wissensseite ohne Titel und Link: nur die Quelldokumente
        const seite: Chip[] = this.wissen() && q.wissen.titel && q.wissen.link
          ? [{ text: q.wissen.titel, link: wissenLink(q.wissen.link), art: 'wissen' }]
          : [];
        return [
          ...seite,
          ...q.dokumente.map((d): Chip => ({
            text: d.seiten ? this.texte.dokumentMitSeiten(d.titel, d.seiten) : d.titel,
            link: dokumentLink(d.link),
            art: 'dokument',
          })),
        ];
      }
      return [{ text: q.titel, link: dokumentLink(q.link), art: 'dokument' }];
    });
  }

  protected listeUmschalten(): void {
    this.listeGeoeffnet.set(!this.listeOffen());
  }

  protected listeNeuLaden(): void {
    this.listeFehler.set(false);
    this.gespraeche.set(null);
    this.listeLaden();
  }

  protected eingeben(wert: string): void {
    this.eingabe.set(wert);
  }

  /** Enter sendet, Umschalt+Enter beginnt eine neue Zeile (auch während einer Eingabe mit IME nicht senden). */
  protected taste(ereignis: KeyboardEvent): void {
    if (ereignis.key === 'Enter' && !ereignis.shiftKey && !ereignis.isComposing) {
      ereignis.preventDefault();
      this.senden();
    }
  }

  protected senden(): void {
    if (!this.sendenMoeglich()) {
      return;
    }
    const frage = this.eingabe().trim();
    this.feldSetzen('');
    this.fehler.set(null);
    this.hinweis.set(null);
    if (this.neuBeginnen()) {
      this.neuBeginnen.set(false);
      this.nachrichten.set([]);
    }
    this.nachrichten.update((liste) => [
      ...liste,
      { rolle: 'BENUTZER', inhalt: frage, quellen: [], status: null, fehler: null },
      { rolle: 'ASSISTENT', inhalt: '', quellen: [], status: null, fehler: null },
    ]);
    this.laeuft.set(true);
    const gespraechId = this.gespraechId();
    const anfrage = !gespraechId && this.modus() === 'WISSEN'
      ? this.port.anlegen('WISSEN').pipe(switchMap((kopf) => {
          this.gespraechId.set(kopf.id);
          return this.port.fragen(frage, kopf.id);
        }))
      : this.port.fragen(frage, gespraechId);
    this.frage = anfrage.subscribe({
      next: (ereignis) => this.verarbeiten(ereignis),
      error: (fehler: unknown) =>
        this.gescheitert(
          frage,
          fehler instanceof ChatFehler ? fehler : new ChatFehler('ALLGEMEIN'),
        ),
      complete: () => this.beenden(),
    });
  }

  protected abbrechen(): void {
    if (!this.laeuft()) {
      return;
    }
    this.frage?.unsubscribe();
    this.antwortAendern((n) => ({ ...n, status: 'ABGEBROCHEN' }));
    this.beenden();
  }

  protected neuesGespraech(): void {
    if (this.laeuft()) {
      return;
    }
    this.laden?.unsubscribe();
    this.laedt.set(false);
    this.gespraechId.set(null);
    this.modus.set('ARCHIV');
    this.nachrichten.set([]);
    this.neuBeginnen.set(false);
    this.fehler.set(null);
    this.analyseFehler.set(null);
    this.mobilSchliessen();
  }

  protected oeffnen(kopf: GespraechKopf): void {
    if (this.laeuft()) {
      return;
    }
    this.laden?.unsubscribe();
    this.fehler.set(null);
    this.laedt.set(true);
    this.mobilSchliessen();
    this.laden = this.port.gespraech(kopf.id).subscribe({
      next: (liste) => {
        this.gespraechId.set(kopf.id);
        this.modus.set(kopf.modus);
        this.neuBeginnen.set(false);
        this.nachrichten.set(
          liste.map((n) => ({
            id: n.id,
            analyseMoeglich: n.analyseMoeglich,
            rolle: n.rolle,
            inhalt: n.inhalt,
            quellen: n.quellen,
            status: n.status,
            fehler: null,
          })),
        );
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        this.laedt.set(false);
        this.fehler.set(
          fehlerText(
            fehler instanceof ChatFehler ? fehler : new ChatFehler('ALLGEMEIN'),
            new Date(),
          ),
        );
        if (fehler instanceof ChatFehler && fehler.art === 'NICHT_GEFUNDEN') {
          this.listeLaden();
        }
      },
    });
  }

  protected modusWaehlen(modus: ChatModus): void {
    if (this.wissen() && !this.gespraechId() && !this.laeuft() && !this.laedt()
        && this.nachrichten().length === 0) {
      this.modus.set(modus);
    }
  }

  private verarbeiten(ereignis: ChatEreignis): void {
    switch (ereignis.typ) {
      case 'werkzeug':
        this.hinweis.set(werkzeugHinweis(ereignis.name));
        break;
      case 'text':
        // der Hinweis verschwindet mit dem ersten Text danach (Req 6.2)
        this.hinweis.set(null);
        this.antwortAendern((n) => ({ ...n, inhalt: n.inhalt + ereignis.delta }));
        break;
      case 'quellen':
        this.antwortAendern((n) => ({ ...n, quellen: ereignis.quellen }));
        break;
      case 'fertig':
        this.gespraechId.set(ereignis.gespraechId);
        this.antwortAendern((n) => ({ ...n, id: ereignis.nachrichtId, status: 'OK' }));
        if (ereignis.nachrichtId && this.anmeldung.darf('A18')) {
          this.analyseStatusLaden(ereignis.gespraechId);
        }
        break;
      case 'fehler':
        if (ereignis.gespraechId) {
          this.gespraechId.set(ereignis.gespraechId);
        }
        this.antwortAendern((n) => ({ ...n, status: 'FEHLER', fehler: ereignis.meldung }));
        break;
    }
  }

  /**
   * Fehler vor dem Strom (400, 401, 404, 429, keine Verbindung): Die Frage kommt zurück ins Eingabefeld, damit sie
   * später gesendet werden kann. Bricht ein Strom mit Text ab, bleibt der Teil mit der Meldung stehen.
   */
  private gescheitert(frage: string, fehler: ChatFehler): void {
    const text = fehlerText(fehler, new Date());
    const antwort = this.nachrichten().at(-1);
    if (antwort && antwort.inhalt === '' && antwort.quellen.length === 0) {
      this.nachrichten.update((liste) => liste.slice(0, -2));
      if (this.eingabe() === '') {
        this.feldSetzen(frage);
      }
      this.fehler.set(text);
    } else {
      this.antwortAendern((n) => ({ ...n, status: 'FEHLER', fehler: text }));
    }
    if (fehler.art === 'NICHT_GEFUNDEN') {
      this.gespraechId.set(null);
    }
    this.beenden();
  }

  private beenden(): void {
    this.frage = undefined;
    this.hinweis.set(null);
    this.neuBeginnen.set(this.gespraechId() === null && this.nachrichten().length > 0);
    if (this.laeuft()) {
      this.laeuft.set(false);
      this.listeLaden();
    }
  }

  protected vorschlagen(nachricht: Nachricht): void {
    if (!nachricht.id || !nachricht.analyseMoeglich || this.analyseSendet()) return;
    const id = nachricht.id;
    this.analyseFehler.set(null);
    this.analyseSendet.set(id);
    this.analyse = this.port.analyseVorschlagen(id).subscribe({
      next: (entwurfId) => {
        this.nachrichten.update((liste) => liste.map((n) => n.id === id ? { ...n, entwurfId } : n));
        this.analyseSendet.set(null);
      },
      error: (fehler: unknown) => {
        this.analyseSendet.set(null);
        this.analyseFehler.set(fehlerText(fehler instanceof ChatFehler ? fehler : new ChatFehler('ALLGEMEIN'), new Date()));
      },
    });
  }

  private analyseStatusLaden(gespraechId: string): void {
    this.analyseStatus?.unsubscribe();
    this.analyseStatus = this.port.gespraech(gespraechId).subscribe({
      next: (gespeichert) => {
        if (this.gespraechId() !== gespraechId) return;
        const status = new Map(gespeichert.map((n) => [n.id, n.analyseMoeglich === true]));
        this.nachrichten.update((liste) => liste.map((n) =>
          n.id && status.has(n.id) ? { ...n, analyseMoeglich: status.get(n.id) } : n));
      },
      error: () => this.analyseFehler.set(this.texte.analyseStatusFehler),
    });
  }

  private antwortAendern(aendern: (n: Nachricht) => Nachricht): void {
    this.nachrichten.update((liste) => {
      const letzte = liste.at(-1);
      return letzte?.rolle === 'ASSISTENT' ? [...liste.slice(0, -1), aendern(letzte)] : liste;
    });
  }

  /**
   * Setzt Signal und Feld zusammen: Lief zwischen Eingabe und Senden keine Änderungserkennung, kennt die Bindung `[value]`
   * noch den alten Wert und liesse den gesendeten Text sonst im Feld stehen.
   */
  private feldSetzen(text: string): void {
    this.eingabe.set(text);
    const feld = this.feld()?.nativeElement;
    if (feld) {
      feld.value = text;
    }
  }

  private mobilSchliessen(): void {
    if (this.mobil()) {
      this.listeGeoeffnet.set(false);
    }
  }

  private listeLaden(): void {
    this.liste?.unsubscribe();
    this.liste = this.port.gespraeche().subscribe({
      next: (liste) => {
        this.listeFehler.set(false);
        this.gespraeche.set(liste.slice(0, 20));
      },
      error: () => {
        this.listeFehler.set(true);
        this.gespraeche.update((alt) => alt ?? []);
      },
    });
  }
}
