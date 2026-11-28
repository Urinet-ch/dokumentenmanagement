import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { forkJoin, map, of, catchError } from 'rxjs';
import {
  QuelleAnlegen,
  QuelleAendern,
  QuelleGeheimnis,
  QuellenService,
  Quelllauf,
  QuelllaufStarten,
  Quellzaehler,
  Registerquelle,
} from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';

interface LaufErgebnis {
  readonly laeufe: readonly Quelllauf[];
  readonly fehler: boolean;
}

interface QuellenFormular {
  readonly code: string;
  readonly name: string;
  readonly typ: QuelleAnlegen.TypEnum;
  readonly konfiguration: string;
  readonly bemerkung: string;
}

interface Bearbeitung {
  readonly code: string;
  readonly status: Registerquelle.StatusEnum;
  readonly konfiguration: string;
  readonly bemerkung: string;
}

const TYPEN: readonly QuelleAnlegen.TypEnum[] = [
  QuelleAnlegen.TypEnum.Generator,
  QuelleAnlegen.TypEnum.Drive,
  QuelleAnlegen.TypEnum.Ordner,
  QuelleAnlegen.TypEnum.MacEingang,
  QuelleAnlegen.TypEnum.GmailLabel,
  QuelleAnlegen.TypEnum.CrmCsv,
  QuelleAnlegen.TypEnum.Scan,
];

const STATUS: readonly Registerquelle.StatusEnum[] = [
  Registerquelle.StatusEnum.Offen,
  Registerquelle.StatusEnum.Laeuft,
  Registerquelle.StatusEnum.Abgeschlossen,
  Registerquelle.StatusEnum.Laufend,
];

const ZAEHLERFELDER: readonly (keyof Quellzaehler)[] = [
  'gefunden',
  'neu',
  'dublettePruefsumme',
  'dubletteFachlich',
  'uebersprungen',
  'fehler',
];

