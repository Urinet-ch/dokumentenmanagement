import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { Dokument, Dokumentart, DokumentartenService, DokumenteService } from '../gemeinsam/api';
import { EingangskorbZaehler } from '../gemeinsam/eingangskorb-zaehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { EingangskorbKarte } from './eingangskorb-karte';

const GROESSE = 20;

/** Eingangskorb, neueste zuerst (Req 8.1); das Archiv liefert nur, was der Benutzer sehen darf (Req 2.3, 2.24). */
@Component({
  selector: 'app-eingangskorb',
  imports: [MatButtonModule, EingangskorbKarte],
  templateUrl: './eingangskorb.html',
  styleUrl: './eingangskorb.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Eingangskorb implements OnInit {
  private readonly api = inject(DokumenteService);
  private readonly artenApi = inject(DokumentartenService);
  private readonly zaehler = inject(EingangskorbZaehler);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly ueberschrift = viewChild.required<ElementRef<HTMLElement>>('ueberschrift');

  protected readonly texte = TEXTE.eingangskorb;
  protected readonly eintraege = signal<readonly Dokument[]>([]);
  protected readonly gesamt = signal(0);
  protected readonly laedt = signal(true);
  protected readonly fehlerLaden = signal(false);
  protected readonly meldung = signal('');
  protected readonly arten = signal<ReadonlyMap<string, Dokumentart>>(new Map());
  private seite = 0;

  ngOnInit(): void {
    this.laden();
    this.artenApi
      .apiV1DokumentartenGet()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (arten) => this.arten.set(new Map(arten.map((a) => [a.code, a]))),
        error: () => this.arten.set(new Map()),
      });
  }

  protected laden(weitere = false): void {
    const seite = weitere ? this.seite + 1 : 0;
    this.laedt.set(true);
    this.fehlerLaden.set(false);
    this.api
      .apiV1EingangskorbGet(GROESSE, seite)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (antwort) => {
          this.seite = seite;
          this.eintraege.update((alt) =>
            weitere ? [...alt, ...antwort.eintraege] : antwort.eintraege,
          );
          this.gesamt.set(antwort.gesamt);
          this.laedt.set(false);
        },
        error: () => {
          this.laedt.set(false);
          this.fehlerLaden.set(true);
        },
      });
  }

  protected neuLaden(meldung: string): void {
    this.meldung.set(meldung);
    this.zaehler.auffrischen();
    this.laden();
    afterNextRender(() => this.ueberschrift().nativeElement.focus(), { injector: this.injector });
  }
}
