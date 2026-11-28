import { Route, Routes } from '@angular/router';
import { DokumentPort } from './dokument/dokument-port';
import { HttpDokumentService } from './dokument/http-dokument.service';
import { HttpUploadService } from './erfassen/http-upload.service';
import { UploadPort } from './erfassen/upload-port';
import { HttpKontakteService } from './kontakte/http-kontakte.service';
import { KontaktePort } from './kontakte/kontakte-port';
import { anmeldungGuard } from './gemeinsam/anmeldung/anmeldung.guard';
import { erfassenGuard } from './gemeinsam/anmeldung/erfassen.guard';
import { rechtGuard } from './gemeinsam/anmeldung/recht.guard';
import { wissenGuard } from './gemeinsam/anmeldung/wissen.guard';
import { WISSENSROUTE } from './gemeinsam/mandant-funktionen';
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
  // Benutzeranleitung (Spec 07, Req 5.1): ohne Rechte-Wächter, die Seite blendet nach Rolle aus (Req 6.2, 6.3)
  {
    path: 'hilfe',
    loadComponent: () => import('./hilfe/hilfe-seite').then((m) => m.HilfeSeite),
    title: titel(TEXTE.hilfe.titel),
  },
  // Mein Konto (Spec 06, Req 11): nur mit Anmeldung, ohne Recht der Rechtematrix; Einstieg über die Kopfzeile
  {
    path: 'konto',
    loadComponent: () => import('./konto/konto-seite').then((m) => m.KontoSeite),
    canActivate: [anmeldungGuard],
    title: titel(TEXTE.konto.titel),
  },
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
    path: 'wissensbasis/einstellungen',
    loadComponent: () => import('./wissen/wissen-einstellungen').then((m) => m.WissenEinstellungenSeite),
    canActivate: [wissenGuard('A19')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissenEinstellungen.titel),
  },
  {
    path: 'wissen',
    loadComponent: () => import('./wissen/wissen-index').then((m) => m.WissenIndex),
    canActivate: [wissenGuard('A15')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissen.titel),
  },
  {
    path: 'wissen/log',
    loadComponent: () => import('./wissen/wissen-log').then((m) => m.WissenLog),
    canActivate: [wissenGuard('A15')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissen.log),
  },
  {
    path: 'wissen/ueberblick',
    loadComponent: () => import('./wissen/wissen-seite').then((m) => m.WissenSeite),
    canActivate: [wissenGuard('A15')],
    data: { [WISSENSROUTE]: true, pfad: 'ueberblick' },
    title: titel(TEXTE.wissen.titel),
  },
  {
    path: 'wissen/entwuerfe',
    loadComponent: () => import('./wissen/wissen-entwuerfe').then((m) => m.WissenEntwuerfe),
    canActivate: [wissenGuard('A17')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissen.entwuerfe.titel),
  },
  {
    path: 'wissen/entwuerfe/import',
    loadComponent: () => import('./wissen/wissen-import').then((m) => m.WissenImport),
    canActivate: [wissenGuard('A17')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissen.import.titel),
  },
  {
    path: 'wissen/entwuerfe/meine',
    loadComponent: () => import('./wissen/meine-analysen').then((m) => m.MeineAnalysen),
    canActivate: [wissenGuard('A18')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.chat.analysen),
  },
  {
    path: 'wissen/entwuerfe/meine/:id',
    loadComponent: () => import('./wissen/meine-analysen').then((m) => m.MeineAnalysen),
    canActivate: [wissenGuard('A18')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.chat.analysen),
  },
  {
    path: 'wissen/entwuerfe/:id',
    loadComponent: () => import('./wissen/wissen-entwuerfe').then((m) => m.WissenEntwuerfe),
    canActivate: [wissenGuard('A17')],
    data: { [WISSENSROUTE]: true },
    title: titel(TEXTE.wissen.entwuerfe.titel),
  },
  {
    path: 'wissen/:ordner/:name',
    loadComponent: () => import('./wissen/wissen-seite').then((m) => m.WissenSeite),
    canActivate: [wissenGuard('A15')],
    data: { [WISSENSROUTE]: true },
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
    path: 'verwaltung/einstellungen',
    loadComponent: () => import('./verwaltung/einstellungen/einstellungen').then((m) => m.EinstellungenSeite),
    canActivate: [rechtGuard('A20', '/nicht-gefunden')],
    title: titel(TEXTE.menue.einstellungen),
  },
  {
    path: 'verwaltung/benutzer',
    loadComponent: () => import('./verwaltung/benutzer/benutzer-liste').then((m) => m.BenutzerListe),
    canActivate: [rechtGuard('A21', '/nicht-gefunden')],
    title: titel(TEXTE.menue.benutzer),
  },
  {
    path: 'verwaltung/benutzer/:id',
    loadComponent: () => import('./verwaltung/benutzer/benutzer-detail').then((m) => m.BenutzerDetail),
    canActivate: [rechtGuard('A21', '/nicht-gefunden')],
    title: titel(TEXTE.menue.benutzer),
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
    path: 'chat/auswertung',
    loadComponent: () => import('./chat/chat-auswertung').then((m) => m.ChatAuswertung),
    canActivate: [rechtGuard('A24', '/nicht-gefunden')],
    title: titel(TEXTE.chat.auswertung.titel),
  },
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
