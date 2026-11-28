import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { WarteschlangeService } from '../erfassen/handy/warteschlange/warteschlange.service';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { kontoverwaltungEinstiege } from '../gemeinsam/anmeldung/kontoverwaltung';
import { Farbschema, FarbschemaService } from '../gemeinsam/farbschema';
import { ONLINE } from '../gemeinsam/online';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { EinfuehrungVermerk } from '../hilfe/einfuehrung-vermerk';
import { Abmeldung } from './abmeldung';
import { LokaleEinstellungen } from './lokale-einstellungen';

/**
 * Seite «Mein Konto» (`/konto`, Spec 06, Requirement 11): Angaben aus dem Token, Einstiege in die Kontoverwaltung von
 * Keycloak, Darstellung, Einführung, «Dieses Gerät» und Abmelden. Keine Anfrage an die API und nichts Neues auf dem
 * Server (Req 11.12); offline bleiben Darstellung, «Dieses Gerät» und «Abmelden» nutzbar (Req 11.11).
 */
@Component({
  selector: 'app-konto-seite',
  imports: [MatButtonModule, RouterLink, Symbol],
  templateUrl: './konto-seite.html',
  styleUrl: './konto-seite.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontoSeite {
  protected readonly texte = TEXTE.konto;
  protected readonly rollen = TEXTE.kopf.rollen;
  protected readonly abmeldenText = TEXTE.anmeldung.abmelden;
  protected readonly erneutText = TEXTE.einfuehrung.erneut;
  private readonly anmeldung = inject(AnmeldungService);
  protected readonly online = inject(ONLINE);
  protected readonly farbschema = inject(FarbschemaService);
  protected readonly vermerk = inject(EinfuehrungVermerk);
  private readonly warteschlange = inject(WarteschlangeService);
  private readonly abmeldung = inject(Abmeldung);
  private readonly lokaleEinstellungen = inject(LokaleEinstellungen);

  protected readonly benutzer = this.anmeldung.benutzer;
  /** Einführung und Anleitung nur mit Mandant und Rolle (Req 11.7, wie Spec 07, 5.5). */
  protected readonly mitRolle = computed(() => !!this.benutzer()?.mandant && !!this.benutzer()?.rolle);
  /** Die bestehende Liste liegt in «Erfassen» (Recht A1). */
  protected readonly darfErfassen = computed(() => this.anmeldung.darf('A1'));
  /** Eigene Aufnahmen und solche ohne Benutzer, wie die Liste in «Erfassen»; nie die anderer (Req 11.8). */
  protected readonly aufnahmen = computed(() => this.warteschlange.eintraege().length);

  private readonly basis = kontoverwaltungEinstiege();
  protected readonly einstiege = this.basis
    ? [
        { test: 'passkeys', text: this.texte.sicherheit.passkeys, url: this.basis.passkeys },
        { test: 'passwort', text: this.texte.sicherheit.passwort, url: this.basis.passwort },
        { test: 'einmalcode', text: this.texte.sicherheit.einmalcode, url: this.basis.einmalcode },
        { test: 'geraete', text: this.texte.sicherheit.geraete, url: this.basis.geraete },
      ]
    : null;
  protected readonly farbschemata: readonly { wert: Farbschema; text: string }[] = [
    { wert: 'system', text: this.texte.darstellung.system },
    { wert: 'hell', text: this.texte.darstellung.hell },
    { wert: 'dunkel', text: this.texte.darstellung.dunkel },
  ];

  protected readonly frageOffen = signal(false);
  protected readonly entfernt = signal(false);

  protected fragen(): void {
    this.entfernt.set(false);
    this.frageOffen.set(true);
  }

  protected async entfernen(): Promise<void> {
    await this.lokaleEinstellungen.entfernen();
    this.frageOffen.set(false);
    this.entfernt.set(true);
  }

  protected abmelden(): void {
    this.abmeldung.abmelden();
  }
}
