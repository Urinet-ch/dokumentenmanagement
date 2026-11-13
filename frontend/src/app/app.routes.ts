import { Route, Routes } from '@angular/router';
import { DokumentPort } from './dokument/dokument-port';
import { HttpDokumentService } from './dokument/http-dokument.service';
import { HttpUploadService } from './erfassen/http-upload.service';
import { UploadPort } from './erfassen/upload-port';
import { Folgt } from './folgt/folgt';
import { erfassenGuard } from './gemeinsam/anmeldung/erfassen.guard';
import { rechtGuard } from './gemeinsam/anmeldung/recht.guard';
import { Aktion } from './gemeinsam/anmeldung/rechte';
import { TEXTE } from './gemeinsam/texte.de-CH';
import { Rueckruf } from './start/rueckruf';
import { Start } from './start/start';
import { HttpSucheService } from './suche/http-suche.service';
import { SuchePort } from './suche/suche-port';

const titel = (seite: string) => `${seite} · ${TEXTE.appTitel}`;

// Seiten späterer Aufgaben: Route und Recht stehen, der Inhalt folgt (design.md «Frontend»)
function folgt(path: string, seite: string, aktion: Aktion): Route {
  return { path, component: Folgt, canActivate: [rechtGuard(aktion)], data: { titel: seite }, title: titel(seite) };
}

export const routes: Routes = [
  { path: '', pathMatch: 'full', component: Start, title: TEXTE.appTitel },
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
    path: 'dokument/:id',
    loadComponent: () => import('./dokument/dokument-ansicht').then((m) => m.DokumentAnsicht),
    canActivate: [rechtGuard('A2')],
    providers: [{ provide: DokumentPort, useClass: HttpDokumentService }],
    title: titel(TEXTE.dokument.titel),
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
  folgt('verwaltung/dokumentarten', TEXTE.menue.dokumentarten, 'A11'),
  {
    path: 'verwaltung/protokoll',
    loadComponent: () => import('./verwaltung/protokoll/protokoll').then((m) => m.Protokoll),
    canActivate: [rechtGuard('A13')],
    title: titel(TEXTE.menue.protokoll),
  },
  {
    path: 'export',
    loadComponent: () => import('./export/export').then((m) => m.Export),
    canActivate: [rechtGuard('EXPORT')],
    title: titel(TEXTE.menue.export),
  },
  { path: '**', redirectTo: '' },
];
