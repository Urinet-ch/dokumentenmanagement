export * from './api-tokens.service';
import { APITokensService } from './api-tokens.service';
export * from './dokumentarten.service';
import { DokumentartenService } from './dokumentarten.service';
export * from './dokumente.service';
import { DokumenteService } from './dokumente.service';
export const APIS = [APITokensService, DokumentartenService, DokumenteService];
