import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { BenutzerAnsicht, BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';
import { BenutzerFortsetzen, BenutzerFortsetzung, istStepUp } from './benutzer-fortsetzung';
import { BenutzerLoeschdialog, BenutzerLoeschdialogDaten, Verwaltungsgrund } from './benutzer-loeschdialog';

@Component({
  selector: 'app-benutzer-loeschen',
  imports: [MatButtonModule],
  template: `
    @if (fehler(); as meldung) {
      <p class="hinweis gefahr" role="alert">{{ meldung }}</p>
    }
    @if (hinweis(); as text) {
      <p role="status">{{ text }}</p>
    }
    @if (meldung(); as text) {
      <p role="status">{{ text }}</p>
    }
    <button mat-button type="button" [disabled]="laufend()" (click)="vorschauLaden()">{{ texte.oeffnen }}</button>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerLoeschen {
  readonly person = input.required<BenutzerAnsicht>();
  readonly fortsetzung = input<BenutzerFortsetzung | null>(null);
  readonly verbraucht = output<void>();
  readonly abgeschlossen = output<{ id: string; antrag: boolean }>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly dialog = inject(MatDialog);
  private readonly zerstoert = inject(DestroyRef);
  private readonly stepUp = inject(BenutzerFortsetzen);
  private fortsetzungVerbraucht = false;
  private geoeffnet?: MatDialogRef<BenutzerLoeschdialog, Verwaltungsgrund | null>;

  protected readonly texte = TEXTE.benutzerLoeschen;
  protected readonly fehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly laufend = signal(false);
  protected readonly hinweis = signal<string | null>(null);

  constructor() {
    this.zerstoert.onDestroy(() => this.geoeffnet?.close());
    effect(() => {
      const fortsetzung = this.fortsetzung();
      if (this.fortsetzungVerbraucht || fortsetzung?.aktion !== 'loeschen' || fortsetzung.ziel !== this.person().id) return;
      // Namensbestätigung und Grund werden nicht gespeichert: Dialog neu öffnen, der Benutzer bestätigt erneut
      this.fortsetzungVerbraucht = true;
      this.verbraucht.emit();
      this.hinweis.set(TEXTE.benutzerFortsetzung.erneutEingeben);
      this.vorschauLaden();
    });
  }

  protected vorschauLaden(): void {
    const { id, benutzername } = this.person();
    if (this.laufend()) return;
    if (!id || !benutzername) {
      this.fehler.set(this.texte.vorschauFehler);
      return;
    }
    this.fehler.set(null);
    this.meldung.set(this.texte.vorschauLaden);
    this.laufend.set(true);
    this.api.apiV1BenutzerBenutzerIdLoeschvorschauGet(id)
      .pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
        next: (vorschau) => {
          const daten = dialogDaten(benutzername, vorschau);
          if (!daten || this.person().id !== id) {
            this.fehler.set(this.texte.vorschauFehler);
            this.meldung.set(null);
            this.laufend.set(false);
            return;
          }
          this.meldung.set(null);
          this.geoeffnet = this.dialog.open(BenutzerLoeschdialog, { data: daten });
          this.geoeffnet.afterClosed().pipe(takeUntilDestroyed(this.zerstoert)).subscribe((grund) => {
            this.geoeffnet = undefined;
            if (!grund || this.person().id !== id || this.person().benutzername !== benutzername) {
              this.laufend.set(false);
              return;
            }
            this.loeschen(id, grund);
          });
        },
        error: (fehler: unknown) => {
          this.fehler.set(benutzerAktionsfehler(fehler));
          this.meldung.set(null);
          this.laufend.set(false);
        },
      });
  }

  private loeschen(id: string, grund: Verwaltungsgrund): void {
    this.hinweis.set(null);
    this.meldung.set(this.texte.wirdGeloescht);
    this.api.apiV1BenutzerBenutzerIdLoeschenPost(id, { grund }, undefined, 'response')
      .pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
        next: (antwort) => {
          this.meldung.set(null);
          this.laufend.set(false);
          if (antwort.status === 204 || (antwort.status === 202 && !!antwort.body?.antragId)) {
            this.abgeschlossen.emit({ id, antrag: antwort.status === 202 });
          } else {
            this.fehler.set(TEXTE.httpFehler.allgemein);
          }
        },
        error: (fehler: unknown) => {
          this.meldung.set(null);
          if (istStepUp(fehler)) {
            if (this.stepUp.starten({ aktion: 'loeschen', ziel: id })) return;
            this.laufend.set(false);
            this.fehler.set(TEXTE.benutzerAktionsfehler.erneuteAnmeldung);
            return;
          }
          this.fehler.set(benutzerAktionsfehler(fehler));
          this.meldung.set(null);
          this.laufend.set(false);
        },
      });
  }
}

function dialogDaten(benutzername: string, vorschau: unknown): BenutzerLoeschdialogDaten | null {
  if (!vorschau || typeof vorschau !== 'object'
      || !('dokumente' in vorschau) || !('protokolleintraege' in vorschau)) return null;
  const { dokumente, protokolleintraege } = vorschau;
  if (typeof dokumente !== 'number' || !Number.isSafeInteger(dokumente) || dokumente < 0
      || typeof protokolleintraege !== 'number' || !Number.isSafeInteger(protokolleintraege)
      || protokolleintraege < 0) return null;
  return { benutzername, dokumente, protokolleintraege };
}
