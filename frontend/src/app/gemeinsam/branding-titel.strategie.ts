import { DOCUMENT } from '@angular/common';
import { effect, inject, Injectable, signal } from '@angular/core';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { BrandingService } from './branding.service';
import { TEXTE } from './texte.de-CH';

/** Setzt den Seitentitel mit dem Mandantennamen statt dem festen Standardtitel. */
@Injectable({ providedIn: 'root' })
export class BrandingTitelStrategie extends TitleStrategy {
  private readonly branding = inject(BrandingService);
  private readonly dokument = inject(DOCUMENT);
  private readonly seitentitel = signal<string | undefined>(undefined);

  constructor() {
    super();
    effect(() => {
      const name = this.branding.branding().name;
      this.dokument.title = this.seitentitel()?.replace(TEXTE.appTitel, name) ?? name;
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.seitentitel.set(this.buildTitle(snapshot));
  }
}
