import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Auswirkung, EinstellungskatalogEintrag, EinstellungenErgebnis, EinstellungenService, EinstellungenVorschau } from '../../gemeinsam/api';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungenVerlauf } from './einstellungen-verlauf';
import { EinstellungenEingabeFehler, einstellungenPatch } from './einstellungen-patch';
import { EinstellungenBestaetigung } from './einstellungen-bestaetigung';
import { EinstellungenFortsetzen, EinstellungsFortsetzungFehler } from './einstellungen-fortsetzung';

interface Einstellungsgruppe {
  code: string;
  eintraege: EinstellungskatalogEintrag[];
}

@Component({
  selector: 'app-einstellungen',
  imports: [MatButtonModule, MatDialogModule, EinstellungenVerlauf],
  templateUrl: './einstellungen.html',
  styleUrl: './einstellungen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EinstellungenSeite implements OnInit {
  private readonly api = inject(EinstellungenService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly fortsetzung = inject(EinstellungenFortsetzen);
  private readonly zerstoert = inject(DestroyRef);
  private etag: string | null = null;
  private aenderungsnummer = 0;
  private fortsetzungGeprueft = false;

  protected readonly texte = TEXTE.einstellungen;
  protected readonly bereich = TEXTE.menueGruppen.verwaltung;
  protected readonly gruppen = signal<Einstellungsgruppe[] | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly eingaben = signal<Readonly<Record<string, string | null>>>({});
  protected readonly hatEingaben = computed(() => Object.keys(this.eingaben()).length > 0);
  protected readonly feldfehler = signal<Readonly<Record<string, string>>>({});
  protected readonly vorschau = signal<readonly Auswirkung[] | null>(null);
  protected readonly vorschauLaeuft = signal(false);
  protected readonly speichert = signal(false);
  protected readonly gespeichert = signal(false);
  protected readonly wochentage = Object.entries(TEXTE.einstellungen.tage);

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.aenderungsnummer++;
    this.fehler.set(null);
    this.gruppen.set(null);
    this.vorschau.set(null);
    this.etag = null;
    this.api.apiV1MandantEinstellungenGet('response').pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        const katalog = antwort.body?.katalog;
        const etag = antwort.headers.get('ETag');
        if (!Array.isArray(katalog) || !etag) {
          this.fehler.set(this.texte.ungueltig);
          return;
        }
        const gruppen = new Map<string, EinstellungskatalogEintrag[]>();
        for (const eintrag of katalog) {
          if (!eintrag.gruppe || !eintrag.schluessel || !eintrag.typ) {
            this.fehler.set(this.texte.ungueltig);
            return;
          }
          const eintraege = gruppen.get(eintrag.gruppe) ?? [];
          eintraege.push(eintrag);
          gruppen.set(eintrag.gruppe, eintraege);
        }
        this.etag = etag;
        this.gruppen.set([...gruppen].map(([code, eintraege]) => ({ code, eintraege })));
        this.fortsetzen();
      },
      error: (fehler: unknown) => {
        if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
          void this.router.navigateByUrl('/nicht-gefunden');
        } else {
          this.fehler.set(httpFehlerMeldung(fehler));
        }
      },
    });
  }

  protected eingabe(eintrag: EinstellungskatalogEintrag): string {
    const schluessel = eintrag.schluessel;
    if (!schluessel) {
      return '';
    }
    if (Object.hasOwn(this.eingaben(), schluessel)) {
      return this.eingaben()[schluessel] ?? '';
    }
    const wert: unknown = eintrag.aktuellerWert;
    if (typeof wert === 'string' || typeof wert === 'number') {
      return String(wert);
    }
    return Array.isArray(wert) && wert.every((element) => typeof element === 'string') ? wert.join('\n') : '';
  }

  protected setzen(eintrag: EinstellungskatalogEintrag, wert: string | null): void {
    const schluessel = eintrag.schluessel;
    if (!schluessel) {
      this.fehler.set(this.texte.ungueltig);
      return;
    }
    this.aenderungsnummer++;
    this.gespeichert.set(false);
    this.eingaben.update((bisher) => ({ ...bisher, [schluessel]: wert }));
    this.feldfehler.update((bisher) => {
      const neu = { ...bisher };
      delete neu[schluessel];
      return neu;
    });
    this.vorschau.set(null);
  }

  protected speichern(): void {
    const auswirkungen = this.vorschau();
    const etag = this.etag;
    if (!auswirkungen || !etag || this.speichert()) {
      return;
    }
    const eingaben = { ...this.eingaben() };
    const schluessel = this.auswirkungsSchluessel(auswirkungen, eingaben);
    if (schluessel === null) {
      this.fehler.set(this.texte.ungueltig);
      return;
    }
    if (auswirkungen.length > 0 || Object.values(eingaben).some((wert) => wert === null)) {
      this.bestaetigen(etag, eingaben, schluessel, auswirkungen, false);
    } else {
      this.schreiben(etag, eingaben, schluessel, false);
    }
  }

  private bestaetigen(etag: string, eingaben: Record<string, string | null>, schluessel: string[],
                      auswirkungen: readonly Auswirkung[], wiederholt: boolean): void {
    this.speichert.set(true);
    this.dialog.open(EinstellungenBestaetigung, { data: auswirkungen })
      .afterClosed().pipe(takeUntilDestroyed(this.zerstoert)).subscribe((bestaetigt: boolean | undefined) => {
        this.speichert.set(false);
        if (bestaetigt) {
          this.schreiben(etag, eingaben, schluessel, wiederholt);
        }
      });
  }

  private schreiben(etag: string, eingaben: Record<string, string | null>, bestaetigt: string[],
                    wiederholt: boolean): void {
    const gruppen = this.gruppen();
    if (!gruppen) {
      this.fehler.set(this.texte.ungueltig);
      return;
    }
    let patch: ReturnType<typeof einstellungenPatch>;
    try {
      patch = einstellungenPatch(gruppen.flatMap((gruppe) => gruppe.eintraege), eingaben);
    } catch (fehler: unknown) {
      if (!(fehler instanceof EinstellungenEingabeFehler)) {
        throw fehler;
      }
      this.feldfehler.set({ [fehler.schluessel]: this.texte.eingabeUngueltig });
      return;
    }
    this.speichert.set(true);
    const params = bestaetigt.length > 0 ? new HttpParams().set('bestaetigt', bestaetigt.join(',')) : new HttpParams();
    this.http.patch<EinstellungenErgebnis>('/api/v1/mandant/einstellungen', patch, {
      headers: new HttpHeaders({ 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' }), params,
    }).pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        this.speichert.set(false);
        if (!Number.isInteger(antwort.version) || typeof antwort.geaendert !== 'boolean') {
          this.fehler.set(this.texte.ungueltig);
          return;
        }
        this.eingaben.set({});
        this.feldfehler.set({});
        this.gespeichert.set(true);
        this.laden();
      },
      error: (fehler: unknown) => {
        this.speichert.set(false);
        if (fehler instanceof HttpErrorResponse && fehler.status === 401
            && fehler.error?.fehlercode === 'STEP_UP_NOETIG') {
          if (wiederholt) {
            this.fehler.set(this.texte.erneuteAnmeldungFehler);
          } else {
            this.erneutAnmelden(etag, eingaben, bestaetigt);
          }
        } else if (fehler instanceof HttpErrorResponse && fehler.status === 409
            && fehler.error?.fehlercode === 'BESTAETIGUNG_NOETIG' && Array.isArray(fehler.error.auswirkung)) {
          const auswirkungen: Auswirkung[] = fehler.error.auswirkung;
          const schluessel = this.auswirkungsSchluessel(auswirkungen, eingaben);
          if (schluessel === null) {
            this.fehler.set(this.texte.ungueltig);
          } else {
            this.bestaetigen(etag, eingaben, [...new Set([...bestaetigt, ...schluessel])], auswirkungen, wiederholt);
          }
        } else if (fehler instanceof HttpErrorResponse && fehler.status === 412) {
          this.fehler.set(this.texte.versionGeaendert);
        } else if (fehler instanceof HttpErrorResponse && fehler.status === 422
            && this.befundeAnzeigen(fehler.error)) {
          return;
        } else {
          this.fehler.set(httpFehlerMeldung(fehler));
        }
      },
    });
  }

  private auswirkungsSchluessel(auswirkungen: readonly Auswirkung[],
                               eingaben: Readonly<Record<string, string | null>>): string[] | null {
    const schluessel: string[] = [];
    for (const auswirkung of auswirkungen) {
      if (!auswirkung || typeof auswirkung.schluessel !== 'string' || typeof auswirkung.text !== 'string'
          || !auswirkung.text || !Object.hasOwn(eingaben, auswirkung.schluessel)) {
        return null;
      }
      schluessel.push(auswirkung.schluessel);
    }
    return [...new Set(schluessel)];
  }

  private erneutAnmelden(etag: string, eingaben: Record<string, string | null>, bestaetigt: string[]): void {
    const benutzer = this.anmeldung.benutzer();
    if (!benutzer?.id || !benutzer.mandant) {
      this.fehler.set(this.texte.erneuteAnmeldungFehler);
      return;
    }
    try {
      this.fortsetzung.merken({ benutzer: benutzer.id, mandant: benutzer.mandant, etag, eingaben,
        bestaetigt, erstellt: Date.now() });
    } catch (fehler: unknown) {
      if (!(fehler instanceof Error)) {
        throw fehler;
      }
      this.fehler.set(this.texte.erneuteAnmeldungFehler);
      return;
    }
    this.speichert.set(true);
    void this.anmeldung.anmelden('/verwaltung/einstellungen', true).catch((fehler: unknown) => {
      if (!(fehler instanceof Error)) {
        throw fehler;
      }
      this.fortsetzung.verwerfen();
      this.speichert.set(false);
      this.fehler.set(this.texte.erneuteAnmeldungFehler);
    });
  }

  private fortsetzen(): void {
    if (this.fortsetzungGeprueft) {
      return;
    }
    this.fortsetzungGeprueft = true;
    const benutzer = this.anmeldung.benutzer();
    if (!benutzer?.id || !benutzer.mandant) {
      this.fortsetzung.verwerfen();
      this.fehler.set(this.texte.erneuteAnmeldungFehler);
      return;
    }
    try {
      const vorgang = this.fortsetzung.entnehmen(benutzer.id, benutzer.mandant);
      if (vorgang) {
        this.eingaben.set(vorgang.eingaben);
        this.schreiben(vorgang.etag, vorgang.eingaben, vorgang.bestaetigt, true);
      }
    } catch (fehler: unknown) {
      if (!(fehler instanceof EinstellungsFortsetzungFehler) && !(fehler instanceof DOMException)) {
        throw fehler;
      }
      this.fehler.set(this.texte.erneuteAnmeldungFehler);
    }
  }

  protected pruefen(): void {
    const etag = this.etag;
    const gruppen = this.gruppen();
    if (!etag || !gruppen) {
      this.fehler.set(this.texte.ungueltig);
      return;
    }
    let patch: ReturnType<typeof einstellungenPatch>;
    try {
      patch = einstellungenPatch(gruppen.flatMap((gruppe) => gruppe.eintraege), this.eingaben());
    } catch (fehler: unknown) {
      if (!(fehler instanceof EinstellungenEingabeFehler)) {
        throw fehler;
      }
      this.feldfehler.set({ [fehler.schluessel]: this.texte.eingabeUngueltig });
      return;
    }
    const nummer = this.aenderungsnummer;
    this.vorschauLaeuft.set(true);
    this.vorschau.set(null);
    this.http.post<EinstellungenVorschau>('/api/v1/mandant/einstellungen/vorschau', patch, {
      headers: new HttpHeaders({ 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' }),
    }).pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        this.vorschauLaeuft.set(false);
        if (nummer !== this.aenderungsnummer) {
          return;
        }
        if (!Array.isArray(antwort.auswirkungen)) {
          this.fehler.set(this.texte.ungueltig);
          return;
        }
        this.feldfehler.set({});
        this.vorschau.set(antwort.auswirkungen);
      },
      error: (fehler: unknown) => {
        this.vorschauLaeuft.set(false);
        if (nummer !== this.aenderungsnummer) {
          return;
        }
        if (fehler instanceof HttpErrorResponse && fehler.status === 412) {
          this.fehler.set(this.texte.versionGeaendert);
        } else if (fehler instanceof HttpErrorResponse && fehler.status === 422
            && this.befundeAnzeigen(fehler.error)) {
          return;
        } else {
          this.fehler.set(httpFehlerMeldung(fehler));
        }
      },
    });
  }

  private befundeAnzeigen(antwort: unknown): boolean {
    if (antwort === null || typeof antwort !== 'object' || !('befunde' in antwort)
        || !Array.isArray(antwort.befunde)) {
      return false;
    }
    const bekannteSchluessel = new Set(this.gruppen()?.flatMap((gruppe) =>
      gruppe.eintraege.map((eintrag) => eintrag.schluessel)) ?? []);
    const meldungen: Record<string, string> = {};
    const befunde: unknown[] = antwort.befunde;
    for (const befund of befunde) {
      if (befund === null || typeof befund !== 'object' || !('pfad' in befund) || !('meldung' in befund)
          || typeof befund.pfad !== 'string'
          || typeof befund.meldung !== 'string' || !befund.pfad.startsWith('einstellungen.')) {
        return false;
      }
      const schluessel: string = befund.pfad.slice('einstellungen.'.length);
      if (!bekannteSchluessel.has(schluessel)) {
        return false;
      }
      meldungen[schluessel] = befund.meldung;
    }
    if (Object.keys(meldungen).length === 0) {
      return false;
    }
    this.feldfehler.set(meldungen);
    return true;
  }

  protected wert(wert: unknown): string {
    if (wert === null || wert === undefined) {
      return this.texte.nichtGesetzt;
    }
    if (typeof wert === 'number' && Number.isFinite(wert)) {
      return new Intl.NumberFormat('de-CH').format(wert).replaceAll('’', "'");
    }
    if (typeof wert === 'string') {
      return wert;
    }
    if (Array.isArray(wert) && wert.every((element) => typeof element === 'string')) {
      return wert.join(', ');
    }
    return this.texte.ungueltig;
  }
}
