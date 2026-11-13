import { ChangeDetectionStrategy, Component, ElementRef, computed, input, linkedSignal, output, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { Ecken, Punkt, ganzesBild, konvex, reihe } from './scanner/ecken';

type Ecke = keyof Ecken;

const ECKEN: Ecke[] = ['obenLinks', 'obenRechts', 'untenRechts', 'untenLinks'];

/** Zuschnitt einer Seite anpassen (Req 4.3): vier Ecken ziehen oder mit den Pfeiltasten verschieben. */
@Component({
  selector: 'app-zuschnitt',
  imports: [MatButtonModule],
  template: `
    <p id="zuschnitt-hinweis">{{ texte.zuschnittHinweis }}</p>
    <svg
      #flaeche
      class="flaeche"
      [attr.viewBox]="'0 0 ' + breite() + ' ' + hoehe()"
      (pointermove)="ziehen($event)"
      (pointerup)="gezogen = null"
      (pointercancel)="gezogen = null"
    >
      <image [attr.href]="bild()" [attr.width]="breite()" [attr.height]="hoehe()" />
      <polygon [attr.points]="umriss()" [attr.stroke-width]="radius() / 4" />
      @for (ecke of ecken; track ecke) {
        <circle
          [attr.cx]="punkte()[ecke].x"
          [attr.cy]="punkte()[ecke].y"
          [attr.r]="radius()"
          [attr.stroke-width]="radius() / 4"
          tabindex="0"
          role="button"
          [attr.aria-label]="texte.ecke[ecke]"
          aria-describedby="zuschnitt-hinweis"
          [attr.data-ecke]="ecke"
          (pointerdown)="greifen($event, ecke)"
          (keydown)="taste($event, ecke)"
        />
      }
    </svg>
    <div class="aktionen">
      <button mat-flat-button type="button" [disabled]="!gueltig()" (click)="uebernehmen.emit(punkte())">{{ texte.uebernehmen }}</button>
      <button mat-stroked-button type="button" (click)="uebernehmen.emit(null)">{{ texte.ganzesBildVerwenden }}</button>
      <button mat-button type="button" (click)="abbrechen.emit()">{{ texte.abbrechen }}</button>
    </div>
  `,
  styles: `
    .flaeche {
      width: 100%;
      max-height: 70vh;
      touch-action: none;
      background: var(--urinet-karte, #0d1017);
    }
    polygon {
      fill: rgb(238 177 17 / 15%);
      stroke: var(--urinet-gelb, #eeb111);
    }
    circle {
      fill: rgb(238 177 17 / 40%);
      stroke: var(--urinet-gelb, #eeb111);
      cursor: grab;
      &:focus-visible {
        outline: none;
        fill: var(--urinet-gelb, #eeb111);
      }
    }
    .aktionen {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Zuschnitt {
  readonly bild = input.required<string>();
  readonly breite = input.required<number>();
  readonly hoehe = input.required<number>();
  /** Vorgeschlagene Ecken; ohne Kanten das ganze Bild. */
  readonly ecken0 = input<Ecken | null>(null, { alias: 'ecken' });
  /** Angepasste Ecken; `null` heisst ganzes Bild. */
  readonly uebernehmen = output<Ecken | null>();
  readonly abbrechen = output<void>();

  protected readonly texte = TEXTE.handy;
  protected readonly ecken = ECKEN;
  protected readonly punkte = linkedSignal(() => this.ecken0() ?? ganzesBild(this.breite(), this.hoehe()));
  protected readonly umriss = computed(() => reihe(this.punkte()).map((p) => `${p.x},${p.y}`).join(' '));
  /** Gekreuzte Kanten lassen sich nicht entzerren. */
  protected readonly gueltig = computed(() => konvex(this.punkte()));
  protected readonly radius = computed(() => Math.max(this.breite(), this.hoehe()) / 40);
  protected gezogen: Ecke | null = null;
  private readonly flaeche = viewChild.required<ElementRef<SVGSVGElement>>('flaeche');

  protected greifen(ereignis: PointerEvent, ecke: Ecke): void {
    this.gezogen = ecke;
    this.flaeche().nativeElement.setPointerCapture?.(ereignis.pointerId);
  }

  protected ziehen(ereignis: PointerEvent): void {
    if (!this.gezogen) {
      return;
    }
    const svg = this.flaeche().nativeElement;
    const matrix = svg.getScreenCTM()?.inverse();
    if (!matrix) {
      return;
    }
    const p = new DOMPoint(ereignis.clientX, ereignis.clientY).matrixTransform(matrix);
    this.setzen(this.gezogen, { x: p.x, y: p.y });
  }

  protected taste(ereignis: KeyboardEvent, ecke: Ecke): void {
    const schritt = Math.max(this.breite(), this.hoehe()) / (ereignis.shiftKey ? 20 : 100);
    const richtung: Record<string, Punkt> = {
      ArrowLeft: { x: -schritt, y: 0 },
      ArrowRight: { x: schritt, y: 0 },
      ArrowUp: { x: 0, y: -schritt },
      ArrowDown: { x: 0, y: schritt },
    };
    const d = richtung[ereignis.key];
    if (d) {
      ereignis.preventDefault();
      const p = this.punkte()[ecke];
      this.setzen(ecke, { x: p.x + d.x, y: p.y + d.y });
    }
  }

  private setzen(ecke: Ecke, p: Punkt): void {
    const x = Math.min(this.breite(), Math.max(0, p.x));
    const y = Math.min(this.hoehe(), Math.max(0, p.y));
    this.punkte.update((alt) => ({ ...alt, [ecke]: { x, y } }));
  }
}
