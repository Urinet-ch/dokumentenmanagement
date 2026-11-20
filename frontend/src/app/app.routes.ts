import { Route, Routes } from '@angular/router';
import { DokumentPort } from './dokument/dokument-port';
import { HttpDokumentService } from './dokument/http-dokument.service';
import { HttpUploadService } from './erfassen/http-upload.service';
import { UploadPort } from './erfassen/upload-port';
import { HttpKontakteService } from './kontakte/http-kontakte.service';
import { KontaktePort } from './kontakte/kontakte-port';
import { erfassenGuard } from './gemeinsam/anmeldung/erfassen.guard';
import { rechtGuard } from './gemeinsam/anmeldung/recht.guard';
import { TEXTE } from './gemeinsam/texte.de-CH';
import { Rueckruf } from './start/rueckruf';
import { Start } from './start/start';
import { HttpSucheService } from './suche/http-suche.service';
import { SuchePort } from './suche/suche-port';
import { verwaltungGuard } from './verwaltung/dokumentarten/verwaltung.guard';

const titel = (seite: string) => `${seite} · ${TEXTE.appTitel}`;

export const routes: Routes = [
  { path: '', pathMatch: 'full', component: Start, title: TEXTE.appTitel },
  {
    path: 'nicht-gefunden',
    loadComponent: () => import('./start/nicht-gefunden').then((m) => m.NichtGefunden),
    title: TEXTE.seiteNichtGefunden.titel,
  },
  { path: 'anmeldung', component: Rueckruf, title: TEXTE.appTitel },
  {
    path: 'erfassen',
    loadComponent: () => import('./erfassen/handy/handy-erfassung').then((m) => m.HandyErfassung),
    canActivate: [erfassenGuard],
    providers: [{ provide: UploadPort, useClass: HttpUploadService }],
    title: titel(TEXTE.menue.erfassen),
  },
  {
    path: 'eingangskorb',
    loadComponent: () => import('./eingangskorb/eingangskorb').then((m) => m.Eingangskorb),
    canActivate: [rechtGuard('A5')],
    title: titel(TEXTE.menue.eingangskorb),
  },
  {
    path: 'suche',
    loadComponent: () => import('./suche/suche').then((m) => m.Suche),
    canActivate: [rechtGuard('A2')],
    providers: [{ provide: SuchePort, useClass: HttpSucheService }],
    title: titel(TEXTE.menue.suche),
  },
  {
    path: 'wissen',
    loadComponent: () => import('./wissen/wissen-index').then((m) => m.WissenIndex),
    canActivate: [rechtGuard('A15')],
    title: titel(TEXTE.wissen.titel),
  },
  {
    path: 'wissen/log',
    loadComponent: () => import('./wissen/wissen-log').then((m) => m.WissenLog),
    canActivate: [rechtGuard('A15')],
    title: titel(TEXTE.wissen.log),
  },
  {
    path: 'wissen/ueberblick',
    loadComponent: () => import('./wissen/wissen-seite').then((m) => m.WissenSeite),
    canActivate: [rechtGuard('A15')],
    data: { pfad: 'ueberblick' },
    title: titel(TEXTE.wissen.titel),
  },
  {
    path: 'wissen/entwuerfe',
    loadComponent: () => import('./wissen/wissen-entwuerfe').then((m) => m.WissenEntwuerfe),
    canActivate: [rechtGuard('A17')],
    title: titel(TEXTE.wissen.entwuerfe.titel),
  },
  {
    path: 'wissen/entwuerfe/:id',
    loadComponent: () => import('./wissen/wissen-entwuerfe').then((m) => m.WissenEntwuerfe),
    canActivate: [rechtGuard('A17')],
    title: titel(TEXTE.wissen.entwuerfe.titel),
  },
  {
    path: 'wissen/:ordner/:name',
    loadComponent: () => import('./wissen/wissen-seite').then((m) => m.WissenSeite),
    canActivate: [rechtGuard('A15')],
    title: titel(TEXTE.wissen.titel),
  },
  {
    path: 'dokument/:id',
    loadComponent: () => import('./dokument/dokument-ansicht').then((m) => m.DokumentAnsicht),
    canActivate: [rechtGuard('A2')],
    providers: [{ provide: DokumentPort, useClass: HttpDokumentService }],
    title: titel(TEXTE.dokument.titel),
  },
  {
    path: 'kontakte',
    loadComponent: () => import('./kontakte/kontakte').then((m) => m.Kontakte),
    canActivate: [rechtGuard('A6')],
    providers: [{ provide: KontaktePort, useClass: HttpKontakteService }],
    title: titel(TEXTE.menue.kontakte),
  },
  {
    path: 'kontakte/:id',
    loadComponent: () => import('./kontakte/kontakt-detail').then((m) => m.KontaktDetailSeite),
    canActivate: [rechtGuard('A6')],
    providers: [{ provide: KontaktePort, useClass: HttpKontakteService }],
    title: titel(TEXTE.menue.kontakte),
  },
  {
    path: 'importe',
    loadComponent: () => import('./importe/importe').then((m) => m.Importe),
    canActivate: [rechtGuard('A12')],
    title: titel(TEXTE.menue.importe),
  },
  {
    path: 'importe/:id',
    loadComponent: () => import('./importe/import-lauf').then((m) => m.ImportLaufSeite),
    canActivate: [rechtGuard('A12')],
    title: titel(TEXTE.menue.importe),
  },
  {
    path: 'verwaltung/dokumentarten',
    loadComponent: () => import('./verwaltung/dokumentarten/verwaltung').then((m) => m.Verwaltung),
    canActivate: [verwaltungGuard],
    title: titel(TEXTE.menue.dokumentarten),
  },
  {
    path: 'verwaltung/protokoll',
    loadComponent: () => import('./verwaltung/protokoll/protokoll').then((m) => m.Protokoll),
    canActivate: [rechtGuard('A13')],
    title: titel(TEXTE.menue.protokoll),
  },
  // Chat (Spec 02, Aufgabe 7.4): alle vier Rollen mit dem Recht wie A2 (Entscheid Fabian 14.11.2026); Menüpunkt «Chat»
  // seit Aufgabe 7.4b (Backend 7.2, 7.3 gemergt)
  {
    path: 'chat',
    loadChildren: () => import('./chat/chat.routes').then((m) => m.CHAT_ROUTES),
    canActivate: [rechtGuard('A2')],
    title: titel(TEXTE.chat.titel),
  },
  {
    path: 'verwaltung/api-tokens',
    loadComponent: () => import('./verwaltung/api-tokens/api-tokens').then((m) => m.ApiTokens),
    canActivate: [rechtGuard('A14')],
    title: titel(TEXTE.menue.apiTokens),
  },
  {
    path: 'export',
    loadComponent: () => import('./export/export').then((m) => m.Export),
    canActivate: [rechtGuard('EXPORT')],
    title: titel(TEXTE.menue.export),
  },
  { path: '**', redirectTo: '' },
];
