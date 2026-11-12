import { Route, Routes } from '@angular/router';
import { DokumentPort } from './dokument/dokument-port';
import { HttpDokumentService } from './dokument/http-dokument.service';
import { HttpUploadService } from './erfassen/http-upload.service';
import { UploadPort } from './erfassen/upload-port';
import { Folgt } from './folgt/folgt';
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
    loadComponent: () => import('./erfassen/upload').then((m) => m.Upload),
    canActivate: [rechtGuard('A1')],
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
  folgt('importe', TEXTE.menue.importe, 'A12'),
  folgt('importe/:id', TEXTE.menue.importe, 'A12'),
  folgt('verwaltung/dokumentarten', TEXTE.menue.dokumentarten, 'A11'),
  folgt('verwaltung/protokoll', TEXTE.menue.protokoll, 'A13'),
  { path: '**', redirectTo: '' },
];
