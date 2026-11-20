import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ArtKlassifikation } from '../../gemeinsam/api/model/art-klassifikation';
import { Dokumentart } from '../../gemeinsam/api/model/dokumentart';
import { DokumentartAenderung } from '../../gemeinsam/api/model/dokumentart-aenderung';
import { Klassifikation } from '../../gemeinsam/api/model/klassifikation';
import { KlassifikationWert } from '../../gemeinsam/api/model/klassifikation-wert';
import { KonfigurationsdateiAnalyse } from '../../gemeinsam/api/model/konfigurationsdatei-analyse';
import { KonfigurationsVersion } from '../../gemeinsam/api/model/konfigurations-version';
import { Regel } from '../../gemeinsam/api/model/regel';
import { RegelAenderung } from '../../gemeinsam/api/model/regel-aenderung';
import { RegelAnlegen } from '../../gemeinsam/api/model/regel-anlegen';
import { DokumentartenService } from '../../gemeinsam/api/api/dokumentarten.service';
import { KonfigurationService } from '../../gemeinsam/api/api/konfiguration.service';
import {
  allgemeineFehlermeldung,
  feldEditorFehler,
  pflichtfelderOrdnen,
  SchemaEditor,
  schemaAusFeldern,
  schemaEditor,
  versionsvergleich,
  verwaltungsFehlerart,
  Versionsaenderung,
  zusatzPflichtfelder,
} from './verwaltung-logik';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { DokumentKlassifikationenService } from '../../dokument/klassifikationen';

interface ArtForm {
  code: string;
  name: string;
  beschreibung: string;
  gruppen: string[];
  /** Im JSON-Modus mit `zusatz.*`, im Feld-Modus ohne (dort setzt der Feld-Editor die Pflicht). */
  pflichtfelder: string;
  /** Feld-Modus: `zusatz.*` aus den Pflichtfeldern ohne passendes Feld im Schema; bleiben beim Speichern erhalten. */
  pflichtZusatzOhneFeld: string[];
  schema: SchemaEditor;
  vertraulichkeit: Dokumentart.VertraulichkeitEnum;
  /** `type="number"`: Angular liefert eine Zahl, bei leerem Feld `null`. */
  aufbewahrungJahre: string | number | null;
  unbefristet: boolean;
  aufbewahrungAb: string;
  aufbewahrungFeld: string;
  gesetzlichePflicht: boolean;
  aktiv: boolean;
  selbstAusgestellt: boolean;
  belegnummerMuster: string;
  fremdbelegArt: string;
  ohneZahlungsdatenArt: string;
  kiVorschlag: boolean;
  kiFelder: string;
  kiHinweise: string;
  reihenfolge: string | number | null;
  grund: string;
}

interface KlassifikationForm {
  code: string;
  name: string;
  beschreibung: string;
  mehrfach: boolean;
  kiVorschlag: boolean;
  suchfilter: boolean;
  oeffentlichFilter: boolean;
  aktiv: boolean;
  grund: string;
}

interface WertForm {
  code: string;
  name: string;
  beschreibung: string;
  reihenfolge: string | number | null;
  aktiv: boolean;
  grund: string;
}

interface ZuordnungForm {
  klassifikation: string;
  pflicht: boolean;
  standardWert: string;
}

interface RegelForm {
  code: string;
  art: string;
  typ: RegelAnlegen.TypEnum;
  parameter: string;
  schwere: RegelAnlegen.SchwereEnum;
  meldung: string;
  aktiv: boolean;
  grund: string;
}

function objekt(wert: unknown): Record<string, unknown> | null {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : null;
}

function zahlText(wert: string | number | null | undefined): string {
  return wert == null ? '' : String(wert).trim();
}

function zeilen(text: string): string[] {
  return text
    .split('\n')
    .map((zeile) => zeile.trim())
    .filter((zeile) => zeile !== '');
}

function istRegelTyp(wert: string | undefined): wert is RegelAnlegen.TypEnum {
  return wert !== undefined && Object.values(RegelAnlegen.TypEnum).some((typ) => typ === wert);
}

function istRegelSchwere(wert: string | undefined): wert is RegelAnlegen.SchwereEnum {
  return (
    wert !== undefined &&
    Object.values(RegelAnlegen.SchwereEnum).some((schwere) => schwere === wert)
  );
}

function regelFormLeer(): RegelForm {
  return {
    code: '',
    art: '',
    typ: RegelAnlegen.TypEnum.PflichtWenn,
    parameter: '{}',
    schwere: RegelAnlegen.SchwereEnum.Warnung,
    meldung: '',
    aktiv: true,
    grund: '',
  };
}

function regelFormAus(regel: Regel): RegelForm | null {
  const parameter = objekt(regel.parameter);
  if (
    typeof regel.code !== 'string' ||
    !istRegelTyp(regel.typ) ||
    !istRegelSchwere(regel.schwere) ||
    typeof regel.meldung !== 'string' ||
    typeof regel.aktiv !== 'boolean' ||
    parameter === null
  ) {
    return null;
  }
  return {
    code: regel.code,
    art: regel.art ?? '',
    typ: regel.typ,
    parameter: JSON.stringify(parameter, null, 2),
    schwere: regel.schwere,
    meldung: regel.meldung,
    aktiv: regel.aktiv,
    grund: '',
  };
}

function artFormLeer(): ArtForm {
  return {
    code: '',
    name: '',
    beschreibung: '',
    gruppen: [],
    pflichtfelder: '',
    pflichtZusatzOhneFeld: [],
    schema: { modus: 'felder', felder: [] },
    vertraulichkeit: 'NORMAL',
    aufbewahrungJahre: '10',
    unbefristet: false,
    aufbewahrungAb: 'GESCHAEFTSJAHR',
    aufbewahrungFeld: '',
    gesetzlichePflicht: false,
    aktiv: true,
    selbstAusgestellt: false,
    belegnummerMuster: '',
    fremdbelegArt: '',
    ohneZahlungsdatenArt: '',
    kiVorschlag: false,
    kiFelder: '',
    kiHinweise: '',
    reihenfolge: '1',
    grund: '',
  };
}

function artFormAus(art: Dokumentart): ArtForm {
  const schema = schemaEditor(art);
  const feldCodes = new Set(
    schema.modus === 'felder' ? schema.felder.map((feld) => feld.code) : [],
  );
  return {
    code: art.code,
    name: art.name,
    beschreibung: art.beschreibung ?? '',
    gruppen: [...art.gruppen],
    pflichtfelder: (schema.modus === 'json'
      ? art.pflichtfelder
      : art.pflichtfelder.filter((feld) => !feld.startsWith('zusatz.'))
    ).join('\n'),
    pflichtZusatzOhneFeld:
      schema.modus === 'json'
        ? []
        : art.pflichtfelder.filter(
            (feld) =>
              feld.startsWith('zusatz.') && !feldCodes.has(feld.substring('zusatz.'.length)),
          ),
    schema,
    vertraulichkeit: art.vertraulichkeit,
    aufbewahrungJahre: art.aufbewahrungJahre == null ? '' : String(art.aufbewahrungJahre),
    unbefristet: art.aufbewahrungJahre == null,
    aufbewahrungAb: art.aufbewahrungAb ?? 'GESCHAEFTSJAHR',
    aufbewahrungFeld: art.aufbewahrungFeld ?? '',
    gesetzlichePflicht: art.gesetzlichePflicht,
    aktiv: art.aktiv,
    selbstAusgestellt: art.selbstAusgestellt,
    belegnummerMuster: art.belegnummerMuster.join('\n'),
    fremdbelegArt: art.fremdbelegArt ?? '',
    ohneZahlungsdatenArt: art.ohneZahlungsdatenArt ?? '',
    kiVorschlag: art.kiVorschlag,
    kiFelder: art.kiFelder.join('\n'),
    kiHinweise: art.kiHinweise ?? '',
    reihenfolge: String(art.reihenfolge),
    grund: '',
  };
}

