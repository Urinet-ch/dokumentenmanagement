import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Ergebnis, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { rechteGruppen } from './benutzer-logik';

@Component({
  selector: 'app-benutzer-rechte',
  template: `
    @for (gruppe of gruppen(); track gruppe.titel) {
      <section>
        <h3>{{ gruppe.titel }}</h3>
        <ul>
          @for (recht of gruppe.rechte; track recht.aktion) {
            <li>
              <span>{{ recht.bezeichnung }}</span>
              <span class="pille {{ ergebnisKlasse(recht.ergebnis) }}">
                {{ texte.ergebnisse[recht.ergebnis] ?? recht.ergebnis }}
              </span>
              @for (fussnote of recht.fussnoten; track $index) {
                <small>{{ fussnote.zeichen }} {{ fussnote.text }}</small>
              }
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host { display: grid; gap: 1rem; }
    h3 { margin: 0 0 .5rem; }
    ul { list-style: none; padding: 0; margin: 0; display: grid; gap: .5rem; }
    li { display: grid; justify-items: start; gap: .2rem; overflow-wrap: anywhere; }
    strong { font-size: .9rem; }
    small { color: var(--text-gedaempft); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerRechte {
  readonly rolle = input.required<VerwaltungRolle>();
  protected readonly texte = TEXTE.benutzer;
  protected readonly gruppen = computed(() => rechteGruppen(this.rolle().rechte));

  protected ergebnisKlasse(ergebnis: Ergebnis): 'ok' | 'gefahr' | 'warnung' | 'info' {
    switch (ergebnis) {
      case 'ERLAUBT': return 'ok';
      case 'VERBOTEN': return 'gefahr';
      case 'NICHT_SICHTBAR': return 'warnung';
      default: return 'info';
    }
  }
}
