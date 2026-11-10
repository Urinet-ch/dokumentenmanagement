import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
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
    // Abgelaufene Sitzung: zurück zur Startseite, dort stehen der Hinweis und «Anmelden»; die Seite bleibt als Ziel
    effect(() => {
      if (this.anmeldung.zustand() === 'abgelaufen') {
        const ziel = untracked(() => this.router.url);
        const queryParams = ziel.startsWith('/?') || ziel === '/' ? {} : { ziel };
        void this.router.navigate(['/'], { queryParams });
      }
    });
  }

  protected zumInhalt(inhalt: HTMLElement): void {
    inhalt.focus();
  }
}
