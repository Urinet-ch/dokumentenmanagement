import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  output,
  signal,
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

  /**
   * Zeigt die Leiste einen Abschnitt, ist das Inhaltsverzeichnis beim Öffnen zugeklappt, damit der Text den Platz
   * bekommt; ohne Abschnitt ist es aufgeklappt, weil es dann der einzige Inhalt ist (Req 4.7).
   */
  private readonly verzeichnisWunsch = signal<boolean | null>(null);
  protected readonly verzeichnisOffen = computed(() => this.verzeichnisWunsch() ?? !this.ausgewaehlt());

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

  protected verzeichnisUmschalten(): void {
    this.verzeichnisWunsch.set(!this.verzeichnisOffen());
  }

  protected waehlen(ereignis: MouseEvent, anker: string): void {
    ereignis.preventDefault();
    this.hilfe.wechseln(anker);
  }
}
