import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';

type Feld = 'benutzername' | 'email' | 'vorname' | 'nachname' | 'rolle';
type Eingabe = Record<Feld, string>;
type Feldfehler = Partial<Record<Feld, string>>;
const FELDER: readonly Feld[] = ['benutzername', 'email', 'vorname', 'nachname', 'rolle'];

@Component({
  selector: 'app-benutzer-einladen',
  imports: [MatButtonModule],
  template: `
    <section class="karte">
      <h2>{{ texte.titel }}</h2>
      <p>{{ texte.hinweis }}</p>
      @if (fehler(); as text) { <p class="hinweis gefahr" role="alert">{{ text }}</p> }
      @if (laufend()) { <p role="status">{{ texte.laeuft }}</p> }
      <form #formular novalidate (submit)="$event.preventDefault(); senden()">
        @for (feld of textfelder; track feld) {
          <div>
            <label [for]="'einladung-' + feld">{{ texte.felder[feld] }}</label>
            <input [id]="'einladung-' + feld" [value]="eingabe()[feld]"
              [attr.maxlength]="feld === 'benutzername' ? 40 : feld === 'email' ? 508 : 120"
              [attr.autocomplete]="feld === 'email' ? 'email' : feld === 'vorname' ? 'given-name' : feld === 'nachname' ? 'family-name' : 'off'"
              [attr.inputmode]="feld === 'email' ? 'email' : null"
              [attr.aria-invalid]="feldfehler()[feld] ? 'true' : null"
              [attr.aria-describedby]="feldfehler()[feld] ? 'einladung-fehler-' + feld : null"
              [disabled]="laufend()" (input)="setzen(feld, $event)" />
            @if (feldfehler()[feld]; as meldung) {
              <p class="hinweis gefahr" [id]="'einladung-fehler-' + feld">{{ meldung }}</p>
            }
          </div>
        }
        <div>
          <label for="einladung-rolle">{{ texte.felder.rolle }}</label>
          <select id="einladung-rolle" [value]="eingabe().rolle" [disabled]="laufend()"
            [attr.aria-invalid]="feldfehler().rolle ? 'true' : null"
            [attr.aria-describedby]="feldfehler().rolle ? 'einladung-fehler-rolle' : null"
            (change)="setzen('rolle', $event)">
            <option value="">{{ texte.waehlen }}</option>
            @for (rolle of rollen(); track rolle.code) {
              <option [value]="rolle.code">{{ rolle.beschreibung }}</option>
            }
          </select>
          @if (feldfehler().rolle; as meldung) {
            <p class="hinweis gefahr" id="einladung-fehler-rolle">{{ meldung }}</p>
          }
        </div>
        <button mat-button type="submit" [disabled]="laufend()">{{ texte.senden }}</button>
      </form>
    </section>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerEinladen {
  readonly rollen = input.required<readonly VerwaltungRolle[]>();
  readonly abgeschlossen = output<{ id: string; antrag: boolean }>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly zerstoert = inject(DestroyRef);
  private readonly formular = viewChild.required<ElementRef<HTMLFormElement>>('formular');
  protected readonly texte = TEXTE.benutzerEinladen;
  protected readonly textfelder = ['benutzername', 'email', 'vorname', 'nachname'] as const;
  protected readonly eingabe = signal<Eingabe>({ benutzername: '', email: '', vorname: '', nachname: '', rolle: '' });
  protected readonly feldfehler = signal<Feldfehler>({});
  protected readonly fehler = signal<string | null>(null);
  protected readonly laufend = signal(false);

  protected setzen(feld: Feld, event: Event): void {
    const wert = (event.target as HTMLInputElement | HTMLSelectElement).value;
    this.eingabe.update((bisher) => ({ ...bisher, [feld]: wert }));
    this.feldfehler.update((bisher) => ({ ...bisher, [feld]: undefined }));
  }

  protected senden(): void {
    if (this.laufend()) return;
    this.fehler.set(null);
    const eingabe = this.eingabe();
    const befunde = pruefen(eingabe, this.rollen());
    this.feldfehler.set(befunde);
    if (Object.keys(befunde).length) return;
    this.laufend.set(true);
    this.api.apiV1BenutzerPost(eingabe).pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        this.laufend.set(false);
        if (!antwort.id) {
          this.fehler.set(TEXTE.httpFehler.allgemein);
          return;
        }
        this.eingabe.set({ benutzername: '', email: '', vorname: '', nachname: '', rolle: '' });
        this.formular().nativeElement.reset();
        this.abgeschlossen.emit({ id: antwort.id, antrag: !!antwort.antragId });
      },
      error: (fehler: unknown) => {
        this.laufend.set(false);
        const felder = serverBefunde(fehler);
        this.feldfehler.set(felder);
        this.fehler.set(benutzerAktionsfehler(fehler));
      },
    });
  }
}

function pruefen(eingabe: Eingabe, rollen: readonly VerwaltungRolle[]): Feldfehler {
  const fehler: Feldfehler = {};
  const texte = TEXTE.benutzerEinladen.ungueltig;
  const gueltigeLaenge = (wert: string, maximum: number) => {
    const zeichen = Array.from(wert);
    return zeichen.length > 0 && zeichen.length <= maximum
      && !zeichen.some((zeichen) => /[\u0000-\u001f\u007f-\u009f]/.test(zeichen));
  };
  if (!/^[a-z0-9._-]{3,40}$/.test(eingabe.benutzername)) fehler.benutzername = texte.benutzername;
  if (!gueltigeLaenge(eingabe.email, 254) || eingabe.email.indexOf('@') < 0
      || eingabe.email.indexOf('@') !== eingabe.email.lastIndexOf('@')) fehler.email = texte.email;
  if (!gueltigeLaenge(eingabe.vorname, 60)) fehler.vorname = texte.vorname;
  if (!gueltigeLaenge(eingabe.nachname, 60)) fehler.nachname = texte.nachname;
  if (!rollen.some((rolle) => rolle.code === eingabe.rolle)) fehler.rolle = texte.rolle;
  return fehler;
}

function serverBefunde(fehler: unknown): Feldfehler {
  const ergebnis: Feldfehler = {};
  if (!(fehler instanceof HttpErrorResponse) || fehler.status !== 422
      || !Array.isArray(fehler.error?.befunde)) return ergebnis;
  for (const befund of fehler.error.befunde as unknown[]) {
    if (!befund || typeof befund !== 'object' || !('pfad' in befund) || !('meldung' in befund)
        || typeof befund.pfad !== 'string' || typeof befund.meldung !== 'string') continue;
    const feld = FELDER.find((eintrag) => eintrag === befund.pfad);
    if (feld) ergebnis[feld] = befund.meldung;
  }
  return ergebnis;
}