function klassifikationsListe(wert: unknown): Klassifikation[] {
  if (!Array.isArray(wert)) {
    return [];
  }
  return wert.flatMap((eintrag): Klassifikation[] => {
    const daten = objekt(eintrag);
    if (daten === null || typeof daten['code'] !== 'string' || typeof daten['name'] !== 'string') {
      return [];
    }
    return [
      {
        code: daten['code'],
        name: daten['name'],
        beschreibung: typeof daten['beschreibung'] === 'string' ? daten['beschreibung'] : '',
        werte: werteListe(daten['werte']),
        mehrfach: daten['mehrfach'] === true,
        kiVorschlag: daten['kiVorschlag'] === true,
        suchfilter: daten['suchfilter'] === true,
        oeffentlichFilter: daten['oeffentlichFilter'] === true,
        aktiv: daten['aktiv'] !== false,
      },
    ];
  });
}

function werteListe(wert: unknown): KlassifikationWert[] {
  if (!Array.isArray(wert)) {
    return [];
  }
  return wert.flatMap((eintrag): KlassifikationWert[] => {
    const daten = objekt(eintrag);
    if (daten === null || typeof daten['code'] !== 'string' || typeof daten['name'] !== 'string') {
      return [];
    }
    return [
      {
        code: daten['code'],
        name: daten['name'],
        beschreibung: typeof daten['beschreibung'] === 'string' ? daten['beschreibung'] : '',
        reihenfolge: typeof daten['reihenfolge'] === 'number' ? daten['reihenfolge'] : 0,
        aktiv: daten['aktiv'] !== false,
      },
    ];
  });
}

function zuordnungenListe(wert: unknown): ArtKlassifikation[] {
  if (!Array.isArray(wert)) {
    return [];
  }
  return wert.flatMap((eintrag): ArtKlassifikation[] => {
    const daten = objekt(eintrag);
    if (daten === null || typeof daten['klassifikation'] !== 'string') {
      return [];
    }
    return [
      {
        klassifikation: daten['klassifikation'],
        pflicht: daten['pflicht'] === true,
        standardWert: typeof daten['standardWert'] === 'string' ? daten['standardWert'] : undefined,
      },
    ];
  });
}

function versionenListe(wert: unknown): KonfigurationsVersion[] {
  if (!Array.isArray(wert)) {
    return [];
  }
  return wert.flatMap((eintrag): KonfigurationsVersion[] => {
    const daten = objekt(eintrag);
    if (
      daten === null ||
      typeof daten['version'] !== 'number' ||
      typeof daten['zeit'] !== 'string' ||
      typeof daten['wer'] !== 'string' ||
      typeof daten['quelle'] !== 'string' ||
      typeof daten['sha256'] !== 'string'
    ) {
      return [];
    }
    return [
      {
        version: daten['version'],
        zeit: daten['zeit'],
        wer: daten['wer'],
        grund: typeof daten['grund'] === 'string' ? daten['grund'] : undefined,
        quelle: daten['quelle'] as KonfigurationsVersion.QuelleEnum,
        sha256: daten['sha256'],
      },
    ];
  });
}

function analysiereAntwort(wert: unknown): KonfigurationsdateiAnalyse | null {
  const daten = objekt(wert);
  if (daten === null) {
    return null;
  }
  return {
    aktuelleVersion:
      typeof daten['aktuelleVersion'] === 'number' ? daten['aktuelleVersion'] : undefined,
    basisVersion: typeof daten['basisVersion'] === 'number' ? daten['basisVersion'] : undefined,
    gueltig: daten['gueltig'] === true,
    befunde: Array.isArray(daten['befunde']) ? daten['befunde'] : [],
    aenderungen: Array.isArray(daten['aenderungen']) ? daten['aenderungen'] : [],
  };
}

