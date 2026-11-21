import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Linien-Symbole der Oberfläche (24 × 24, Strich 1.7), nur als Pfade, damit keine Bibliothek und kein HTML nötig ist. */
const kreis = (x: number, y: number, r: number) =>
  `M${x - r} ${y}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;

export const SYMBOLE = {
  uebersicht:
    'M5.5 3.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM15.5 3.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM5.5 13.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM15.5 13.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z',
  eingangskorb: 'M3 13h5l1.5 2.5h5L16 13h5M5.5 5h13L21 13v6H3v-6z',
  erfassen: 'M12 15V4M7.5 8.5 12 4l4.5 4.5M4 15v5h16v-5',
  suche: `${kreis(11, 11, 6.5)}M20 20l-4.2-4.2`,
  chat: 'M4 5.5h16v11H10l-5 3.5v-3.5H4zM8 10h8M8 13h5',
  kontakte: `${kreis(9, 8.5, 3.5)}M2.5 20c.8-3.5 3.3-5.5 6.5-5.5s5.7 2 6.5 5.5M16 5.2a3.3 3.3 0 0 1 0 6.6M18 14.8c1.8.7 3 2.4 3.5 5.2`,
  wissen:
    'M12 6.5C10 5 7 4.5 3.5 5v13.5c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5zM12 6.5V20',
  importe: 'M12 3v11M7.5 9.5 12 14l4.5-4.5M4 16v4h16v-4',
  export:
    'M4.5 4h15A1.5 1.5 0 0 1 21 5.5v2A1.5 1.5 0 0 1 19.5 9h-15A1.5 1.5 0 0 1 3 7.5v-2A1.5 1.5 0 0 1 4.5 4zM5 9v10.5h14V9M10 13h4',
  dokumentarten:
    'M4 6h7M4 12h7M4 18h7M15 4h4a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM15 10h4a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1zM15 16h4a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z',
  protokoll: 'M12 3 19.5 6v5.5c0 4.8-3.2 8.2-7.5 9.5-4.3-1.3-7.5-4.7-7.5-9.5V6zM9 12l2 2 4-4',
  schluessel: `${kreis(8, 15, 4)}M11 12l9-9M17 6l2.5 2.5M14.5 8.5l2 2`,
  menue: 'M4 7h16M4 12h16M4 17h16',
  sonne: `${kreis(12, 12, 4)}M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4`,
  mond: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z',
  abmelden: 'M14 4h4.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H14M10 16l-4-4 4-4M6 12h10',
  archiv: 'M4 7h16M4 12h16M4 17h10',
  plus: 'M12 5v14M5 12h14',
  senden: 'M4 12 20 4l-5 16-3.5-6.5zM11.5 13.5 20 4',
  dokument: 'M6.5 3h7.5l4.5 4.5V21h-12zM14 3v4.5h4.5M9 13h6M9 16.5h4',
  tabelle:
    'M5 4.5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1zM4 9.5h16M4 14.5h16M10 9.5v10',
  schloss:
    'M7 10.5h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 10.5V8a4 4 0 0 1 8 0v2.5',
  uhr: `${kreis(12, 12, 8.5)}M12 7.5V12l3 2`,
  herunterladen: 'M12 4v11M7.5 10.5 12 15l4.5-4.5M5 20h14',
  haken: 'M5 12.5l4.5 4.5L19 7.5',
  bearbeiten: 'M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4',
  warnung: 'M12 3.5 21.5 20h-19zM12 10v4.5M12 17.2v.3',
  wiederholen: 'M20 11a8 8 0 0 0-14.5-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.5 4.5L20 16M20 20v-4h-4',
  zusammenfuehren: `${kreis(6, 5.5, 2)}${kreis(6, 18.5, 2)}${kreis(18, 12, 2)}M6 7.5v9M6 9c0 3 3 3 6 3h4`,
  kamera: `M4 8h3.5L9 5.5h6L16.5 8H20v11H4z${kreis(12, 13, 3.5)}`,
  info: `${kreis(12, 12, 8.5)}M12 11v5.5M12 7.7v.3`,
  hilfe: `${kreis(12, 12, 9)}M9.5 9a2.6 2.6 0 1 1 4.4 1.9c-1.1 1-1.9 1.4-1.9 3.1M12 17.5v.2`,
} as const;

export type SymbolName = keyof typeof SYMBOLE;

@Component({
  selector: 'app-symbol',
  template: `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path [attr.d]="pfad()" />
  </svg>`,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      width: var(--symbol-groesse, 18px);
      height: var(--symbol-groesse, 18px);
    }
    svg {
      width: 100%;
      height: 100%;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.7;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Symbol {
  readonly name = input.required<SymbolName>();
  protected readonly pfad = computed(() => SYMBOLE[this.name()]);
}
