import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Subscription, map } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ChatEreignis, ChatFehler, ChatPort, GespraechKopf, NachrichtStatus, Quelle } from './chat-port';
import {
  FRAGE_MAX,
  datenstandText,
  dokumentLink,
  fehlerText,
  istDatenstand,
  tag,
  werkzeugHinweis,
} from './chat-logik';

/** Eine Nachricht im Verlauf; `fehler` ist die Meldung zu einer Antwort, die nicht fertig wurde. */
export interface Nachricht {
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
}

/**
 * Seite «Chat» (Spec 02, Aufgabe 7.4; Req 6.1–6.3): Frage stellen, Antwort fortlaufend als reiner Text, Werkzeug-Hinweis,
 * Quellen-Chips und die letzten 20 eigenen Gespräche. Was der Chat sehen darf, entscheidet das Archiv (Req 5.1, 5.2).
 */
@Component({
  selector: 'app-chat',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './chat.html',
  styleUrl: './chat.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Chat {
  private readonly port = inject(ChatPort);

  protected readonly texte = TEXTE.chat;
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
  protected readonly listeFehler = signal(false);
  protected readonly gespraechId = signal<string | null>(null);
  protected readonly nachrichten = signal<Nachricht[]>([]);
  protected readonly laedt = signal(false);
  protected readonly laeuft = signal(false);
  protected readonly hinweis = signal<string | null>(null);
  protected readonly eingabe = signal('');
  protected readonly fehler = signal<string | null>(null);

  protected readonly sendenMoeglich = computed(() => {
    const frage = this.eingabe().trim();
    return !this.laeuft() && !this.laedt() && frage.length > 0 && frage.length <= FRAGE_MAX;
  });
  /** Noch kein Text und kein Werkzeug-Hinweis: «Antwort wird vorbereitet …». */
  protected readonly wartet = computed(
    () => this.laeuft() && this.hinweis() === null && (this.nachrichten().at(-1)?.inhalt ?? '') === '',
  );

  private frage?: Subscription;
  private laden?: Subscription;
  private liste?: Subscription;

  constructor() {
    // Seite verlassen bricht eine laufende Frage ab (die Anfrage wird beendet)
    inject(DestroyRef).onDestroy(() => {
      this.frage?.unsubscribe();
      this.laden?.unsubscribe();
      this.liste?.unsubscribe();
    });
    this.listeLaden();
  }

  protected chips(quellen: readonly Quelle[]): Chip[] {
    return quellen.map((q) =>
      istDatenstand(q) ? { text: datenstandText(q), link: null } : { text: q.titel, link: dokumentLink(q.link) },
    );
  }

  protected listeUmschalten(): void {
    this.listeGeoeffnet.set(!this.listeOffen());
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
    this.eingabe.set('');
    this.fehler.set(null);
    this.hinweis.set(null);
    this.nachrichten.update((liste) => [
      ...liste,
      { rolle: 'BENUTZER', inhalt: frage, quellen: [], status: null, fehler: null },
      { rolle: 'ASSISTENT', inhalt: '', quellen: [], status: null, fehler: null },
    ]);
    this.laeuft.set(true);
    this.frage = this.port.fragen(frage, this.gespraechId()).subscribe({
      next: (ereignis) => this.verarbeiten(ereignis),
      error: (fehler: unknown) =>
        this.gescheitert(frage, fehler instanceof ChatFehler ? fehler : new ChatFehler('ALLGEMEIN')),
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
    this.nachrichten.set([]);
    this.fehler.set(null);
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
        this.nachrichten.set(
          liste.map((n) => ({ rolle: n.rolle, inhalt: n.inhalt, quellen: n.quellen, status: n.status, fehler: null })),
        );
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        this.laedt.set(false);
        this.fehler.set(fehlerText(fehler instanceof ChatFehler ? fehler : new ChatFehler('ALLGEMEIN'), new Date()));
        if (fehler instanceof ChatFehler && fehler.art === 'NICHT_GEFUNDEN') {
          this.listeLaden();
        }
      },
    });
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
        this.antwortAendern((n) => ({ ...n, status: 'OK' }));
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
        this.eingabe.set(frage);
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
    if (this.laeuft()) {
      this.laeuft.set(false);
      this.listeLaden();
    }
  }

  private antwortAendern(aendern: (n: Nachricht) => Nachricht): void {
    this.nachrichten.update((liste) => {
      const letzte = liste.at(-1);
      return letzte?.rolle === 'ASSISTENT' ? [...liste.slice(0, -1), aendern(letzte)] : liste;
    });
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
