import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ZusatzFeld, listeAus, listeZu } from './aenderung';

/**
 * Zusatzfeld vom Typ Liste (JSON-Schema `array` von Strings): bei `items.enum` eine Mehrfachauswahl aus Checkboxen,
 * sonst eine Liste freier Texte mit Hinzufügen und Entfernen. Der Wert ist wie bei den übrigen Zusatzfeldern ein Text,
 * ein Eintrag je Zeile (`listeAus`/`listeZu`); Prüfung und Body-Bau liegen in `aenderung.ts`.
 */
@Component({
  selector: 'app-listenfeld',
  imports: [MatButtonModule],
  templateUrl: './listenfeld.html',
  styleUrl: './listenfeld.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Listenfeld {
  readonly feld = input.required<ZusatzFeld>();
  /** Eindeutiger Präfix für `id`-Attribute. */
  readonly kennung = input.required<string>();
  readonly beschriftung = input.required<string>();
  readonly wert = input<string>('');
  readonly ungueltig = input(false);
  readonly geaendert = output<string>();

  protected readonly texte = TEXTE.listenfeld;
  protected readonly doppelt = signal(false);
  protected readonly eintraege = computed(() => listeAus(this.wert()));
  protected readonly hinweis = computed(() => {
    const feld = this.feld();
    const min = Math.max(feld.minEintraege ?? 0, feld.pflicht ? 1 : 0);
    return feld.typ === 'mehrfach' ? this.texte.hinweisMehrfach(min) : this.texte.hinweisListe(min);
  });

  protected gewaehlt(option: string): boolean {
    return this.eintraege().includes(option);
  }

  /** Liest die Auswahl aus den Checkboxen selbst: Zwei schnelle Eingaben hintereinander überschreiben sich so nicht. */
  protected umschalten(gruppe: HTMLElement): void {
    const gewaehlt = new Set(
      [...gruppe.querySelectorAll<HTMLInputElement>('input[type=checkbox]:checked')].map((box) => box.value),
    );
    // Reihenfolge der Optionen aus dem Schema, nicht der Klicks: stabiler Body, nachvollziehbarer Vergleich.
    this.geaendert.emit(listeZu(this.feld().optionen.filter((o) => gewaehlt.has(o))));
  }

  protected hinzufuegen(eingabe: HTMLInputElement): void {
    const eintrag = eingabe.value.trim();
    if (eintrag === '') {
      return;
    }
    if (this.feld().eindeutig && this.eintraege().includes(eintrag)) {
      this.doppelt.set(true);
      return;
    }
    this.doppelt.set(false);
    this.geaendert.emit(listeZu([...this.eintraege(), eintrag]));
    eingabe.value = '';
    eingabe.focus();
  }

  protected entfernen(index: number, eingabe: HTMLInputElement): void {
    this.geaendert.emit(listeZu(this.eintraege().filter((_, i) => i !== index)));
    eingabe.focus();
  }
}
