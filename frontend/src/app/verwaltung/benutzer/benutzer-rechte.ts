import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { rechteGruppen } from './benutzer-logik';

@Component({
  selector: 'app-benutzer-rechte',
  template: `
    @for (gruppe of gruppen(); track gruppe.titel) {
      <table>
        <caption>
          {{
            gruppe.titel
          }}
        </caption>
        <colgroup>
          <col class="recht" />
          <col class="ergebnis" />
          <col class="hinweis" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">{{ texte.tabellenkoepfe.recht }}</th>
            <th scope="col">{{ texte.tabellenkoepfe.ergebnis }}</th>
            <th scope="col">{{ texte.tabellenkoepfe.hinweis }}</th>
          </tr>
        </thead>
        <tbody>
          @for (recht of gruppe.rechte; track recht.aktion) {
            <tr>
              <th scope="row">{{ recht.bezeichnung }}</th>
              <td>
                {{ texte.ergebnisse[recht.ergebnis] ?? texte.unbekannt }}
              </td>
              <td>
                @for (fussnote of recht.fussnoten; track $index) {
                  <small>{{ fussnote.zeichen }} {{ fussnote.text }}</small>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 1rem;
      min-width: 0;
    }
    table {
      width: 100%;
      min-width: 0;
      table-layout: fixed;
      border-collapse: collapse;
    }
    col.recht {
      width: 40%;
    }
    col.ergebnis {
      width: 25%;
    }
    col.hinweis {
      width: 35%;
    }
    caption {
      padding-bottom: 0.5rem;
      font-weight: 600;
      text-align: start;
    }
    th,
    td {
      padding: 0.45rem 0.35rem;
      border-bottom: 1px solid var(--linie);
      overflow-wrap: anywhere;
      text-align: start;
      vertical-align: top;
    }
    thead th {
      font-size: 0.9rem;
    }
    tbody th {
      font-weight: 500;
    }
    small {
      display: block;
      color: var(--text-gedaempft);
    }
    @media (max-width: 599px) {
      th,
      td {
        padding: 0.4rem 0.2rem;
      }
      thead th {
        font-size: 0.82rem;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerRechte {
  readonly rolle = input.required<VerwaltungRolle>();
  protected readonly texte = TEXTE.benutzer;
  protected readonly gruppen = computed(() => rechteGruppen(this.rolle().rechte));
}
