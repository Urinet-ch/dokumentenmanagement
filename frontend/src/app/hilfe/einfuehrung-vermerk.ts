import { DOCUMENT } from '@angular/common';
import { Injectable, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import {
  EINFUEHRUNG_VERSION,
  VermerkArt,
  vermerkAblegen,
  vermerkDatum,
  vermerkEntfernen,
  vermerkeLesen,
  vermerkKennung,
} from './einfuehrung-vermerk-speicher';

interface Person {
  readonly mandant: string;
  readonly sub: string;
}

/**
 * Ob der angemeldete Benutzer die Einführung in diesem Browser schon abgeschlossen oder übersprungen hat (Spec 07,
 * Req 1.3–1.6). Der Vermerk liegt nur im localStorage (`archiv.einfuehrung`, Kennung SHA-256 von `<mandant>:<sub>`) und
 * geht nie an den Server. Zusätzlich merkt sich der Dienst die Vermerke dieser Sitzung im Arbeitsspeicher, damit die
 * Einführung bis zum Neuladen nicht wieder erscheint, wenn der Browser nichts speichert (Req 1.5).
 */
@Injectable({ providedIn: 'root' })
export class EinfuehrungVermerk {
  private readonly anmeldung = inject(AnmeldungService);
  private readonly fenster = inject(DOCUMENT).defaultView;
  /** `<mandant>:<sub>` der Vermerke dieser Sitzung; bleibt im Arbeitsspeicher und wird nie gespeichert. */
  private readonly sitzung = new Set<string>();
  private readonly gelesen = signal<{ person: string; bekannt: boolean } | null>(null);
  private readonly fortschritt = signal<{ person: string; schritt: number; erneut: boolean } | null>(null);
  private readonly parameterAusgewertet = new Set<string>();

  private readonly person = computed<Person | null>(
    () => {
      const benutzer = this.anmeldung.benutzer();
      return this.anmeldung.angemeldet() && benutzer?.id && benutzer.mandant && benutzer.rolle
        ? { mandant: benutzer.mandant, sub: benutzer.id }
        : null;
    },
    { equal: (a, b) => (a && schluessel(a)) === (b && schluessel(b)) },
  );

  /**
   * `false`: neuer Benutzer (Einführung zeigen); `true`: Vermerk besteht; `null`: kein Benutzer mit Mandant und Rolle
   * angemeldet oder noch nicht nachgesehen (die Kennung wird asynchron berechnet).
   */
  readonly bekannt: Signal<boolean | null> = computed(() => {
    const person = this.person();
    const gelesen = this.gelesen();
    return person && gelesen?.person === schluessel(person) ? gelesen.bekannt : null;
  });
  readonly schritt = computed(() => {
    const person = this.person();
    const fortschritt = this.fortschritt();
    return person && fortschritt?.person === schluessel(person) ? fortschritt.schritt : 0;
  });
  readonly erneut = computed(() => {
    const person = this.person();
    const fortschritt = this.fortschritt();
    return !!person && fortschritt?.person === schluessel(person) && fortschritt.erneut;
  });

  schrittSetzen(schritt: number): void {
    const person = this.person();
    if (person) {
      this.fortschritt.set({ person: schluessel(person), schritt, erneut: this.erneut() });
    }
  }

  neuBeginnen(): void {
    const person = this.person();
    if (person) {
      this.parameterAusgewertet.add(schluessel(person));
      this.fortschritt.set({ person: schluessel(person), schritt: 0, erneut: true });
    }
  }

  wiederaufrufAuswerten(): void {
    const person = this.person();
    if (person && !this.parameterAusgewertet.has(schluessel(person))) this.neuBeginnen();
  }

  constructor() {
    effect(() => {
      const person = this.person();
      if (person) {
        untracked(() => void this.nachsehen(person));
      }
    });
  }

  /**
   * Legt für den angemeldeten Benutzer den Vermerk ab (Abschluss oder Überspringen, Req 3.3, 3.4) und ersetzt einen
   * bestehenden (Req 3.5); ohne Benutzer mit Mandant und Rolle geschieht nichts. `bekannt` ist sofort `true`.
   */
  async ablegen(art: VermerkArt): Promise<void> {
    const person = this.person();
    if (!person) {
      return;
    }
    this.sitzung.add(schluessel(person));
    this.gelesen.set({ person: schluessel(person), bekannt: true });
    this.fortschritt.set(null);
    const k = await vermerkKennung(person.mandant, person.sub, this.subtle());
    if (k) {
      vermerkAblegen(this.speicher(), {
        k,
        version: EINFUEHRUNG_VERSION,
        art,
        am: vermerkDatum(new Date()),
      });
    }
  }

  /**
   * Entfernt den Vermerk des angemeldeten Benutzers in diesem Browser (Spec 06, Req 11.9), auch ohne Rolle; danach gilt
   * er wieder als neu. Vermerke anderer Benutzer bleiben.
   */
  async entfernen(): Promise<void> {
    const benutzer = this.anmeldung.benutzer();
    if (!this.anmeldung.angemeldet() || !benutzer?.id || !benutzer.mandant) {
      return;
    }
    const person: Person = { mandant: benutzer.mandant, sub: benutzer.id };
    this.sitzung.delete(schluessel(person));
    if (this.gelesen()?.person === schluessel(person)) {
      this.gelesen.set({ person: schluessel(person), bekannt: false });
    }
    const k = await vermerkKennung(person.mandant, person.sub, this.subtle());
    if (k) {
      vermerkEntfernen(this.speicher(), k);
    }
  }

  private async nachsehen(person: Person): Promise<void> {
    const k = await vermerkKennung(person.mandant, person.sub, this.subtle());
    const aktuell = this.person();
    if (!aktuell || schluessel(aktuell) !== schluessel(person)) {
      return;
    }
    const bekannt =
      this.sitzung.has(schluessel(person)) ||
      (k !== null && vermerkeLesen(this.speicher()).some((vermerk) => vermerk.k === k));
    this.gelesen.set({ person: schluessel(person), bekannt });
  }

  private speicher(): Storage | null {
    try {
      return this.fenster?.localStorage ?? null;
    } catch {
      return null;
    }
  }

  private subtle(): SubtleCrypto | undefined {
    try {
      return this.fenster?.crypto?.subtle;
    } catch {
      return undefined;
    }
  }
}

function schluessel(person: Person): string {
  return `${person.mandant}:${person.sub}`;
}