@Component({
  selector: 'app-verwaltung-dokumentarten',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './verwaltung.html',
  styleUrl: './verwaltung.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Verwaltung implements OnInit {
  protected readonly texte = TEXTE.verwaltung;
  protected readonly arten = signal<Dokumentart[] | null>(null);
  protected readonly ausgewaehlteArt = signal<string | null>(null);
  protected readonly klassifikationen = signal<Klassifikation[] | null>(null);
  protected readonly ausgewaehlteKlassifikation = signal<string | null>(null);
  protected readonly regeln = signal<Regel[] | null>(null);
  protected readonly ausgewaehlteRegel = signal<string | null>(null);
  protected readonly zuordnungen = signal<ArtKlassifikation[]>([]);
  protected readonly versionen = signal<KonfigurationsVersion[]>([]);
  protected readonly versionenGeladen = signal(false);
  protected readonly diff = signal<Versionsaenderung[]>([]);
  protected readonly dateiGelesen = signal(false);
  protected readonly dateiPruefung = signal<KonfigurationsdateiAnalyse | null>(null);
  protected readonly dateiVergleich = signal<KonfigurationsdateiAnalyse | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly etag = signal<string | null>(null);
  protected readonly beschaeftigt = signal(false);
  protected readonly artNeu = signal(false);
  protected readonly klassifikationNeu = signal(false);
  protected readonly regelNeu = signal(false);
  protected readonly artLoeschenFrage = signal(false);
  protected readonly klassifikationLoeschenFrage = signal(false);
  protected readonly wertLoeschenFrage = signal<string | null>(null);
  protected readonly speichernFrage = signal<'art' | null>(null);
  protected readonly einspielenFrage = signal(false);
  protected readonly dateiErfolgreichEingespielt = signal(false);
  protected readonly versionAlt = signal<number | null>(null);
  protected readonly versionNeu = signal<number | null>(null);
  protected readonly vergleichAusgefuehrt = signal(false);
  protected readonly gruppen = ['finanz', 'vertrag', 'frei'] as const;
  protected readonly regelTypen = Object.values(RegelAnlegen.TypEnum);
  protected readonly regelSchweren = Object.values(RegelAnlegen.SchwereEnum);
  protected readonly felderTypen = [
    'text',
    'ganzzahl',
    'zahl',
    'datum',
    'janein',
    'auswahl',
    'mehrfach',
    'liste',
  ] as const;
  protected readonly aufbewahrungBezug = [
    'GESCHAEFTSJAHR',
    'GUELTIG_BIS',
    'BELEGDATUM',
    'FELD',
  ] as const;
  protected dateiGrund = '';
  protected artForm: ArtForm = artFormLeer();
  protected klassifikationForm: KlassifikationForm = {
    code: '',
    name: '',
    beschreibung: '',
    mehrfach: false,
    kiVorschlag: false,
    suchfilter: false,
    oeffentlichFilter: false,
    aktiv: true,
    grund: '',
  };
  protected wertForm: WertForm = {
    code: '',
    name: '',
    beschreibung: '',
    reihenfolge: '0',
    aktiv: true,
    grund: '',
  };
  protected wertBearbeiten: string | null = null;
  protected zuordnungForm: ZuordnungForm = { klassifikation: '', pflicht: false, standardWert: '' };
  protected zuordnungBearbeiten: string | null = null;
  protected regelForm: RegelForm = regelFormLeer();
  private dateiInhalt: string | null = null;
  private dateiLeseLauf = 0;

  constructor(
    private readonly dokumentartenService: DokumentartenService,
    private readonly konfigurationService: KonfigurationService,
    private readonly klassifikationenService: DokumentKlassifikationenService,
  ) {}

  ngOnInit(): void {
    this.ladeStand();
  }

  protected artWaehlen(art: Dokumentart): void {
    this.ausgewaehlteArt.set(art.code);
    this.artNeu.set(false);
    this.artForm = artFormAus(art);
    this.artLoeschenFrage.set(false);
    this.zuordnungBearbeiten = null;
    this.zuordnungForm = { klassifikation: '', pflicht: false, standardWert: '' };
    this.ladeZuordnungen(art.code);
    this.meldung.set(null);
  }

  protected neueArt(): void {
    this.artNeu.set(true);
    this.ausgewaehlteArt.set(null);
    this.artForm = artFormLeer();
    this.zuordnungen.set([]);
    this.zuordnungBearbeiten = null;
    this.meldung.set(null);
  }

  protected artGruppeSetzen(gruppe: string, gesetzt: boolean): void {
    const gruppen = new Set(this.artForm.gruppen);
    gesetzt ? gruppen.add(gruppe) : gruppen.delete(gruppe);
    this.artForm.gruppen = [...gruppen];
  }

  protected feldEditorFehler(): string | null {
    if (this.artForm.schema.modus !== 'felder') {
      return null;
    }
    const fehler = feldEditorFehler(this.artForm.schema.felder);
    if (fehler === 'code') {
      return this.texte.dokumentarten.fehlerCode;
    }
    if (fehler === 'optionen') {
      return this.texte.dokumentarten.fehlerOptionen;
    }
    if (fehler === 'liste') {
      return this.texte.dokumentarten.fehlerListe;
    }
    return null;
  }

  protected feldHinzufuegen(): void {
    if (this.artForm.schema.modus !== 'felder') {
      return;
    }
    this.artForm.schema = {
      ...this.artForm.schema,
      felder: [
        ...this.artForm.schema.felder,
        {
          code: '',
          name: '',
          typ: 'text',
          pflicht: false,
          pflichtInListe: false,
          pflichtImSchema: false,
          beschreibung: '',
          optionen: [],
          eindeutig: false,
        },
      ],
    };
  }

  protected feldEntfernen(index: number): void {
    if (this.artForm.schema.modus === 'felder') {
      this.artForm.schema = {
        ...this.artForm.schema,
        felder: this.artForm.schema.felder.filter((_, aktuelle) => aktuelle !== index),
      };
    }
  }

  protected optionHinzufuegen(index: number): void {
    if (this.artForm.schema.modus !== 'felder') {
      return;
    }
    this.feldAktualisieren(index, (feld) => ({
      ...feld,
      optionen: [...feld.optionen, { wert: '', beschriftung: '' }],
    }));
  }

  protected optionEntfernen(feldIndex: number, optionIndex: number): void {
    if (this.artForm.schema.modus !== 'felder') {
      return;
    }
    this.feldAktualisieren(feldIndex, (feld) => ({
      ...feld,
      optionen: feld.optionen.filter((_, aktuelle) => aktuelle !== optionIndex),
    }));
  }

  protected artSpeichernStarten(): void {
    this.fehler.set(this.feldEditorFehler());
    if (this.fehler()) {
      return;
    }
    if (this.artNeu()) {
      this.artSpeichernBestaetigt();
    } else {
      this.speichernFrage.set('art');
    }
  }

  protected artSpeichernBestaetigt(): void {
    const etag = this.etagPruefen();
    if (etag === null) {
      return;
    }
    const art = this.arten()?.find((eintrag) => eintrag.code === this.ausgewaehlteArt());
    let schema: object;
    try {
      if (this.artForm.schema.modus === 'json') {
        const parsed = objekt(JSON.parse(this.artForm.schema.inhalt));
        if (parsed === null) {
          this.fehler.set(this.texte.dokumentarten.schemaJsonUngueltig);
          this.speichernFrage.set(null);
          return;
        }
        schema = parsed;
      } else {
        schema = schemaAusFeldern(this.artForm.schema.felder, art?.zusatzSchema);
      }
    } catch {
      this.fehler.set(this.texte.dokumentarten.schemaJsonUngueltig);
      this.speichernFrage.set(null);
      return;
    }
    const jahreText = zahlText(this.artForm.aufbewahrungJahre);
    const jahre = this.artForm.unbefristet ? null : jahreText === '' ? 10 : Number(jahreText);
    const reihenfolge = Number(zahlText(this.artForm.reihenfolge));
    if (
      !/^[A-Z][A-Z0-9_]{1,39}$/.test(this.artForm.code.trim()) ||
      this.artForm.name.trim() === '' ||
      (jahre !== null && (!Number.isInteger(jahre) || jahre < 0)) ||
      !Number.isInteger(reihenfolge)
    ) {
      this.fehler.set(this.texte.fehlerUngueltig);
      this.speichernFrage.set(null);
      return;
    }
    // Pflichtfelder im Rundlauf: `zusatz.*` kommen im JSON-Modus aus dem Textfeld, im Feld-Modus aus dem Feld-Editor.
    const pflichtfelder =
      this.artForm.schema.modus === 'json'
        ? zeilen(this.artForm.pflichtfelder)
        : (() => {
            const felder = this.artForm.schema.felder;
            const codes = new Set(felder.map((feld) => `zusatz.${feld.code.trim()}`));
            return [
              ...zeilen(this.artForm.pflichtfelder).filter((feld) => !feld.startsWith('zusatz.')),
              ...this.artForm.pflichtZusatzOhneFeld.filter((feld) => !codes.has(feld)),
              ...zusatzPflichtfelder(felder),
            ];
          })();
    // Das Backend verlangt die Gruppe `frei`, sobald ein Pflichtfeld `zusatz.*` steht (Dokumentart.java): Sie wird gesetzt und bleibt im Formular sichtbar.
    if (pflichtfelder.some((feld) => feld.startsWith('zusatz.'))) {
      this.artGruppeSetzen('frei', true);
    }
    const body: DokumentartAenderung = {
      name: this.artForm.name.trim(),
      beschreibung: this.artForm.beschreibung.trim() || null,
      gruppen: this.artForm.gruppen,
      pflichtfelder: pflichtfelderOrdnen(art?.pflichtfelder ?? [], pflichtfelder),
      zusatzSchema: schema,
      vertraulichkeit: this.artForm.vertraulichkeit,
      aufbewahrungJahre: jahre,
      aufbewahrungAb: this.artForm.aufbewahrungAb as DokumentartAenderung.AufbewahrungAbEnum,
      aufbewahrungFeld:
        this.artForm.aufbewahrungAb === 'FELD' ? this.artForm.aufbewahrungFeld.trim() : null,
      gesetzlichePflicht: this.artForm.gesetzlichePflicht,
      aktiv: this.artForm.aktiv,
      selbstAusgestellt: this.artForm.selbstAusgestellt,
      belegnummerMuster: this.artForm.belegnummerMuster
        .split('\n')
        .map((muster) => muster.trim())
        .filter((muster) => muster !== ''),
      kiVorschlag: this.artForm.kiVorschlag,
      kiFelder: this.artForm.kiFelder
        .split('\n')
        .map((feld) => feld.trim())
        .filter((feld) => feld !== ''),
      kiHinweise: this.artForm.kiHinweise.trim() || null,
      reihenfolge,
      grund: this.artForm.grund.trim() || null,
    };
    const fremdbelegArt = this.artForm.fremdbelegArt.trim();
    const ohneZahlungsdatenArt = this.artForm.ohneZahlungsdatenArt.trim();
    if (fremdbelegArt !== '') {
      body.fremdbelegArt = fremdbelegArt;
    }
    if (ohneZahlungsdatenArt !== '') {
      body.ohneZahlungsdatenArt = ohneZahlungsdatenArt;
    }
    if (this.artNeu()) {
      body.code = this.artForm.code.trim();
    }
    this.beschaeftigt.set(true);
    const anfrage = this.artNeu()
      ? this.dokumentartenService.apiV1DokumentartenPost(etag, body, 'response')
      : this.dokumentartenService.apiV1DokumentartenCodePatch(
          this.artForm.code,
          etag,
          body,
          'response',
        );
    anfrage.subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.speichernFrage.set(null);
        this.fehler.set(null);
        this.nachSchreiben(antwort);
        const gespeichert = antwort.body ?? this.artAusForm(body, this.artForm.code.trim());
        this.arten.update((liste) => {
          const ohne = (liste ?? []).filter((eintrag) => eintrag.code !== gespeichert.code);
          return [...ohne, gespeichert].sort(
            (a, b) => a.reihenfolge - b.reihenfolge || a.code.localeCompare(b.code),
          );
        });
        this.ausgewaehlteArt.set(gespeichert.code);
        this.artNeu.set(false);
        this.artForm = artFormAus(gespeichert);
        this.ladeZuordnungen(gespeichert.code);
        this.meldung.set(this.texte.gespeichert);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected artLoeschen(): void {
    const code = this.ausgewaehlteArt();
    const etag = this.etagPruefen();
    if (code === null || etag === null) {
      return;
    }
    this.beschaeftigt.set(true);
    this.dokumentartenService.apiV1DokumentartenCodeDelete(code, etag, 'response').subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.nachSchreiben(antwort);
        this.arten.update((liste) => (liste ?? []).filter((art) => art.code !== code));
        this.ausgewaehlteArt.set(null);
        this.artForm = artFormLeer();
        this.zuordnungen.set([]);
        this.artLoeschenFrage.set(false);
        this.meldung.set(this.texte.gespeichert);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected klassifikationWaehlen(klassifikation: Klassifikation): void {
    this.ausgewaehlteKlassifikation.set(klassifikation.code ?? null);
    this.klassifikationNeu.set(false);
    this.klassifikationLoeschenFrage.set(false);
    this.klassifikationForm = {
      code: klassifikation.code ?? '',
      name: klassifikation.name ?? '',
      beschreibung: klassifikation.beschreibung ?? '',
      mehrfach: klassifikation.mehrfach === true,
      kiVorschlag: klassifikation.kiVorschlag === true,
      suchfilter: klassifikation.suchfilter === true,
      oeffentlichFilter: klassifikation.oeffentlichFilter === true,
      aktiv: klassifikation.aktiv !== false,
      grund: '',
    };
    this.wertBearbeiten = null;
    this.wertForm = this.wertFormLeer();
    this.wertLoeschenFrage.set(null);
  }

  protected neueKlassifikation(): void {
    this.ausgewaehlteKlassifikation.set(null);
    this.klassifikationNeu.set(true);
    this.klassifikationForm = {
      code: '',
      name: '',
      beschreibung: '',
      mehrfach: false,
      kiVorschlag: false,
      suchfilter: false,
      oeffentlichFilter: false,
      aktiv: true,
      grund: '',
    };
    this.wertBearbeiten = null;
    this.wertForm = this.wertFormLeer();
  }

  protected klassifikationSpeichern(): void {
    const etag = this.etagPruefen();
    if (etag === null) {
      return;
    }
    if (
      !/^[a-z][a-z0-9_]{0,39}$/.test(this.klassifikationForm.code.trim()) ||
      !this.klassifikationForm.name.trim()
    ) {
      this.fehler.set(this.texte.fehlerUngueltig);
      return;
    }
    const bestehend = this.klassifikationen()?.find(
      (eintrag) => eintrag.code === this.ausgewaehlteKlassifikation(),
    );
    const body: Record<string, unknown> = {
      name: this.klassifikationForm.name.trim(),
      beschreibung: this.klassifikationForm.beschreibung.trim() || null,
      mehrfach: this.klassifikationForm.mehrfach,
      kiVorschlag: this.klassifikationForm.kiVorschlag,
      suchfilter: this.klassifikationForm.suchfilter,
      oeffentlichFilter: this.klassifikationForm.oeffentlichFilter,
      aktiv: this.klassifikationForm.aktiv,
      grund: this.klassifikationForm.grund.trim() || null,
    };
    if (this.klassifikationNeu()) {
      body['code'] = this.klassifikationForm.code.trim();
      body['werte'] = [];
    }
    this.beschaeftigt.set(true);
    const anfrage = this.klassifikationNeu()
      ? this.konfigurationService.apiV1KlassifikationenPost(JSON.stringify(body), etag, 'response')
      : this.konfigurationService.apiV1KlassifikationenCodePatch(
          this.klassifikationForm.code,
          JSON.stringify(body),
          etag,
          'response',
        );
    anfrage.subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.nachSchreiben(antwort);
        const gespeichert = klassifikationsListe([antwort.body])[0] ?? {
          ...bestehend,
          ...this.klassifikationForm,
          werte: bestehend?.werte ?? [],
        };
        this.klassifikationen.update((liste) => {
          const ohne = (liste ?? []).filter((eintrag) => eintrag.code !== gespeichert.code);
          return [...ohne, gespeichert].sort((a, b) => (a.code ?? '').localeCompare(b.code ?? ''));
        });
        this.ausgewaehlteKlassifikation.set(gespeichert.code ?? null);
        this.klassifikationNeu.set(false);
        this.klassifikationWaehlen(gespeichert);
        this.meldung.set(this.texte.gespeichert);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected klassifikationLoeschen(): void {
    const code = this.ausgewaehlteKlassifikation();
    const etag = this.etagPruefen();
    if (code === null || etag === null) {
      return;
    }
    this.beschaeftigt.set(true);
    this.konfigurationService.apiV1KlassifikationenCodeDelete(code, etag, 'response').subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.nachSchreiben(antwort);
        this.klassifikationen.update((liste) =>
          (liste ?? []).filter((eintrag) => eintrag.code !== code),
        );
        this.ausgewaehlteKlassifikation.set(null);
        this.klassifikationLoeschenFrage.set(false);
        this.meldung.set(this.texte.gespeichert);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected wertBearbeitenStarten(wert: KlassifikationWert): void {
    this.wertBearbeiten = wert.code ?? null;
    this.wertForm = {
      code: wert.code ?? '',
      name: wert.name ?? '',
      beschreibung: wert.beschreibung ?? '',
      reihenfolge: String(wert.reihenfolge ?? 0),
      aktiv: wert.aktiv !== false,
      grund: '',
    };
  }

  protected neuerWert(): void {
    this.wertBearbeiten = null;
    this.wertForm = this.wertFormLeer();
  }

  protected wertSpeichern(): void {
    const code = this.ausgewaehlteKlassifikation();
    const etag = this.etagPruefen();
    if (code === null || etag === null) {
      return;
    }
    const reihenfolge = Number(zahlText(this.wertForm.reihenfolge));
    if (
      !/^[a-z][a-z0-9_]{0,39}$/.test(this.wertForm.code.trim()) ||
      !this.wertForm.name.trim() ||
      !Number.isInteger(reihenfolge)
    ) {
      this.fehler.set(this.texte.fehlerUngueltig);
      return;
    }
    const body: Record<string, unknown> = {
      name: this.wertForm.name.trim(),
      beschreibung: this.wertForm.beschreibung.trim() || null,
      reihenfolge,
      aktiv: this.wertForm.aktiv,
      grund: this.wertForm.grund.trim() || null,
    };
    if (this.wertBearbeiten === null) {
      body['code'] = this.wertForm.code.trim();
    }
    this.beschaeftigt.set(true);
    const anfrage =
      this.wertBearbeiten === null
        ? this.konfigurationService.apiV1KlassifikationenCodeWertePost(
            code,
            JSON.stringify(body),
            etag,
            'response',
          )
        : this.konfigurationService.apiV1KlassifikationenCodeWerteWertPatch(
            code,
            this.wertBearbeiten,
            JSON.stringify(body),
            etag,
            'response',
          );
    anfrage.subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.nachSchreiben(antwort);
        const gespeichert = werteListe([antwort.body])[0] ?? {
          code: this.wertForm.code,
          name: this.wertForm.name,
          beschreibung: this.wertForm.beschreibung,
          reihenfolge,
          aktiv: this.wertForm.aktiv,
        };
        this.klassifikationen.update((liste) =>
          (liste ?? []).map((eintrag) =>
            eintrag.code !== code
              ? eintrag
              : {
                  ...eintrag,
                  werte: [
                    ...(eintrag.werte ?? []).filter((vorher) => vorher.code !== gespeichert.code),
                    gespeichert,
                  ].sort((a, b) => (a.reihenfolge ?? 0) - (b.reihenfolge ?? 0)),
                },
          ),
        );
        this.neuerWert();
        this.meldung.set(this.texte.gespeichert);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected wertLoeschen(codeWert: string): void {
    const code = this.ausgewaehlteKlassifikation();
    const etag = this.etagPruefen();
    if (code === null || etag === null) {
      return;
    }
    this.beschaeftigt.set(true);
    this.konfigurationService
      .apiV1KlassifikationenCodeWerteWertDelete(code, codeWert, etag, 'response')
      .subscribe({
        next: (antwort) => {
          this.beschaeftigt.set(false);
          this.nachSchreiben(antwort);
          this.klassifikationen.update((liste) =>
            (liste ?? []).map((eintrag) =>
              eintrag.code !== code
                ? eintrag
                : {
                    ...eintrag,
                    werte: (eintrag.werte ?? []).filter((wert) => wert.code !== codeWert),
                  },
            ),
          );
          this.wertLoeschenFrage.set(null);
          this.meldung.set(this.texte.gespeichert);
          this.fehler.set(null);
        },
        error: (fehler: unknown) => this.anfrageFehler(fehler),
      });
  }

  protected klassifikationWerte(): KlassifikationWert[] {
    const code = this.ausgewaehlteKlassifikation();
    return this.klassifikationen()?.find((eintrag) => eintrag.code === code)?.werte ?? [];
  }

  protected zuordnungWaehlen(zuordnung: ArtKlassifikation): void {
    this.zuordnungBearbeiten = zuordnung.klassifikation ?? null;
    this.zuordnungForm = {
      klassifikation: zuordnung.klassifikation ?? '',
      pflicht: zuordnung.pflicht === true,
      standardWert: zuordnung.standardWert ?? '',
    };
  }

  protected neueZuordnung(): void {
    this.zuordnungBearbeiten = null;
    this.zuordnungForm = { klassifikation: '', pflicht: false, standardWert: '' };
  }

  protected zuordnungSpeichern(): void {
    const art = this.ausgewaehlteArt();
    const etag = this.etagPruefen();
    const klassifikation = this.zuordnungForm.klassifikation;
    if (art === null || etag === null || klassifikation === '') {
      return;
    }
    const body = JSON.stringify({
      ...(this.zuordnungBearbeiten === null ? { klassifikation } : {}),
      pflicht: this.zuordnungForm.pflicht,
      standardWert: this.zuordnungForm.standardWert || null,
    });
    const anfrage =
      this.zuordnungBearbeiten === null
        ? this.konfigurationService.apiV1DokumentartenArtKlassifikationenPost(
            art,
            body,
            etag,
            'response',
          )
        : this.konfigurationService.apiV1DokumentartenArtKlassifikationenCodePatch(
            art,
            klassifikation,
            body,
            etag,
            'response',
          );
    this.beschaeftigt.set(true);
    anfrage.subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.nachSchreiben(antwort);
        const gespeichert =
          zuordnungenListe([antwort.body])[0] ??
          ({
            klassifikation,
            pflicht: this.zuordnungForm.pflicht,
            standardWert: this.zuordnungForm.standardWert || undefined,
          } satisfies ArtKlassifikation);
        this.zuordnungen.update((liste) => [
          ...liste.filter((eintrag) => eintrag.klassifikation !== klassifikation),
          gespeichert,
        ]);
        this.neueZuordnung();
        this.meldung.set(this.texte.gespeichert);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected zuordnungEntfernen(code: string | undefined): void {
    const art = this.ausgewaehlteArt();
    const etag = this.etagPruefen();
    if (art === null || code === undefined || etag === null) {
      return;
    }
    this.beschaeftigt.set(true);
    this.konfigurationService
      .apiV1DokumentartenArtKlassifikationenCodeDelete(art, code, etag, 'response')
      .subscribe({
        next: (antwort) => {
          this.beschaeftigt.set(false);
          this.nachSchreiben(antwort);
          this.zuordnungen.update((liste) =>
            liste.filter((eintrag) => eintrag.klassifikation !== code),
          );
          this.meldung.set(this.texte.gespeichert);
          this.fehler.set(null);
        },
        error: (fehler: unknown) => this.anfrageFehler(fehler),
      });
  }

  protected checkboxWert(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected klassifikationName(code: string | undefined): string {
    const klassifikation = this.klassifikationen()?.find((eintrag) => eintrag.code === code);
    return klassifikation?.name ?? code ?? '';
  }

  protected zuordnungsKlassifikationen(): Klassifikation[] {
    const aktuell = this.zuordnungForm.klassifikation;
    return (this.klassifikationen() ?? []).filter(
      (klassifikation) => klassifikation.aktiv !== false || klassifikation.code === aktuell,
    );
  }

  protected zuordnungsWerte(): KlassifikationWert[] {
    const aktuell = this.zuordnungForm.standardWert;
    const klassifikation = this.klassifikationen()?.find(
      (eintrag) => eintrag.code === this.zuordnungForm.klassifikation,
    );
    return (klassifikation?.werte ?? []).filter(
      (wert) => wert.aktiv !== false || wert.code === aktuell,
    );
  }

  protected regelWaehlen(regel: Regel): void {
    const form = regelFormAus(regel);
    if (form === null) {
      this.fehler.set(this.texte.regeln.antwortUngueltig);
      return;
    }
    this.ausgewaehlteRegel.set(form.code);
    this.regelNeu.set(false);
    this.regelForm = form;
    this.fehler.set(null);
    this.meldung.set(null);
  }

  protected neueRegel(): void {
    this.ausgewaehlteRegel.set(null);
    this.regelNeu.set(true);
    this.regelForm = regelFormLeer();
    this.fehler.set(null);
    this.meldung.set(null);
  }

  protected regelTypName(typ: string | undefined): string {
    return istRegelTyp(typ) ? this.texte.regeln.typen[typ] : this.texte.regeln.unbekannterTyp;
  }

  protected regelSpeichern(): void {
    const etag = this.etagPruefen();
    if (etag === null) {
      return;
    }
    const istNeu = this.regelNeu();
    const code = this.regelForm.code.trim();
    const bestehenderCode = this.ausgewaehlteRegel();
    if (
      (istNeu && !/^[a-z][a-z0-9_]{0,39}$/.test(code)) ||
      (!istNeu && bestehenderCode === null) ||
      this.regelForm.meldung.trim() === ''
    ) {
      this.fehler.set(this.texte.fehlerUngueltig);
      return;
    }
    let parameter: Record<string, unknown> | null;
    try {
      parameter = objekt(JSON.parse(this.regelForm.parameter));
    } catch {
      parameter = null;
    }
    if (parameter === null) {
      this.fehler.set(this.texte.regeln.parameterUngueltig);
      return;
    }
    const art = this.regelForm.art.trim() || null;
    const meldung = this.regelForm.meldung.trim();
    const grund = this.regelForm.grund.trim() || null;
    const beiErfolg = (antwort: HttpResponse<Regel>) => {
      this.beschaeftigt.set(false);
      this.nachSchreiben(antwort);
      const gespeichert = antwort.body;
      const codeGespeichert = gespeichert?.code;
      const form = gespeichert === null ? null : regelFormAus(gespeichert);
      if (gespeichert === null || codeGespeichert === undefined || form === null) {
        this.fehler.set(this.texte.regeln.antwortUngueltig);
        this.ladeStand();
        return;
      }
      this.regeln.update((liste) => {
        const ohne = (liste ?? []).filter((eintrag) => eintrag.code !== codeGespeichert);
        return [...ohne, gespeichert].sort((a, b) => (a.code ?? '').localeCompare(b.code ?? ''));
      });
      this.ausgewaehlteRegel.set(codeGespeichert);
      this.regelNeu.set(false);
      this.regelForm = form;
      this.meldung.set(
        gespeichert.betroffeneDokumente == null
          ? this.texte.regeln.gespeichert
          : `${this.texte.regeln.gespeichert} ${this.texte.regeln.betroffene(gespeichert.betroffeneDokumente)}`,
      );
      this.fehler.set(null);
    };
    const beiFehler = (fehler: unknown) => this.anfrageFehler(fehler);
    if (istNeu) {
      const payload: RegelAnlegen = {
        code,
        art,
        typ: this.regelForm.typ,
        parameter,
        schwere: this.regelForm.schwere,
        meldung,
        aktiv: this.regelForm.aktiv,
        grund,
      };
      this.beschaeftigt.set(true);
      this.konfigurationService.apiV1RegelnPost(etag, payload, 'response').subscribe({
        next: beiErfolg,
        error: beiFehler,
      });
      return;
    }
    if (bestehenderCode === null) {
      this.fehler.set(this.texte.fehlerUngueltig);
      return;
    }
    const payload: RegelAenderung = {
      art,
      typ: this.regelForm.typ,
      parameter,
      schwere: this.regelForm.schwere,
      meldung,
      aktiv: this.regelForm.aktiv,
      grund,
    };
    this.beschaeftigt.set(true);
    this.konfigurationService
      .apiV1RegelnCodePatch(bestehenderCode, etag, payload, 'response')
      .subscribe({ next: beiErfolg, error: beiFehler });
  }

  protected versionAuswahlAendern(seite: 'alt' | 'neu', wert: number | null): void {
    const version = wert === null ? null : Number(wert);
    if (seite === 'alt') {
      this.versionAlt.set(version);
    } else {
      this.versionNeu.set(version);
    }
    this.vergleichAusgefuehrt.set(false);
  }

  protected versionenVergleichen(): void {
    const alt = this.versionAlt();
    const neu = this.versionNeu();
    if (alt === null || neu === null || alt === neu) {
      this.fehler.set(this.texte.fehlerUngueltig);
      return;
    }
    this.beschaeftigt.set(true);
    forkJoin({
      alt: this.konfigurationService.apiV1KonfigurationVersionenNummerGet(alt, 'response'),
      neu: this.konfigurationService.apiV1KonfigurationVersionenNummerGet(neu, 'response'),
    }).subscribe({
      next: ({ alt: vorher, neu: nachher }) => {
        this.beschaeftigt.set(false);
        const altInhalt = objekt(vorher.body)?.['inhalt'] ?? vorher.body;
        const neuInhalt = objekt(nachher.body)?.['inhalt'] ?? nachher.body;
        this.diff.set(versionsvergleich(altInhalt, neuInhalt));
        this.vergleichAusgefuehrt.set(true);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected dateiAuswaehlen(event: Event): void {
    const input = event.target as HTMLInputElement;
    const datei = input.files?.[0];
    if (!datei) {
      return;
    }
    const lauf = ++this.dateiLeseLauf;
    this.dateiInhalt = null;
    this.dateiGelesen.set(false);
    this.dateiPruefung.set(null);
    this.dateiVergleich.set(null);
    this.dateiErfolgreichEingespielt.set(false);
    this.einspielenFrage.set(false);
    this.dateiGrund = '';
    this.fehler.set(null);
    if (datei.size > 1024 * 1024) {
      this.fehler.set(this.texte.datei.dateiZuGross);
      input.value = '';
      return;
    }
    datei.text().then(
      (inhalt) => {
        if (lauf !== this.dateiLeseLauf) {
          return;
        }
        this.dateiInhalt = inhalt;
        this.dateiGelesen.set(true);
        this.fehler.set(null);
        this.meldung.set(null);
      },
      () => {
        if (lauf === this.dateiLeseLauf) {
          this.fehler.set(this.texte.datei.fehlerLaden);
        }
      },
    );
  }

  protected dateiPruefen(): void {
    if (!this.dateiBereit()) {
      return;
    }
    this.beschaeftigt.set(true);
    const lauf = this.dateiLeseLauf;
    this.konfigurationService
      .apiV1KonfigurationPruefenPost(this.dateiInhalt!, 'response')
      .subscribe({
        next: (antwort) => {
          this.beschaeftigt.set(false);
          if (lauf !== this.dateiLeseLauf) {
            return;
          }
          this.dateiPruefung.set(analysiereAntwort(antwort.body));
          this.fehler.set(null);
        },
        error: (fehler: unknown) => {
          if (lauf === this.dateiLeseLauf) {
            this.anfrageFehler(fehler);
          } else {
            this.beschaeftigt.set(false);
          }
        },
      });
  }

  protected dateiVergleichen(): void {
    if (!this.dateiBereit()) {
      return;
    }
    this.beschaeftigt.set(true);
    const lauf = this.dateiLeseLauf;
    this.konfigurationService
      .apiV1KonfigurationVergleichenPost(this.dateiInhalt!, 'response')
      .subscribe({
        next: (antwort) => {
          this.beschaeftigt.set(false);
          if (lauf !== this.dateiLeseLauf) {
            return;
          }
          this.dateiVergleich.set(analysiereAntwort(antwort.body));
          this.fehler.set(null);
        },
        error: (fehler: unknown) => {
          if (lauf === this.dateiLeseLauf) {
            this.anfrageFehler(fehler);
          } else {
            this.beschaeftigt.set(false);
          }
        },
      });
  }

  protected dateiEinspielenMoeglich(): boolean {
    const pruefung = this.dateiPruefung();
    const vergleich = this.dateiVergleich();
    return (
      !this.dateiErfolgreichEingespielt() &&
      this.dateiInhalt !== null &&
      pruefung?.gueltig === true &&
      vergleich?.gueltig === true &&
      pruefung.basisVersion !== undefined &&
      vergleich.aktuelleVersion !== undefined &&
      pruefung.basisVersion === vergleich.aktuelleVersion
    );
  }

  protected dateiEinspielen(): void {
    if (!this.dateiEinspielenMoeglich()) {
      this.fehler.set(this.texte.datei.vorschauNoetig);
      return;
    }
    this.einspielenFrage.set(true);
  }

  protected dateiEinspielenBestaetigen(): void {
    const grund = this.dateiGrund.trim();
    if (!this.dateiEinspielenMoeglich() || !this.dateiInhalt || grund === '') {
      return;
    }
    // Der Grund geht als Header `X-Aenderungsgrund`; der Browser lässt dort nur Zeichen von U+0020 bis U+00FF zu.
    if (/[^\u0020-\u00ff]/.test(grund)) {
      this.fehler.set(this.texte.datei.grundZeichen);
      return;
    }
    this.beschaeftigt.set(true);
    this.konfigurationService.apiV1KonfigurationPut(this.dateiInhalt, grund, 'response').subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        this.etagAktualisieren(antwort);
        this.dateiErfolgreichEingespielt.set(true);
        this.einspielenFrage.set(false);
        this.meldung.set(this.texte.datei.einspielenErfolgreich);
        this.fehler.set(null);
        // Die Datei ändert alles: Listen und offene Formulare auf den neuen Stand bringen.
        this.ladeStand();
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected dateiExportieren(): void {
    this.beschaeftigt.set(true);
    this.konfigurationService.apiV1KonfigurationGet('response').subscribe({
      next: (antwort) => {
        this.beschaeftigt.set(false);
        const text =
          typeof antwort.body === 'string'
            ? antwort.body
            : JSON.stringify(antwort.body ?? {}, null, 2);
        const url = URL.createObjectURL(
          new Blob([text], { type: 'application/json;charset=utf-8' }),
        );
        const link = document.createElement('a');
        link.href = url;
        link.download = 'konfiguration.json';
        document.body.append(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 0);
        this.fehler.set(null);
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler),
    });
  }

  protected aenderungen(antwort: KonfigurationsdateiAnalyse | null): Versionsaenderung[] {
    return (antwort?.aenderungen ?? []).flatMap((aenderung): Versionsaenderung[] => {
      const daten = objekt(aenderung);
      if (daten === null) {
        return [];
      }
      const pfad = typeof daten['pfad'] === 'string' ? daten['pfad'] : '/';
      const verdeckt =
        daten['werteVerdeckt'] === true || /(^|[./_-])(kennungen|iban|uid)([./_-]|$)/i.test(pfad);
      return [
        {
          pfad,
          alt: verdeckt ? '' : String(daten['alt'] ?? '—'),
          neu: verdeckt ? '' : String(daten['neu'] ?? '—'),
          werteVerdeckt: verdeckt,
        },
      ];
    });
  }

  protected fehlerMeldung(): string | null {
    return this.fehler();
  }

  protected neuLaden(): void {
    this.fehler.set(null);
    this.ladeStand();
  }

  protected neuLadenNoetig(): boolean {
    return (
      this.arten() === null ||
      this.klassifikationen() === null ||
      this.regeln() === null ||
      !this.versionenGeladen() ||
      this.etag() === null ||
      this.fehler() === this.texte.fehlerKonflikt ||
      this.fehler() === this.texte.fehlerLaden
    );
  }

  protected zeitpunkt(zeit: string | undefined): string {
    if (!zeit) {
      return '—';
    }
    const datum = new Date(zeit);
    if (Number.isNaN(datum.getTime())) {
      return '—';
    }
    return new Intl.DateTimeFormat('de-CH', {
      timeZone: 'Europe/Zurich',
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(datum);
  }

  /**
   * Lädt Arten, Klassifikationen und Regeln gemeinsam. Der ETag gilt nur, wenn alle drei Listen denselben Stand
   * haben; sonst bleibt er leer, und Speichern verlangt erst ein Neuladen (Req 7.6). Offene Formulare bestehender
   * Einträge übernehmen den geladenen Stand, damit kein veraltetes Formular mit dem neuen ETag speichert.
   */
  private ladeStand(): void {
    forkJoin({
      arten: this.dokumentartenService.apiV1DokumentartenGet('response'),
      klassifikationen: this.konfigurationService.apiV1KlassifikationenGet('response'),
      regeln: this.konfigurationService.apiV1RegelnGet('response'),
    }).subscribe({
      next: ({ arten, klassifikationen, regeln }) => {
        const etags = new Set([arten, klassifikationen, regeln].map((a) => a.headers.get('ETag')));
        const [etag] = etags;
        const einheitlich = etags.size === 1 && etag !== null && etag !== undefined;
        this.etag.set(einheitlich ? etag : null);
        this.arten.set(arten.body ?? []);
        this.klassifikationen.set(klassifikationsListe(klassifikationen.body));
        this.regeln.set(regeln.body ?? []);
        this.formulareAbgleichen();
        if (!einheitlich) {
          this.fehler.set(this.texte.fehlerKonflikt);
        }
        this.ladeVersionen();
        const art = this.ausgewaehlteArt();
        if (art !== null) {
          this.ladeZuordnungen(art);
        }
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler, 'laden'),
    });
  }

  private formulareAbgleichen(): void {
    const artCode = this.ausgewaehlteArt();
    if (artCode !== null && !this.artNeu()) {
      const art = this.arten()?.find((eintrag) => eintrag.code === artCode);
      this.artForm = art === undefined ? artFormLeer() : artFormAus(art);
      this.speichernFrage.set(null);
      this.artLoeschenFrage.set(false);
      if (art === undefined) {
        this.ausgewaehlteArt.set(null);
        this.zuordnungen.set([]);
      }
    }
    const klassifikationCode = this.ausgewaehlteKlassifikation();
    if (klassifikationCode !== null && !this.klassifikationNeu()) {
      const klassifikation = this.klassifikationen()?.find(
        (eintrag) => eintrag.code === klassifikationCode,
      );
      if (klassifikation === undefined) {
        this.ausgewaehlteKlassifikation.set(null);
      } else {
        this.klassifikationWaehlen(klassifikation);
      }
    }
    const regelCode = this.ausgewaehlteRegel();
    if (regelCode !== null && !this.regelNeu()) {
      const regel = this.regeln()?.find((eintrag) => eintrag.code === regelCode);
      const form = regel === undefined ? null : regelFormAus(regel);
      if (form === null) {
        this.ausgewaehlteRegel.set(null);
        this.regelForm = regelFormLeer();
      } else {
        this.regelForm = form;
      }
    }
  }

  private ladeVersionen(): void {
    this.konfigurationService.apiV1KonfigurationVersionenGet('response').subscribe({
      next: (antwort) => {
        this.standPruefen(antwort);
        const liste = versionenListe(antwort.body);
        this.versionen.set(liste);
        this.versionenGeladen.set(true);
        if (liste.length >= 2 && this.versionAlt() === null && this.versionNeu() === null) {
          this.versionAlt.set(liste[liste.length - 2].version);
          this.versionNeu.set(liste[liste.length - 1].version);
        }
      },
      error: (fehler: unknown) => this.anfrageFehler(fehler, 'laden'),
    });
  }

  private ladeZuordnungen(art: string): void {
    this.konfigurationService.apiV1DokumentartenArtKlassifikationenGet(art, 'response').subscribe({
      next: (antwort) => {
        if (this.ausgewaehlteArt() !== art) {
          return;
        }
        this.standPruefen(antwort);
        this.zuordnungen.set(zuordnungenListe(antwort.body));
      },
      error: (fehler: unknown) => {
        if (this.ausgewaehlteArt() === art) {
          this.anfrageFehler(fehler, 'laden');
        }
      },
    });
  }

  private etagPruefen(): string | null {
    const etag = this.etag();
    if (etag === null) {
      this.fehler.set(this.texte.fehlerLaden);
    }
    return etag;
  }

  /** Nur Schreibantworten und `ladeStand` setzen den ETag; er beschreibt dann die angezeigten Listen. */
  private etagAktualisieren(antwort: HttpResponse<unknown>): void {
    const etag = antwort.headers.get('ETag');
    if (etag !== null) {
      this.etag.set(etag);
    }
  }

  private nachSchreiben(antwort: HttpResponse<unknown>): void {
    this.etagAktualisieren(antwort);
    this.klassifikationenService.aktualisieren();
    this.ladeVersionen();
  }

  /**
   * Einzelne Lesezugriffe (Zuordnungen, Versionen) übernehmen den ETag nicht: Ein neuerer Stand dort heisst, dass die
   * angezeigten Listen veraltet sind. Speichern geht dann mit dem alten ETag und endet in 412 statt in einem
   * verlorenen Update.
   */
  private standPruefen(antwort: HttpResponse<unknown>): void {
    const etag = antwort.headers.get('ETag');
    const gehalten = this.etag();
    if (etag !== null && gehalten !== null && etag !== gehalten) {
      this.fehler.set(this.texte.fehlerKonflikt);
    }
  }

  private anfrageFehler(fehler: unknown, art: 'aktion' | 'laden' = 'aktion'): void {
    this.beschaeftigt.set(false);
    const fehlerart = verwaltungsFehlerart(fehler);
    const text =
      fehlerart === 'konflikt'
        ? this.texte.fehlerKonflikt
        : fehlerart === 'dateiVeraltet'
          ? this.texte.datei.fehlerVeraltet
          : fehlerart === 'gebrauch'
            ? this.texte.fehlerGebrauch
            : fehlerart === 'existiert'
              ? this.texte.fehlerExistiert
              : fehlerart === 'ungueltig'
                ? this.texte.fehlerUngueltig
                : art === 'laden' && !(fehler instanceof HttpErrorResponse && fehler.status > 0)
                  ? this.texte.fehlerLaden
                  : allgemeineFehlermeldung(fehler);
    if (fehlerart === 'dateiVeraltet') {
      this.dateiPruefung.set(null);
      this.dateiVergleich.set(null);
      this.einspielenFrage.set(false);
    }
    this.fehler.set(text);
  }

  private dateiBereit(): boolean {
    if (this.dateiInhalt === null) {
      this.fehler.set(this.texte.datei.vorschauNoetig);
      return false;
    }
    try {
      const parsed = objekt(JSON.parse(this.dateiInhalt));
      if (parsed === null) {
        this.fehler.set(this.texte.datei.jsonUngueltig);
        return false;
      }
      return true;
    } catch {
      this.fehler.set(this.texte.datei.jsonUngueltig);
      return false;
    }
  }

  private feldAktualisieren(
    index: number,
    aktualisieren: (
      feld: Extract<SchemaEditor, { modus: 'felder' }>['felder'][number],
    ) => Extract<SchemaEditor, { modus: 'felder' }>['felder'][number],
  ): void {
    if (this.artForm.schema.modus !== 'felder') {
      return;
    }
    this.artForm.schema = {
      ...this.artForm.schema,
      felder: this.artForm.schema.felder.map((feld, aktuelle) =>
        aktuelle === index ? aktualisieren(feld) : feld,
      ),
    };
  }

  private artAusForm(body: DokumentartAenderung, code: string): Dokumentart {
    return {
      code,
      name: body.name ?? code,
      beschreibung: body.beschreibung,
      gruppen: body.gruppen ?? [],
      pflichtfelder: body.pflichtfelder ?? [],
      zusatzSchema: body.zusatzSchema ?? {},
      vertraulichkeit: body.vertraulichkeit ?? 'NORMAL',
      aufbewahrungJahre: body.aufbewahrungJahre,
      aufbewahrungAb: body.aufbewahrungAb,
      aufbewahrungFeld: body.aufbewahrungFeld,
      gesetzlichePflicht: body.gesetzlichePflicht ?? false,
      aktiv: body.aktiv ?? true,
      selbstAusgestellt: body.selbstAusgestellt ?? false,
      belegnummerMuster: body.belegnummerMuster ?? [],
      fremdbelegArt: body.fremdbelegArt,
      ohneZahlungsdatenArt: body.ohneZahlungsdatenArt,
      kiVorschlag: body.kiVorschlag ?? false,
      kiFelder: body.kiFelder ?? [],
      kiHinweise: body.kiHinweise,
      reihenfolge: body.reihenfolge ?? 0,
    };
  }

  private wertFormLeer(): WertForm {
    return { code: '', name: '', beschreibung: '', reihenfolge: '0', aktiv: true, grund: '' };
  }
}
