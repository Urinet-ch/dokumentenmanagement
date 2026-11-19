import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-nicht-gefunden',
  standalone: true,
  template: `
    <div>
      <h1>{{ texte.seiteNichtGefunden.titel }}</h1>
      <p>{{ texte.seiteNichtGefunden.beschreibung }}</p>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NichtGefunden {
  protected readonly texte = TEXTE;
}
