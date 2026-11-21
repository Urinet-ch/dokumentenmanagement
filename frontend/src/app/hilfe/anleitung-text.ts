import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { AnleitungLader } from './anleitung-lader';
import { Anleitungsteil } from './anleitung-markdown';
import { HilfeEintrag } from './hilfe-katalog';

/**
 * Text eines Abschnitts der Anleitung (Spec 07, Req 7.3, 7.6): Baum aus der Markdown-Teilmenge, dargestellt wie die
 * Wissensseite nur über Interpolation (kein innerHTML, kein bypassSecurityTrust…). Den Titel setzt die Seite bzw. die
 * Hilfe-Leiste; hier steht nur der Inhalt der Datei.
 */
@Component({
  selector: 'app-anleitung-text',
  imports: [NgTemplateOutlet, RouterLink, MatButtonModule, MatProgressBarModule, Symbol],
  templateUrl: './anleitung-text.html',
  styleUrl: './anleitung-text.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnleitungText {
  private readonly lader = inject(AnleitungLader);
  private readonly branding = inject(BrandingService);
  private readonly anmeldung = inject(AnmeldungService);
  readonly eintrag = input.required<HilfeEintrag>();
  /** Stufen, um die Überschriften der Datei tiefer stehen (z. B. 1: «##» wird h3 unter dem h2 des Abschnitts). */
  readonly versatz = input(0);
  /** Auf `/hilfe` kann derselbe Text zugleich in der Leiste stehen; dort bleiben Überschriften-IDs weg. */
  readonly ids = input(true);
  /** In der Hilfe-Leiste: Links auf Abschnitte navigieren nicht, sondern melden nur «abschnitt» (Req 4.4). */
  readonly leiste = input(false);
  /** Ein Link auf einen Abschnitt (`#anker`) wurde gewählt. */
  readonly abschnitt = output<string>();
  /** Ein Link auf eine Seite der App wurde gewählt (die Leiste schliesst sich). */
  readonly navigiert = output<void>();
  protected readonly texte = TEXTE.hilfe;
  protected readonly zustand = computed(() => this.lader.zustand(this.eintrag())());

  protected platzhalter(teil: Anleitungsteil): string {
    if (teil.platzhalter === 'name') return this.branding.branding().name;
    const rolle = this.anmeldung.benutzer()?.rolle;
    return rolle ? (TEXTE.kopf.rollen[rolle] ?? '') : '';
  }

  protected ebene(teil: Anleitungsteil): number {
    return Math.min(6, Number(teil.art.charAt(1)) + this.versatz());
  }

  protected abschnittGewaehlt(ereignis: Event, anker: string): void {
    if (this.leiste()) ereignis.preventDefault();
    this.abschnitt.emit(anker);
  }

  protected erneut(): void {
    this.lader.erneut(this.eintrag());
  }
}
