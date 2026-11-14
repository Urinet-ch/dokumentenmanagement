import { DOCUMENT } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  inject,
  input,
  output,
} from '@angular/core';

const FOKUSSIERBAR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modaler Dialog für die Rückfragen bei Kontakten (Req 17.17): Titel, Inhalt als Platzhalter, Escape schliesst, der Fokus
 * bleibt im Dialog und kehrt beim Schliessen zum auslösenden Knopf zurück. Auf dem Handy füllt er die Breite und scrollt
 * nur senkrecht.
 */
@Component({
  selector: 'app-kontakt-dialog',
  template: `
    <div class="schleier" data-test="dialog-schleier">
      <div
        class="dialog karte"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titelId"
        data-test="dialog"
        (keydown)="taste($event)"
      >
        <h2 [id]="titelId" tabindex="-1" #titelElement>{{ titel() }}</h2>
        <ng-content />
      </div>
    </div>
  `,
  styles: `
    .schleier {
      position: fixed;
      inset: 0;
      z-index: 1000;
      display: flex;
      align-items: flex-start;
      justify-content: center;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 1rem;
      box-sizing: border-box;
      background-color: rgba(0, 0, 0, 0.7);
    }
    .dialog {
      box-sizing: border-box;
      width: min(100%, 36rem);
      min-width: 0;
      padding: 1rem;
      margin: auto 0;
      overflow-wrap: anywhere;
    }
    h2 {
      font: var(--mat-sys-title-large);
      margin: 0 0 0.75rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktDialog implements AfterViewInit, OnDestroy {
  readonly titel = input.required<string>();
  readonly schliessen = output<void>();

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly dokument = inject(DOCUMENT);
  private readonly vorher = this.dokument.activeElement as HTMLElement | null;
  protected readonly titelId = `kontakt-dialog-${Math.random().toString(36).slice(2, 8)}`;

  ngAfterViewInit(): void {
    this.element.nativeElement.querySelector<HTMLElement>('h2')?.focus();
  }

  ngOnDestroy(): void {
    if (this.vorher && this.dokument.contains(this.vorher)) {
      this.vorher.focus();
    }
  }

  protected taste(ereignis: KeyboardEvent): void {
    if (ereignis.key === 'Escape') {
      ereignis.stopPropagation();
      this.schliessen.emit();
      return;
    }
    if (ereignis.key !== 'Tab') {
      return;
    }
    const elemente = Array.from(
      this.element.nativeElement.querySelectorAll<HTMLElement>(FOKUSSIERBAR),
    ).filter((e) => !e.hasAttribute('hidden'));
    if (elemente.length === 0) {
      return;
    }
    const erstes = elemente[0];
    const letztes = elemente[elemente.length - 1];
    const aktiv = this.dokument.activeElement;
    if (
      ereignis.shiftKey &&
      (aktiv === erstes || aktiv === this.element.nativeElement.querySelector('h2'))
    ) {
      ereignis.preventDefault();
      letztes.focus();
    } else if (!ereignis.shiftKey && aktiv === letztes) {
      ereignis.preventDefault();
      erstes.focus();
    }
  }
}
