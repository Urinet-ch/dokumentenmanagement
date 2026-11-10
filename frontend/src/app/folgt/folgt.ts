import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/** Platzhalter für Seiten späterer Aufgaben (z. B. Eingangskorb 3.6, Suche); die Route und das Recht stehen schon. */
@Component({
  selector: 'app-folgt',
  template: `
    <h1>{{ titel() }}</h1>
    <p>{{ text }}</p>
  `,
  styles: `
    h1 {
      font: var(--mat-sys-headline-small);
      margin-top: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Folgt {
  readonly titel = input('');
  protected readonly text = TEXTE.folgt;
}
