import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { BrandingService } from '../gemeinsam/branding.service';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { AnleitungLader } from './anleitung-lader';
import { AnleitungText } from './anleitung-text';
import { HILFE_EINTRAEGE, sichtbareAbschnitte, sichtbareAnker } from './hilfe-sichtbar';

/**
 * Seite `/hilfe`: die ganze Anleitung der Rolle mit Inhaltsverzeichnis und Sprungmarken (Spec 07, Req 5, 6.2, 6.3,
 * 6.5). Der Inhalt scrollt in `mat-sidenav-content`, nicht im Fenster; darum springt die Seite selbst zum Anker
 * (design.md «Hilfe-Leiste und Seite `/hilfe`»), erst wenn alle Dateien geladen sind, damit nichts mehr verrutscht.
 */
@Component({
  selector: 'app-hilfe-seite',
  imports: [RouterLink, Symbol, AnleitungText],
  templateUrl: './hilfe-seite.html',
  styleUrl: './hilfe-seite.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HilfeSeite {
  protected readonly branding = inject(BrandingService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly lader = inject(AnleitungLader);
  private readonly katalog = inject(HILFE_EINTRAEGE);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly texte = TEXTE.hilfe;
  protected readonly rollen = TEXTE.kopf.rollen;

  /** Nur mit Mandant und Rolle; sonst zeigt die Anleitung die Abschnitte für Abgemeldete (Req 6.3). */
  protected readonly rolle = computed<Rolle | null>(() => {
    const benutzer = this.anmeldung.benutzer();
    return this.anmeldung.angemeldet() && benutzer?.mandant && benutzer.rolle ? benutzer.rolle : null;
  });
  protected readonly abschnitte = computed(() => sichtbareAbschnitte(this.katalog, this.rolle()));
  private readonly anker = computed(() => sichtbareAnker(this.abschnitte()));
  private readonly geladen = computed(() =>
    this.abschnitte()
      .flatMap(({ eintrag, unter }) => [eintrag, ...unter])
      .every((eintrag) => this.lader.zustand(eintrag)().art !== 'laedt'),
  );
  /** Zuletzt angesprungener Anker (`aria-current` im Inhaltsverzeichnis). */
  protected readonly aktuell = signal<string | null>(null);
  /** Jede Navigation ein neues Objekt, damit auch der Zurück-Knopf auf denselben Anker erneut springt. */
  private readonly ziel = signal<{ anker: string | null }>({ anker: null });
  private erledigt: object | null = null;

  constructor() {
    const route = inject(ActivatedRoute);
    route.fragment.pipe(takeUntilDestroyed()).subscribe((anker) => this.ziel.set({ anker }));
    afterRenderEffect(() => {
      const ziel = this.ziel();
      if (!this.geladen() || ziel === this.erledigt) return;
      this.erledigt = ziel;
      untracked(() => this.springen(ziel.anker));
    });
  }

  /**
   * Eintrag im Inhaltsverzeichnis oder Link im Text: Der Router setzt die Adresse (Req 5.3). Steht sie schon auf
   * diesem Anker, navigiert er nicht; dann springt die Seite direkt.
   */
  protected gewaehlt(anker: string): void {
    if (this.ziel().anker === anker) this.springen(anker);
  }

  /** Req 5.2: zum Abschnitt scrollen und den Fokus auf seine Überschrift; unbekannt oder verborgen: Seite von oben. */
  private springen(anker: string | null): void {
    const ueberschrift =
      anker && this.anker().has(anker) ? this.host.nativeElement.querySelector<HTMLElement>(`[id="${anker}"]`) : null;
    if (ueberschrift) {
      ueberschrift.scrollIntoView({ block: 'start' });
      ueberschrift.focus({ preventScroll: true });
      this.aktuell.set(anker);
    } else {
      const bereich = this.host.nativeElement.closest('mat-sidenav-content');
      if (bereich) bereich.scrollTop = 0;
      this.aktuell.set(null);
    }
  }
}
