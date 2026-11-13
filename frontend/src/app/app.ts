import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, Injector, afterNextRender, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AnmeldungService } from './gemeinsam/anmeldung/anmeldung.service';
import { EingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';
import { MENUE } from './gemeinsam/navigation';
import { TEXTE } from './gemeinsam/texte.de-CH';

@Component({
  imports: [MatButtonModule, MatListModule, MatSidenavModule, MatToolbarModule, RouterLink, RouterLinkActive, RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly texte = TEXTE;
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly zaehler = inject(EingangskorbZaehler);
  private readonly router = inject(Router);

  protected readonly mobil = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((zustand) => zustand.matches)),
    { initialValue: true },
  );
  protected readonly menue = computed(() => MENUE.filter((punkt) => this.anmeldung.darf(punkt.aktion)));

  constructor() {
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

  protected zumInhalt(inhalt: HTMLElement): void {
    inhalt.focus();
  }
}
