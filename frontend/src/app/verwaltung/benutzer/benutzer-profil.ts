import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { BenutzerAenderung, BenutzerAnsicht, BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';
import { BenutzerFortsetzen, BenutzerFortsetzung, istStepUp } from './benutzer-fortsetzung';
import { BenutzerFeldfehler, benutzerFeldbefunde, emailGueltig, nameGueltig } from './benutzer-formregeln';

type Feld = 'email' | 'vorname' | 'nachname';
type Werte = Record<Feld, string>;
const FELDER: readonly Feld[] = ['email', 'vorname', 'nachname'];

@Component({
  selector: 'app-benutzer-profil',
  imports: [MatButtonModule],
  template: `
    <section class="karte">
      <h2>{{ texte.titel }}</h2>
      <p>{{ texte.hinweis }}</p>
      @if (fortsetzungHinweis(); as text) { <p class="hinweis" role="status">{{ text }}</p> }
      @if (fehler(); as text) {
        <div class="hinweis gefahr" role="alert">
          <p>{{ text }}</p>
          @if (versionskonflikt()) {
            <button mat-button type="button" (click)="nachladen.emit()">{{ texte.laden }}</button>
          }
        </div>
      }
      @if (laufend()) { <p role="status">{{ texte.laeuft }}</p> }
      <form novalidate (submit)="$event.preventDefault(); speichern()">
        @for (feld of felder; track feld) {
          <div>
            <label [for]="'profil-' + feld">{{ feldtexte[feld] }}</label>
            <input [id]="'profil-' + feld" [value]="werte()[feld]"
              [attr.maxlength]="feld === 'email' ? 508 : 120"
              [attr.autocomplete]="feld === 'email' ? 'email' : feld === 'vorname' ? 'given-name' : 'family-name'"
              [attr.inputmode]="feld === 'email' ? 'email' : null"
              [attr.aria-invalid]="feldfehler()[feld] ? 'true' : null"
              [attr.aria-describedby]="feldfehler()[feld] ? 'profil-fehler-' + feld : null"
              [disabled]="laufend()" (input)="setzen(feld, $event)" />
            @if (feldfehler()[feld]; as meldung) {
              <p class="hinweis gefahr" [id]="'profil-fehler-' + feld">{{ meldung }}</p>
            }
          </div>
        }
        <button mat-button type="submit" [disabled]="laufend()">{{ texte.speichern }}</button>
      </form>
    </section>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerProfil {
  readonly person = input.required<BenutzerAnsicht>();
  readonly fortsetzung = input<BenutzerFortsetzung | null>(null);
  readonly verbraucht = output<void>();
  readonly abgeschlossen = output<string>();
  readonly nachladen = output<void>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly zerstoert = inject(DestroyRef);
  private readonly stepUp = inject(BenutzerFortsetzen);
  private anfrage?: Subscription;
  private id = '';
  private basis: Werte = { email: '', vorname: '', nachname: '' };
  protected readonly felder = FELDER;
  protected readonly texte = TEXTE.benutzerProfil;
  protected readonly feldtexte = TEXTE.benutzerEinladen.felder;
  protected readonly werte = signal<Werte>({ email: '', vorname: '', nachname: '' });
  protected readonly feldfehler = signal<BenutzerFeldfehler>({});
  protected readonly fehler = signal<string | null>(null);
  protected readonly versionskonflikt = signal(false);
  protected readonly laufend = signal(false);
  protected readonly fortsetzungHinweis = signal<string | null>(null);

  constructor() {
    effect(() => {
      const fortsetzung = this.fortsetzung();
      if (fortsetzung?.aktion === 'profil' && fortsetzung.ziel === this.person().id) {
        // Eingaben wurden nicht gespeichert (Datenschutz): Formular zeigt die Serverwerte, der Benutzer tippt neu
        this.fortsetzungHinweis.set(TEXTE.benutzerFortsetzung.erneutEingeben);
        this.verbraucht.emit();
      }
    });
    effect(() => {
      const person = this.person();
      const neu: Werte = { email: person.email ?? '', vorname: person.vorname ?? '', nachname: person.nachname ?? '' };
      if (person.id !== this.id) {
        this.anfrage?.unsubscribe();
        this.laufend.set(false);
        this.id = person.id ?? '';
        this.werte.set(neu);
        this.feldfehler.set({});
        this.fehler.set(null);
      } else {
        const bisher = this.basis;
        this.werte.update((werte) => ({
          email: werte.email === bisher.email ? neu.email : werte.email,
          vorname: werte.vorname === bisher.vorname ? neu.vorname : werte.vorname,
          nachname: werte.nachname === bisher.nachname ? neu.nachname : werte.nachname,
        }));
      }
      this.basis = neu;
      this.versionskonflikt.set(false);
    });
  }

  protected setzen(feld: Feld, event: Event): void {
    this.werte.update((bisher) => ({ ...bisher, [feld]: (event.target as HTMLInputElement).value }));
    this.feldfehler.update((bisher) => ({ ...bisher, [feld]: undefined }));
  }

  protected speichern(): void {
    if (this.laufend()) return;
    this.fehler.set(null);
    this.fortsetzungHinweis.set(null);
    const person = this.person();
    const { id, etag } = person;
    if (!id || !etag || !/^[a-f0-9]{64}$/.test(etag) || person.rollenkonflikt) {
      this.fehler.set(this.texte.unvollstaendig);
      return;
    }
    const werte = this.werte();
    const aenderung: BenutzerAenderung = {};
    for (const feld of FELDER) if (werte[feld] !== this.basis[feld]) aenderung[feld] = werte[feld];
    if (!Object.keys(aenderung).length) {
      this.fehler.set(this.texte.keineAenderung);
      return;
    }
    const fehler: BenutzerFeldfehler = {};
    for (const feld of FELDER) {
      if (aenderung[feld] !== undefined && !(feld === 'email' ? emailGueltig(werte[feld]) : nameGueltig(werte[feld]))) {
        fehler[feld] = TEXTE.benutzerEinladen.ungueltig[feld];
      }
    }
    this.feldfehler.set(fehler);
    if (Object.keys(fehler).length) return;
    this.laufend.set(true);
    this.anfrage = this.api.apiV1BenutzerBenutzerIdPatch(id, aenderung, `"${etag}"`)
      .pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
        next: () => {
          this.laufend.set(false);
          this.basis = { ...this.basis, ...aenderung };
          this.abgeschlossen.emit(id);
        },
        error: (fehler: unknown) => {
          if (istStepUp(fehler) && this.stepUp.starten({ aktion: 'profil', ziel: id })) return;
          this.laufend.set(false);
          if (istStepUp(fehler)) {
            this.fehler.set(TEXTE.benutzerAktionsfehler.erneuteAnmeldung);
            return;
          }
          this.feldfehler.set(benutzerFeldbefunde(fehler));
          this.fehler.set(benutzerAktionsfehler(fehler));
          this.versionskonflikt.set(fehler instanceof HttpErrorResponse && fehler.status === 412);
        },
      });
  }
}
