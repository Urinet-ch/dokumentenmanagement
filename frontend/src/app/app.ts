import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  Injector,
  InjectionToken,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSidenavModule } from '@angular/material/sidenav';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { filter, map } from 'rxjs';
import { AnmeldungService } from './gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from './gemeinsam/branding.service';
import { EingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';
import { EingangskorbEntwurfSpeicher } from './eingangskorb/entwurf-speicher';
import { FarbschemaService } from './gemeinsam/farbschema';
import { MENUE, abschnitte } from './gemeinsam/navigation';
import { Symbol } from './gemeinsam/symbol';
import { ONLINE } from './gemeinsam/online';
import { TEXTE } from './gemeinsam/texte.de-CH';

export const SEITE_NEU_LADEN = new InjectionToken<() => void>('SEITE_NEU_LADEN', {
  providedIn: 'root',
  factory: () => {
    const fenster = inject(DOCUMENT).defaultView;
    return () => {
      if (!fenster) {
        throw new Error('Die Seite kann in dieser Umgebung nicht neu geladen werden.');
      }
      fenster.location.reload();
    };
  },
});

@Component({
  imports: [MatButtonModule, MatSidenavModule, RouterLink, RouterLinkActive, RouterOutlet, Symbol],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly texte = TEXTE;
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly branding = inject(BrandingService);
  protected readonly zaehler = inject(EingangskorbZaehler);
  private readonly entwurfSpeicher = inject(EingangskorbEntwurfSpeicher);
  protected readonly online = inject(ONLINE);
  protected readonly updateBereit = signal(false);
  protected readonly updateFehler = signal(false);
  private readonly router = inject(Router);
  private readonly swUpdate = inject(SwUpdate);
  private readonly seiteNeuLaden = inject(SEITE_NEU_LADEN);

  protected readonly mobil = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((zustand) => zustand.matches)),
    { initialValue: true },
  );
  protected readonly menue = computed(() => MENUE.filter((punkt) => this.anmeldung.darf(punkt.aktion)));
  protected readonly abschnitte = computed(() => abschnitte(this.menue()));
  protected readonly darfSuchen = computed(() => this.menue().length > 0 && this.anmeldung.darf('A2'));
  protected readonly farbschema = inject(FarbschemaService);

  constructor() {
    effect(() => {
      const benutzerId = this.anmeldung.benutzer()?.id;
      if (benutzerId) {
        this.entwurfSpeicher.benutzerWechsel(benutzerId);
      }
    });

    if (this.swUpdate.isEnabled) {
      this.swUpdate.versionUpdates
        .pipe(
          filter((ereignis) => ereignis.type === 'VERSION_READY'),
          takeUntilDestroyed(),
        )
        .subscribe(() => {
          this.updateFehler.set(false);
          this.updateBereit.set(true);
        });
    }

    // Die Warteschlange der Handy-Erfassung lädt ab dem Start der App hoch, nicht erst auf «Erfassen» (Req 4.5);
    // nachgeladen, damit IndexedDB und Upload nicht im ersten Bundle liegen
    const injector = inject(Injector);
    afterNextRender(() =>
      void import('./erfassen/handy/warteschlange/warteschlange.service').then((m) => injector.get(m.WarteschlangeService)),
    );
    // Abgelaufene Sitzung: zurück zur Startseite, dort stehen der Hinweis und «Anmelden»; die Seite bleibt als Ziel.
    // «Erfassen» zeigt beides selbst und sichert vor der Anmeldung die aufgenommenen Seiten (Req 4.4).
    effect(() => {
      if (this.anmeldung.zustand() === 'abgelaufen') {
        const ziel = untracked(() => this.router.url);
        if (ziel === '/erfassen' || ziel.startsWith('/erfassen?')) {
          return;
        }
        const queryParams = ziel.startsWith('/?') || ziel === '/' ? {} : { ziel };
        void this.router.navigate(['/'], { queryParams });
      }
    });
  }

  /** Suche aus der Kopfzeile: führt zur Seite «Suche» mit dem Begriff (`?q=`). */
  protected suchen(ereignis: Event, feld: HTMLInputElement): void {
    ereignis.preventDefault();
    const q = feld.value.trim();
    void this.router.navigate(['/suche'], { queryParams: q ? { q } : {} });
    feld.value = '';
  }

  protected initialen(name: string): string {
    const teile = name.trim().split(/\s+/).filter(Boolean);
    return (teile.length > 1 ? teile[0][0] + teile[teile.length - 1][0] : (teile[0] ?? '?').slice(0, 2)).toUpperCase();
  }

  protected zumInhalt(inhalt: HTMLElement): void {
    inhalt.focus();
  }

  protected abmelden(): void {
    this.entwurfSpeicher.alleLoeschen();
    this.anmeldung.abmelden();
  }

  protected async aktualisieren(): Promise<void> {
    this.updateFehler.set(false);
    try {
      if (!(await this.swUpdate.activateUpdate())) {
        this.updateFehler.set(true);
        return;
      }
      this.seiteNeuLaden();
    } catch {
      this.updateFehler.set(true);
    }
  }
}
