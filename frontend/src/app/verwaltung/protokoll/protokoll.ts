import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ProtokollEintrag, ProtokollService, VerzeichnisEintrag } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import {
  AKTIONEN,
  Filter,
  LEERER_FILTER,
  SEITE,
  austrittMoeglich,
  fehlerText,
  filterFehler,
  parameter,
  person,
  wer,
} from './protokoll-logik';

/**
 * Seite «Zugriffsprotokoll» (Aufgabe 5.1; Req 10.5, 10.10; Recht A13): Einträge mit Filter nach Zeitraum, Kennung,
 * Dokument und Aktion, dazu das Benutzerverzeichnis mit Austritt.
 */
@Component({
  selector: 'app-protokoll',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './protokoll.html',
  styleUrl: './protokoll.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Protokoll implements OnInit {
  private readonly protokoll = inject(ProtokollService);

  protected readonly texte = TEXTE.protokoll;
  protected readonly aktionen = AKTIONEN;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly wer = wer;
  protected readonly person = person;

  protected readonly filter = signal<Filter>(LEERER_FILTER);
  protected readonly eintraege = signal<readonly ProtokollEintrag[] | null>(null);
  protected readonly gesamt = signal(0);
  protected readonly seite = signal(0);
  protected readonly fehler = signal<string | null>(null);

  protected readonly verzeichnis = signal<readonly VerzeichnisEintrag[] | null>(null);
  protected readonly verzeichnisFehler = signal<string | null>(null);
  protected readonly austrittFuer = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly laeuft = signal(false);

  /** Kennungen mit dem zuletzt bekannten Namen, als Vorschläge für den Filter. */
  protected readonly kennungen = computed(() => {
    const letzte = new Map<string, VerzeichnisEintrag>();
    for (const zeile of this.verzeichnis() ?? []) {
      letzte.set(zeile.benutzer, zeile);
    }
    return [...letzte.values()];
  });

  ngOnInit(): void {
    this.laden(0);
    this.verzeichnisLaden();
  }

  protected setzen(feld: keyof Filter, wert: string): void {
    this.filter.update((f) => ({ ...f, [feld]: wert }));
  }

  protected anzeigen(): void {
    this.laden(0);
  }

  protected zuruecksetzen(): void {
    this.filter.set(LEERER_FILTER);
    this.laden(0);
  }

  protected laden(seite: number): void {
    const fehler = filterFehler(this.filter());
    if (fehler) {
      this.fehler.set(fehler);
      return;
    }
    this.fehler.set(null);
    const p = parameter(this.filter());
    this.protokoll.apiV1ProtokollGet(p.aktion, p.benutzer, p.bis, p.dokument, SEITE, seite, p.von).subscribe({
      next: (antwort) => {
        this.eintraege.set(antwort.eintraege ?? []);
        this.gesamt.set(antwort.gesamt ?? 0);
        this.seite.set(seite);
      },
      error: (f: unknown) => this.fehler.set(fehlerText(f)),
    });
  }

  protected bereich(): { von: string; bis: string; gesamt: string } {
    const von = this.seite() * SEITE + 1;
    const bis = Math.min(this.gesamt(), von + (this.eintraege()?.length ?? 0) - 1);
    return { von: zahl(von), bis: zahl(bis), gesamt: zahl(this.gesamt()) };
  }

  protected hatWeitere(): boolean {
    return (this.seite() + 1) * SEITE < this.gesamt();
  }

  protected aktionText(aktion: string): string {
    return this.texte.aktionen[aktion] ?? aktion;
  }

  protected austrittMoeglich(zeile: VerzeichnisEintrag): boolean {
    return austrittMoeglich(zeile, this.verzeichnis() ?? []);
  }

  protected austritt(zeile: VerzeichnisEintrag): void {
    this.laeuft.set(true);
    this.verzeichnisFehler.set(null);
    this.protokoll.apiV1ProtokollBenutzerBenutzerAustrittPost(zeile.benutzer).subscribe({
      next: () => {
        this.laeuft.set(false);
        this.austrittFuer.set(null);
        this.meldung.set(this.texte.austrittErfasst(zeile.name || zeile.benutzer));
        this.verzeichnisLaden();
      },
      error: (f: unknown) => {
        this.laeuft.set(false);
        this.austrittFuer.set(null);
        this.verzeichnisFehler.set(fehlerText(f));
        this.verzeichnisLaden();
      },
    });
  }

  private verzeichnisLaden(): void {
    this.protokoll.apiV1ProtokollBenutzerGet().subscribe({
      next: (zeilen) => this.verzeichnis.set(zeilen),
      error: (f: unknown) => this.verzeichnisFehler.set(fehlerText(f)),
    });
  }
}

function zahl(wert: number): string {
  return String(wert).replace(/\B(?=(\d{3})+(?!\d))/g, "'");
}
