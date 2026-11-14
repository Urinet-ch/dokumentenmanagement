import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  distinctUntilChanged,
  map,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { Dokumentart, Suchergebnis, Suchtreffer } from '../gemeinsam/api';
import { AnzeigeTitel, TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { datum } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DOKUMENTE_GROESSE, seitenAnzahl } from './kontakt-logik';
import { KontaktePort } from './kontakte-port';

/**
 * Dokumente des Kontakts (Req 17.3): `GET /dokumente?partner=<id>&sortierung=DATUM` ohne `q`, mit Blättern. Die PWA
 * filtert nichts selbst: Sichtbarkeit nach Vertraulichkeit und Rechtematrix (2.3) und die Gesamtzahl kommen vom Archiv.
 */
@Component({
  selector: 'app-kontakt-dokumente',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink, TitelAnzeige],
  templateUrl: './kontakt-dokumente.html',
  styleUrls: ['./kontakte-gemeinsam.scss', './kontakt-dokumente.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktDokumente {
  private readonly port = inject(KontaktePort);

  readonly kontaktId = input.required<string>();

  protected readonly texte = TEXTE.kontakte;
  protected readonly datum = datum;
  protected readonly seite = signal(0);
  protected readonly ergebnis = signal<Suchergebnis | null>(null);
  protected readonly laedt = signal(false);
  protected readonly fehler = signal(false);
  private readonly arten = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly seiten = computed(() =>
    seitenAnzahl(this.ergebnis()?.gesamt ?? 0, DOKUMENTE_GROESSE),
  );
  private readonly neu = new BehaviorSubject<void>(undefined);

  constructor() {
    const arten = this.port
      .dokumentarten()
      .pipe(catchError(() => of([] as Dokumentart[])))
      .subscribe((liste) => this.arten.set(new Map(liste.map((a) => [a.code, a.name]))));
    // ein anderer Kontakt beginnt wieder auf der ersten Seite
    const id = toObservable(this.kontaktId).pipe(
      distinctUntilChanged(),
      tap(() => this.seite.set(0)),
    );
    const laden = combineLatest([id, toObservable(this.seite), this.neu])
      .pipe(
        tap(() => {
          this.laedt.set(true);
          this.fehler.set(false);
        }),
        // veraltete Antworten verwerfen
        switchMap(([kontakt, seite]) =>
          this.port.dokumente(kontakt, seite, DOKUMENTE_GROESSE).pipe(
            map((e) => ({ e })),
            catchError(() => of({ e: null })),
          ),
        ),
      )
      .subscribe(({ e }) => {
        this.laedt.set(false);
        this.ergebnis.set(e);
        this.fehler.set(e === null);
      });
    inject(DestroyRef).onDestroy(() => {
      arten.unsubscribe();
      laden.unsubscribe();
    });
  }

  protected artName(code: string | null | undefined): string {
    return code ? (this.arten().get(code) ?? code) : this.texte.ohneArt;
  }

  protected titel(t: Suchtreffer): AnzeigeTitel {
    return anzeigeTitel(t.titel, null, t.dateiname);
  }

  protected blaettern(richtung: -1 | 1): void {
    this.seite.update((s) => Math.max(0, s + richtung));
  }

  protected neuLaden(): void {
    this.neu.next();
  }
}
