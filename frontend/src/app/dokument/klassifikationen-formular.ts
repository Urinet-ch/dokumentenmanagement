import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KlassifikationFeld, KlassifikationWertFeld } from './klassifikationen';

@Component({
  selector: 'app-klassifikationen-formular',
  templateUrl: './klassifikationen-formular.html',
  styleUrl: './klassifikationen-formular.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KlassifikationenFormular {
  readonly felder = input<readonly KlassifikationFeld[]>([]);
  readonly kennung = input.required<string>();
  readonly werte = input<Readonly<Record<string, readonly string[]>>>({});
  readonly ungueltigeCodes = input<readonly string[]>([]);
  readonly geaendert = output<Readonly<Record<string, readonly string[]>>>();

  protected readonly texte = TEXTE.eingangskorb;

  protected optionen(feld: KlassifikationFeld): readonly KlassifikationWertFeld[] {
    const ausgewaehlt = this.werte()[feld.code] ?? [];
    const aktiv = feld.aktiv ? feld.werte.filter((wert) => wert.aktiv) : [];
    const bekannte = feld.werte.filter(
      (wert) =>
        ausgewaehlt.includes(wert.code) && !aktiv.some((option) => option.code === wert.code),
    );
    const unbekannte = ausgewaehlt
      .filter((code) => !feld.werte.some((wert) => wert.code === code))
      .map((code) => ({ code, name: code, aktiv: false }));
    return [...aktiv, ...bekannte, ...unbekannte];
  }

  protected gewaehlt(feld: string, wert: string): boolean {
    return this.werte()[feld]?.includes(wert) ?? false;
  }

  protected einzelGeaendert(feld: string, ereignis: Event): void {
    const wert = (ereignis.target as HTMLSelectElement).value;
    this.geaendert.emit({ ...this.werte(), [feld]: wert === '' ? [] : [wert] });
  }

  protected umschalten(feld: string, wert: string, ereignis: Event): void {
    const ausgewaehlt = this.werte()[feld] ?? [];
    const gesetzt = (ereignis.target as HTMLInputElement).checked;
    const neu = gesetzt ? [...ausgewaehlt, wert] : ausgewaehlt.filter((code) => code !== wert);
    this.geaendert.emit({ ...this.werte(), [feld]: neu });
  }

  protected feldUngueltig(code: string): boolean {
    return this.ungueltigeCodes().includes(code);
  }

  protected darfWaehlen(feld: KlassifikationFeld, wert: KlassifikationWertFeld): boolean {
    return (feld.aktiv && wert.aktiv) || this.gewaehlt(feld.code, wert.code);
  }

  protected alteWerte(feld: KlassifikationFeld): string[] {
    return (this.werte()[feld.code] ?? []).filter(
      (code) => !this.optionen(feld).some((wert) => wert.code === code && wert.aktiv),
    );
  }
}
