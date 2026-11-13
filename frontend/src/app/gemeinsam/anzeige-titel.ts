import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TEXTE } from './texte.de-CH';

const T = TEXTE.titelPlatzhalter;
// FSI … PDI: isoliert den Namen in reinem Text (Meldungen, Attribute) wie <bdi> im Markup
const ISOLIERT = (name: string) => `\u2068${name}\u2069`;

/** Was an Stelle des Titels steht (Req 8.11, 14.9, 14.10; design.md «Frontend», Titel-Platzhalter). */
export interface AnzeigeTitel {
  /** Text für Meldungen, Rückfragen und Attribute; beim Platzhalter «Ohne Titel · <Dateiname>». */
  readonly text: string;
  /** Der Dateiname steht an Stelle eines Titels; nie als Titel senden. */
  readonly platzhalter: boolean;
  /** Der Dateiname beim Platzhalter, sonst null. */
  readonly dateiname: string | null;
}

/**
 * Titel des Dokuments, dann (nur im Eingangskorb) der vorgeschlagene Titel, dann der Dateiname als Platzhalter, sonst
 * «Ohne Titel».
 */
export function anzeigeTitel(
  titel: string | null | undefined,
  vorschlagTitel: string | null | undefined,
  dateiname: string | null | undefined,
): AnzeigeTitel {
  const gesetzt = titel || vorschlagTitel;
  if (gesetzt) {
    return { text: gesetzt, platzhalter: false, dateiname: null };
  }
  if (dateiname) {
    return { text: T.ohneTitel + T.trenner + ISOLIERT(dateiname), platzhalter: true, dateiname };
  }
  return { text: T.ohneTitel, platzhalter: false, dateiname: null };
}

/**
 * Zeigt den Titel oder den Platzhalter: der Dateiname kursiv, isoliert in `<bdi>`, gekürzt mit vollem Namen im
 * `title`; Screenreader hören «Ohne Titel, Dateiname …». Der Name wird nur als Text gesetzt, nie als HTML.
 */
@Component({
  selector: 'app-titel-anzeige',
  template: `
    @let a = anzeige();
    @if (a.dateiname; as name) {
      <span class="platzhalter" data-test="titel-platzhalter" [attr.title]="name">
        <span aria-hidden="true">{{ texte.ohneTitel }}{{ texte.trenner }}<bdi class="dateiname">{{ name }}</bdi></span>
        <span class="nur-screenreader">{{ screenreader() }}</span>
      </span>
    } @else {<ng-container>{{ a.text }}</ng-container>}
  `,
  styles: `
    :host {
      display: inline-block;
      max-width: 100%;
      min-width: 0;
      vertical-align: bottom;
    }
    .platzhalter {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-weight: normal;
    }
    .dateiname {
      font-style: italic;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TitelAnzeige {
  readonly anzeige = input.required<AnzeigeTitel>();
  protected readonly texte = T;
  protected readonly screenreader = computed(() => T.screenreader(this.anzeige().dateiname ?? ''));
}