@Component({
  selector: 'app-quellen',
  imports: [MatButtonModule, MatProgressBarModule],
  templateUrl: './quellen.html',
  styleUrl: './quellen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuellenSeite implements OnInit {
  private readonly quellenApi = inject(QuellenService);
  private readonly anmeldung = inject(AnmeldungService);

  protected readonly texte = TEXTE.quellen;
  protected readonly gruppe = TEXTE.menueGruppen.verwaltung;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly typen = TYPEN;
  protected readonly stati = STATUS;
  protected readonly zaehlerFelder = ZAEHLERFELDER;
  protected readonly darfPflegen = computed(() => this.anmeldung.darf('A28'));
  protected readonly quellen = signal<readonly Registerquelle[] | null>(null);
  protected readonly laeufe = signal<Readonly<Record<string, LaufErgebnis>> | null>(null);
  protected readonly bearbeitung = signal<Bearbeitung | null>(null);
  protected readonly neueQuelleOffen = signal(false);
  protected readonly formular = signal<QuellenFormular>({
    code: '',
    name: '',
    typ: QuelleAnlegen.TypEnum.Drive,
    konfiguration: '{}',
    bemerkung: '',
  });
  protected readonly bestaetigungEcht = signal<string | null>(null);
  protected readonly aktionLaeuft = signal<string | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.fehler.set(null);
    this.quellen.set(null);
    this.laeufe.set(null);
    this.quellenApi.apiV1QuellenGet().subscribe({
      next: (quellen) => {
        this.quellen.set(quellen);
        if (quellen.length === 0) {
          this.laeufe.set({});
          return;
        }
        forkJoin(
          quellen.map((quelle) =>
            this.quellenApi.apiV1QuellenCodeLaeufeGet(quelle.code).pipe(
              map((laeufe) => ({ code: quelle.code, ergebnis: { laeufe, fehler: false } })),
              catchError(() => of({ code: quelle.code, ergebnis: { laeufe: [], fehler: true } })),
            ),
          ),
        ).subscribe((ergebnisse) => {
          this.laeufe.set(Object.fromEntries(ergebnisse.map(({ code, ergebnis }) => [code, ergebnis])));
        });
      },
      error: (fehler: unknown) => this.fehler.set(this.fehlertext(fehler, this.texte.fehlerLaden)),
    });
  }

  protected bearbeiten(quelle: Registerquelle): void {
    if (!this.darfPflegen()) return;
    this.fehler.set(null);
    this.bearbeitung.set({
      code: quelle.code,
      status: quelle.status,
      konfiguration: JSON.stringify(quelle.konfiguration, null, 2),
      bemerkung: quelle.bemerkung ?? '',
    });
  }

  protected abbrechen(): void {
    this.bearbeitung.set(null);
    this.fehler.set(null);
  }

  protected bearbeitungsTextAendern(feld: 'konfiguration' | 'bemerkung', ereignis: Event): void {
    const ziel = ereignis.target;
    if (!(ziel instanceof HTMLInputElement || ziel instanceof HTMLTextAreaElement)) return;
    this.bearbeitung.update((aktuell) =>
      aktuell ? { ...aktuell, [feld]: ziel.value } : aktuell,
    );
  }

  protected statusAendern(ereignis: Event): void {
    const ziel = ereignis.target;
    if (!(ziel instanceof HTMLSelectElement)) return;
    const status = STATUS.find((wert) => wert === ziel.value);
    if (!status) return;
    this.bearbeitung.update((aktuell) => (aktuell ? { ...aktuell, status } : aktuell));
  }

  protected speichern(quelle: Registerquelle): void {
    const bearbeitung = this.bearbeitung();
    if (!this.darfPflegen() || !bearbeitung || bearbeitung.code !== quelle.code || this.aktionLaeuft()) return;
    const konfiguration = this.konfiguration(bearbeitung.konfiguration);
    if (!konfiguration) {
      this.fehler.set(this.texte.konfigurationUngueltig);
      return;
    }
    this.fehler.set(null);
    this.meldung.set(null);
    this.aktionLaeuft.set(`speichern:${quelle.code}`);
    const aenderung: QuelleAendern = {
      status: bearbeitung.status,
      bemerkung: bearbeitung.bemerkung,
      konfiguration,
    };
    this.quellenApi
      .apiV1QuellenCodePatch(quelle.code, `"${quelle.version}"`, aenderung)
      .subscribe({
        next: () => {
          this.bearbeitung.set(null);
          this.aktionLaeuft.set(null);
          this.meldung.set(this.texte.gespeichert);
          this.laden();
        },
        error: (fehler: unknown) => {
          this.aktionLaeuft.set(null);
          this.fehler.set(this.fehlertext(fehler, this.texte.bearbeitenFehler));
        },
      });
  }

  protected geheimnisSpeichern(quelle: Registerquelle, geheimnis: string): void {
    if (!this.darfPflegen() || !geheimnis.trim() || this.aktionLaeuft()) {
      if (!geheimnis.trim()) this.fehler.set(this.texte.geheimnisFehlt);
      return;
    }
    this.fehler.set(null);
    this.meldung.set(null);
    this.aktionLaeuft.set(`geheimnis:${quelle.code}`);
    const anfrage: QuelleGeheimnis = { geheimnis };
    this.quellenApi.apiV1QuellenCodeGeheimnisPut(quelle.code, anfrage).subscribe({
      next: () => {
        this.aktionLaeuft.set(null);
        this.meldung.set(this.texte.geheimnisGespeichert);
        this.laden();
      },
      error: (fehler: unknown) => {
        this.aktionLaeuft.set(null);
        this.fehler.set(this.fehlertext(fehler, this.texte.fehlerAllgemein));
      },
    });
  }

  protected laufStarten(quelle: Registerquelle, art: QuelllaufStarten.ArtEnum): void {
    if (!this.darfPflegen() || this.aktionLaeuft()) return;
    if (art === QuelllaufStarten.ArtEnum.Echt && this.bestaetigungEcht() !== quelle.code) {
      this.bestaetigungEcht.set(quelle.code);
      return;
    }
    this.fehler.set(null);
    this.meldung.set(null);
    this.aktionLaeuft.set(`lauf:${quelle.code}`);
    this.bestaetigungEcht.set(null);
    this.quellenApi.apiV1QuellenCodeLaeufePost(quelle.code, { art }).subscribe({
      next: (lauf) => {
        this.aktionLaeuft.set(null);
        this.meldung.set(this.texte.laufGestartet(lauf.id));
        this.laden();
      },
      error: (fehler: unknown) => {
        this.aktionLaeuft.set(null);
        this.fehler.set(this.fehlertext(fehler, this.texte.laufFehler));
      },
    });
  }

  protected echtlaufAbbrechen(): void {
    this.bestaetigungEcht.set(null);
  }

  protected textAendern(feld: 'code' | 'name' | 'konfiguration' | 'bemerkung', ereignis: Event): void {
    const ziel = ereignis.target;
    if (!(ziel instanceof HTMLInputElement || ziel instanceof HTMLTextAreaElement)) return;
    this.formular.update((aktuell) => ({ ...aktuell, [feld]: ziel.value }));
  }

  protected typAendern(ereignis: Event): void {
    const ziel = ereignis.target;
    if (!(ziel instanceof HTMLSelectElement)) return;
    const typ = TYPEN.find((wert) => wert === ziel.value);
    if (typ) this.formular.update((aktuell) => ({ ...aktuell, typ }));
  }

  protected quelleAnlegen(ereignis: Event): void {
    ereignis.preventDefault();
    if (!this.darfPflegen() || this.aktionLaeuft()) return;
    const formular = this.formular();
    const konfiguration = this.konfiguration(formular.konfiguration);
    if (!konfiguration) {
      this.fehler.set(this.texte.konfigurationUngueltig);
      return;
    }
    this.fehler.set(null);
    this.meldung.set(null);
    this.aktionLaeuft.set('anlegen');
    const anfrage: QuelleAnlegen = {
      code: formular.code.trim(),
      name: formular.name.trim(),
      typ: formular.typ,
      konfiguration,
      bemerkung: formular.bemerkung.trim(),
    };
    this.quellenApi.apiV1QuellenPost(anfrage).subscribe({
      next: () => {
        this.aktionLaeuft.set(null);
        this.neueQuelleOffen.set(false);
        this.formular.set({
          code: '',
          name: '',
          typ: QuelleAnlegen.TypEnum.Drive,
          konfiguration: '{}',
          bemerkung: '',
        });
        this.meldung.set(this.texte.quelleAngelegt);
        this.laden();
      },
      error: (fehler: unknown) => {
        this.aktionLaeuft.set(null);
        this.fehler.set(this.fehlertext(fehler, this.texte.anlegenFehler));
      },
    });
  }

  protected quelleHinzufuegen(): void {
    if (!this.darfPflegen()) return;
    this.fehler.set(null);
    this.neueQuelleOffen.update((offen) => !offen);
  }

  protected zaehlerLabel(feld: keyof Quellzaehler): string {
    return this.texte.zaehler[feld];
  }

  protected statusLabel(status: Registerquelle.StatusEnum | Quelllauf.StatusEnum): string {
    return this.texte.stati[status] ?? status;
  }

  protected typLabel(typ: Registerquelle.TypEnum): string {
    return this.texte.typen[typ] ?? typ;
  }

  protected konfigurationText(konfiguration: object): string {
    return JSON.stringify(konfiguration, null, 2);
  }

  protected laufArtLabel(art: Quelllauf.ArtEnum): string {
    return this.texte.laufArten[art] ?? art;
  }

  protected laeufeFuer(code: string): LaufErgebnis | null {
    return this.laeufe()?.[code] ?? null;
  }

  protected aktionIst(aktion: string): boolean {
    return this.aktionLaeuft() === aktion;
  }

  private konfiguration(text: string): object | null {
    try {
      const wert: unknown = JSON.parse(text);
      return typeof wert === 'object' && wert !== null && !Array.isArray(wert) ? wert : null;
    } catch {
      return null;
    }
  }

  private fehlertext(fehler: unknown, standard: string): string {
    if (!(fehler instanceof HttpErrorResponse)) return standard;
    if (fehler.status === 401) return this.texte.fehlerSitzung;
    if (fehler.status === 403) return this.texte.fehlerRecht;
    if (fehler.status === 0) return this.texte.fehlerVerbindung;
    const code = this.problemcode(fehler);
    if (code === 'VERSION_KONFLIKT' || code === 'VERSION_FEHLT') return this.texte.versionKonflikt;
    if (code === 'QUELLE_LAEUFT') return this.texte.quelleLaeuft;
    if (code === 'QUELLE_EXISTIERT') return this.texte.quelleExistiert;
    return standard;
  }

  private problemcode(fehler: HttpErrorResponse): string | null {
    if (typeof fehler.error !== 'object' || fehler.error === null) return null;
    const code = (fehler.error as Record<string, unknown>)['fehlercode'];
    return typeof code === 'string' ? code : null;
  }
}
