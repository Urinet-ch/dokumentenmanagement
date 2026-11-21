import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  output,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { AnleitungText } from './anleitung-text';
import { HILFE_EINTRAEGE, sichtbareAbschnitte } from './hilfe-sichtbar';
import { HilfeLeiste } from './hilfe-leiste.service';

@Component({
  selector: 'app-hilfe-leiste',
  imports: [AnleitungText, MatButtonModule, RouterLink],
  templateUrl: './hilfe-leiste.html',
  styleUrl: './hilfe-leiste.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HilfeLeisteAnsicht {
  protected readonly hilfe = inject(HilfeLeiste);
  private readonly katalog = inject(HILFE_EINTRAEGE);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly texte = TEXTE.hilfe;
  readonly navigiert = output<void>();

  protected readonly eintraege = computed(() =>
    sichtbareAbschnitte(this.katalog, this.hilfe.rolle()).flatMap(({ eintrag, unter }) => [eintrag, ...unter]),
  );

  protected readonly ausgewaehlt = computed(
    () => this.eintraege().find((eintrag) => eintrag.anker === this.hilfe.anker()) ?? null,
  );

  constructor() {
    afterRenderEffect(() => {
      if (this.hilfe.offen()) this.host.nativeElement.querySelector<HTMLElement>('#hilfe-leiste-titel')?.focus();
    });
  }

  protected waehlen(ereignis: MouseEvent, anker: string): void {
    ereignis.preventDefault();
    this.hilfe.wechseln(anker);
  }
}
