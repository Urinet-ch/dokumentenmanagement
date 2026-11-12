import { HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ImportLauf, ImporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ImportArt, MAX_GROESSE, fehlerText, groesse, zahl, zeit } from './import-logik';

const SEITE = 20;

/** Seite «Importe»: CSV hochladen und Liste der Läufe (Aufgabe 4.4; Req 13.2, 13.7, 13.10; Recht A12). */
@Component({
  selector: 'app-importe',
  imports: [MatButtonModule, MatCheckboxModule, MatProgressBarModule, RouterLink],
  templateUrl: './importe.html',
  styleUrl: './importe.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Importe implements OnInit {
  private readonly importe = inject(ImporteService);
  private readonly router = inject(Router);
  private laufend?: Subscription;

  protected readonly texte = TEXTE.importe;
  protected readonly zahl = zahl;
  protected readonly zeit = zeit;

  protected readonly art = signal<ImportArt>('kontakt');
  protected readonly vollexport = signal(false);
  protected readonly datei = signal<File | null>(null);
  protected readonly laeuft = signal(false);
  protected readonly prozent = signal(0);
  protected readonly fehler = signal<string | null>(null);

  protected readonly laeufe = signal<readonly ImportLauf[] | null>(null);
  protected readonly gesamt = signal(0);
  protected readonly seite = signal(0);
  protected readonly listeFehler = signal<string | null>(null);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.laufend?.unsubscribe());
  }

  ngOnInit(): void {
    this.laden(0);
  }

  protected artWaehlen(art: string): void {
    this.art.set(art === 'verkaufschance' ? 'verkaufschance' : 'kontakt');
    if (art === 'verkaufschance') {
      this.vollexport.set(false);
    }
  }

  protected gewaehlt(eingabe: HTMLInputElement): void {
    const datei = eingabe.files?.[0] ?? null;
    eingabe.value = '';
    this.fehler.set(null);
    // Grenze vorab prüfen, ohne die Datei zu lesen (Req 13.10); der Server prüft sie ebenfalls
    if (datei && datei.size > MAX_GROESSE) {
      this.datei.set(null);
      this.fehler.set(this.texte.zuGross(groesse(datei.size)));
      return;
    }
    this.datei.set(datei);
  }

  protected hochladen(): void {
    const datei = this.datei();
    if (!datei || this.laeuft()) {
      return;
    }
    this.laeuft.set(true);
    this.prozent.set(0);
    this.fehler.set(null);
    const vollexport = this.art() === 'kontakt' && this.vollexport() ? 'true' : 'false';
    this.laufend = this.importe.apiV1ImporteCrmPost(this.art(), vollexport, datei, 'events', true).subscribe({
      next: (ereignis) => {
        if (ereignis.type === HttpEventType.UploadProgress) {
          this.prozent.set(ereignis.total ? Math.round((100 * ereignis.loaded) / ereignis.total) : 0);
        } else if (ereignis.type === HttpEventType.Response) {
          this.laeuft.set(false);
          const id = ereignis.body?.lauf?.id;
          if (id) {
            void this.router.navigate(['/importe', id]);
          } else {
            this.fehler.set(this.texte.fehlerAllgemein);
          }
        }
      },
      error: (fehler: unknown) => {
        this.laeuft.set(false);
        this.fehler.set(fehlerText(fehler));
      },
    });
  }

  protected laden(seite: number): void {
    this.listeFehler.set(null);
    this.importe.apiV1ImporteGet(SEITE, seite).subscribe({
      next: (antwort) => {
        this.laeufe.set(antwort.eintraege ?? []);
        this.gesamt.set(antwort.gesamt ?? 0);
        this.seite.set(seite);
      },
      error: (fehler: unknown) => this.listeFehler.set(fehlerText(fehler)),
    });
  }

  protected bereich(): { von: string; bis: string; gesamt: string } {
    const von = this.seite() * SEITE + 1;
    const bis = Math.min(this.gesamt(), von + (this.laeufe()?.length ?? 0) - 1);
    return { von: zahl(von), bis: zahl(bis), gesamt: zahl(this.gesamt()) };
  }

  protected hatWeitere(): boolean {
    return (this.seite() + 1) * SEITE < this.gesamt();
  }
}
