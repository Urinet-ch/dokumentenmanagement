import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Quelle, WissensbasisService } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-wissen-quellen',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './wissen-quellen.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenQuellen {
  private readonly api = inject(WissensbasisService);
  private readonly destroy = inject(DestroyRef);
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly texte = TEXTE.wissen.quellenListe;
  protected readonly quellen = signal<Quelle[] | null>(null);
  protected readonly laedt = signal(false);
  protected readonly ladeFehler = signal<string | null>(null);
  protected readonly entfernenFehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly entferntId = signal<string | null>(null);
  protected readonly zeitpunkt = zeitpunkt;
  private anfrage = 0;

  constructor() {
    this.laden();
  }

  protected laden(): void {
    const nummer = ++this.anfrage;
    this.quellen.set(null);
    this.ladeFehler.set(null);
    this.entfernenFehler.set(null);
    this.meldung.set(null);
    this.laedt.set(true);
    this.api.apiV1WissenQuellenGet().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (quellen) => {
        if (nummer !== this.anfrage) return;
        this.quellen.set(quellen);
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        if (nummer !== this.anfrage) return;
        this.ladeFehler.set(httpFehlerMeldung(fehler));
        this.laedt.set(false);
      },
    });
  }

  protected titel(quelle: Quelle): string {
    return quelle.titel || this.texte.ohneTitel;
  }

  protected ingestStatus(status: string | null | undefined): string {
    return status ? (this.texte.ingestStatus[status] ?? this.texte.statusUnbekannt) : this.texte.keinIngest;
  }

  protected ingestStatusKlasse(status: string | null | undefined): string {
    if (status === 'ERLEDIGT') return 'ok';
    if (status === 'FEHLER' || status === 'ABGELEHNT') return 'gefahr';
    if (status === 'WARTEND' || status === 'IN_ARBEIT') return 'info';
    return '';
  }

  protected entfernen(quelle: Quelle): void {
    const id = quelle.dokumentId;
    if (!id || !this.anmeldung.darf('A16') || this.entferntId()) return;
    this.entfernenFehler.set(null);
    this.meldung.set(null);
    this.entferntId.set(id);
    this.api.apiV1DokumenteIdWissensquelleDelete(id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => {
        this.quellen.update((liste) => liste?.filter((eintrag) => eintrag.dokumentId !== id) ?? []);
        this.entferntId.set(null);
        this.meldung.set(this.texte.entfernt);
      },
      error: (fehler: unknown) => {
        this.entferntId.set(null);
        this.entfernenFehler.set(httpFehlerMeldung(fehler));
      },
    });
  }
}
