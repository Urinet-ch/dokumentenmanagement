import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DOCUMENT, DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { UMGEBUNG } from '../../../umgebung/umgebung';
import { codeChallengeS256, jwtNutzlast, zufall } from './pkce';
import { Aktion, Rolle, darf, rolleAusRealmRollen } from './rechte';

export interface OidcKonfiguration {
  aussteller: string;
  clientId: string;
}

export const OIDC_KONFIGURATION = new InjectionToken<OidcKonfiguration>('OIDC_KONFIGURATION', {
  factory: () => UMGEBUNG.oidc,
});

/** Zugriff auf Adresse, Weiterleitung und Sitzungsspeicher des Browsers; in Tests ersetzbar. */
export interface Fenster {
  readonly location: { readonly origin: string; assign(url: string): void };
  readonly sessionStorage: Storage;
}

export const FENSTER = new InjectionToken<Fenster>('FENSTER', {
  factory: () => inject(DOCUMENT).defaultView as Fenster,
});

export type Sitzungszustand = 'abgemeldet' | 'angemeldet' | 'abgelaufen';

export interface Benutzer {
  id: string;
  name: string;
  mandant: string | null;
  rolle: Rolle | null;
}

export class AnmeldungFehler extends Error {}

interface Tokens {
  accessToken: string;
  idToken?: string;
  refreshToken?: string;
  ablauf: number;
}

interface TokenAntwort {
  access_token?: string;
  id_token?: string;
  refresh_token?: string;
  expires_in?: number;
}

// Nur der laufende Anmeldevorgang (state, nonce, code_verifier, Ziel) überlebt die Weiterleitung zu Keycloak im
// sessionStorage; er wird beim Rückruf sofort gelöscht. Tokens liegen nie im Browser-Speicher, nur in diesem Dienst.
const VORGANG_SCHLUESSEL = 'archiv.anmeldung.vorgang';
const ERNEUERN_VOR_ABLAUF_MS = 30_000;
const ERNEUERN_MINDESTENS_MS = 5_000;
const ERNEUERN_WIEDERHOLEN_MS = 15_000;

interface Vorgang {
  state: string;
  nonce: string;
  verifier: string;
  ziel: string;
}

/**
 * Anmeldung über Keycloak mit OIDC Authorization Code Flow und PKCE S256 (Req 2.1). Die Tokens liegen nur im
 * Arbeitsspeicher (kein localStorage, kein sessionStorage); nach einem Neuladen meldet sich der Benutzer neu an
 * (Keycloak erkennt die bestehende Sitzung).
 */
@Injectable({ providedIn: 'root' })
export class AnmeldungService {
  private readonly http = inject(HttpClient);
  private readonly konfiguration = inject(OIDC_KONFIGURATION);
  private readonly fenster = inject(FENSTER);

  private readonly tokens = signal<Tokens | null>(null);
  private readonly zustandIntern = signal<Sitzungszustand>('abgemeldet');
  private erneuerungsTimer: ReturnType<typeof setTimeout> | undefined;
  private laufendeErneuerung: Promise<void> | null = null;

  readonly zustand = this.zustandIntern.asReadonly();
  readonly angemeldet = computed(() => this.zustandIntern() === 'angemeldet');
  readonly benutzer = computed<Benutzer | null>(() => {
    const nutzlast = jwtNutzlast(this.tokens()?.accessToken);
    if (!nutzlast) {
      return null;
    }
    const realm = nutzlast['realm_access'] as { roles?: unknown[] } | undefined;
    const name = nutzlast['name'] ?? nutzlast['preferred_username'] ?? '';
    return {
      id: typeof nutzlast['sub'] === 'string' ? nutzlast['sub'] : '',
      name: typeof name === 'string' ? name : '',
      mandant: mandantAusClaim(nutzlast['mandant']),
      rolle: rolleAusRealmRollen(realm?.roles),
    };
  });

  constructor() {
    // Mobile Browser halten Timer im Hintergrund an; beim Zurückkehren die Frist selbst prüfen
    const dokument = inject(DOCUMENT);
    const sichtbar = () => {
      if (dokument.visibilityState !== 'hidden' && this.erneuerungFaellig()) {
        void this.erneuern();
      }
    };
    dokument.addEventListener('visibilitychange', sichtbar);
    inject(DestroyRef).onDestroy(() => {
      dokument.removeEventListener('visibilitychange', sichtbar);
      clearTimeout(this.erneuerungsTimer);
    });
  }

  /** Ohne Mandant im Token gibt es kein Recht (fail closed, wie im Backend). */
  darf(aktion: Aktion): boolean {
    const benutzer = this.benutzer();
    return this.angemeldet() && !!benutzer?.mandant && darf(benutzer.rolle, aktion);
  }

  accessToken(): string | null {
    return this.angemeldet() ? (this.tokens()?.accessToken ?? null) : null;
  }

  /** Access-Token für eine API-Anfrage; läuft es bald ab (z. B. Timer im Ruhezustand verpasst), erst erneuern. */
  async gueltigesAccessToken(): Promise<string | null> {
    if (this.erneuerungFaellig()) {
      await this.erneuern();
    }
    return this.accessToken();
  }

  async anmelden(ziel = '/'): Promise<void> {
    const vorgang: Vorgang = { state: zufall(), nonce: zufall(), verifier: zufall(48), ziel: sicheresZiel(ziel) };
    this.fenster.sessionStorage.setItem(VORGANG_SCHLUESSEL, JSON.stringify(vorgang));
    const parameter = new URLSearchParams({
      client_id: this.konfiguration.clientId,
      redirect_uri: this.rueckrufAdresse(),
      response_type: 'code',
      scope: 'openid',
      state: vorgang.state,
      nonce: vorgang.nonce,
      code_challenge: await codeChallengeS256(vorgang.verifier),
      code_challenge_method: 'S256',
    });
    this.fenster.location.assign(`${this.endpunkt('auth')}?${parameter}`);
  }

  /** Verarbeitet die Rückkehr von Keycloak und liefert das Ziel innerhalb der App. */
  async rueckrufVerarbeiten(parameter: URLSearchParams): Promise<string> {
    const gespeichert = this.fenster.sessionStorage.getItem(VORGANG_SCHLUESSEL);
    this.fenster.sessionStorage.removeItem(VORGANG_SCHLUESSEL);
    const vorgang = gespeichert ? (JSON.parse(gespeichert) as Vorgang) : null;
    const code = parameter.get('code');
    // RFC 9207: Schickt der Aussteller `iss` mit, muss er unserer sein (Schutz vor Mix-up)
    const fremderAussteller = parameter.has('iss') && parameter.get('iss') !== this.konfiguration.aussteller;
    if (parameter.has('error') || fremderAussteller || !vorgang || !code || parameter.get('state') !== vorgang.state) {
      throw new AnmeldungFehler('Rückruf ungültig');
    }
    const antwort = await this.tokenAnfrage({
      grant_type: 'authorization_code',
      code,
      redirect_uri: this.rueckrufAdresse(),
      client_id: this.konfiguration.clientId,
      code_verifier: vorgang.verifier,
    });
    const id = jwtNutzlast(antwort.id_token);
    const zielgruppe = id?.['aud'];
    const fuerUns = Array.isArray(zielgruppe)
      ? zielgruppe.includes(this.konfiguration.clientId)
      : zielgruppe === this.konfiguration.clientId;
    if (!antwort.access_token || id?.['nonce'] !== vorgang.nonce || id?.['iss'] !== this.konfiguration.aussteller || !fuerUns) {
      throw new AnmeldungFehler('Token ungültig');
    }
    this.uebernehmen(antwort);
    return vorgang.ziel;
  }

  abmelden(): void {
    const idToken = this.tokens()?.idToken;
    this.verwerfen('abgemeldet');
    const parameter = new URLSearchParams({
      client_id: this.konfiguration.clientId,
      post_logout_redirect_uri: `${this.fenster.location.origin}/`,
    });
    if (idToken) {
      parameter.set('id_token_hint', idToken);
    }
    this.fenster.location.assign(`${this.endpunkt('logout')}?${parameter}`);
  }

  /** Die API hat 401 geantwortet oder die Erneuerung ist gescheitert: Tokens verwerfen, neu anmelden lassen. */
  sitzungAbgelaufen(): void {
    if (this.zustandIntern() === 'angemeldet') {
      this.verwerfen('abgelaufen');
    }
  }

  private uebernehmen(antwort: TokenAntwort): void {
    const gueltigMs = Math.max(0, (antwort.expires_in ?? 60) * 1000);
    this.tokens.set({
      accessToken: antwort.access_token!,
      idToken: antwort.id_token ?? this.tokens()?.idToken,
      refreshToken: antwort.refresh_token ?? this.tokens()?.refreshToken,
      ablauf: Date.now() + gueltigMs,
    });
    this.zustandIntern.set('angemeldet');
    this.erneuerungPlanen(Math.max(ERNEUERN_MINDESTENS_MS, gueltigMs - ERNEUERN_VOR_ABLAUF_MS));
  }

  private erneuerungPlanen(inMs: number): void {
    clearTimeout(this.erneuerungsTimer);
    this.erneuerungsTimer = setTimeout(() => void this.erneuern(), inMs);
  }

  private erneuerungFaellig(): boolean {
    const aktuell = this.tokens();
    return this.angemeldet() && !!aktuell && aktuell.ablauf - Date.now() < ERNEUERN_VOR_ABLAUF_MS;
  }

  /** Höchstens eine Erneuerung gleichzeitig (Timer, Rückkehr in den Vordergrund und API-Anfragen teilen sie). */
  private erneuern(): Promise<void> {
    this.laufendeErneuerung ??= this.erneuernEinmal().finally(() => (this.laufendeErneuerung = null));
    return this.laufendeErneuerung;
  }

  private async erneuernEinmal(): Promise<void> {
    const aktuell = this.tokens();
    if (!aktuell?.refreshToken || this.zustandIntern() !== 'angemeldet') {
      this.sitzungAbgelaufen();
      return;
    }
    try {
      const antwort = await this.tokenAnfrage({
        grant_type: 'refresh_token',
        refresh_token: aktuell.refreshToken,
        client_id: this.konfiguration.clientId,
      });
      if (!antwort.access_token) {
        throw new AnmeldungFehler('Token fehlt');
      }
      if (this.tokens() === aktuell) {
        this.uebernehmen(antwort);
      }
    } catch (fehler) {
      // Netz weg: wiederholen, solange das Token gilt; Keycloak lehnt ab (Sitzung beendet): abgelaufen
      const netzfehler = fehler instanceof HttpErrorResponse && fehler.status === 0;
      if (netzfehler && aktuell.ablauf - Date.now() > ERNEUERN_WIEDERHOLEN_MS) {
        this.erneuerungPlanen(ERNEUERN_WIEDERHOLEN_MS);
      } else {
        this.sitzungAbgelaufen();
      }
    }
  }

  private verwerfen(zustand: Sitzungszustand): void {
    clearTimeout(this.erneuerungsTimer);
    this.tokens.set(null);
    this.zustandIntern.set(zustand);
  }

  private tokenAnfrage(felder: Record<string, string>): Promise<TokenAntwort> {
    return firstValueFrom(
      this.http.post<TokenAntwort>(this.endpunkt('token'), new URLSearchParams(felder).toString(), {
        headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
      }),
    );
  }

  private endpunkt(name: 'auth' | 'token' | 'logout'): string {
    return `${this.konfiguration.aussteller}/protocol/openid-connect/${name}`;
  }

  private rueckrufAdresse(): string {
    return `${this.fenster.location.origin}/anmeldung`;
  }
}

function mandantAusClaim(wert: unknown): string | null {
  const mandant = Array.isArray(wert) && wert.length === 1 ? wert[0] : wert;
  return typeof mandant === 'string' && mandant.length > 0 ? mandant : null;
}

/** Nur Pfade innerhalb der App, nie fremde Adressen (kein offener Redirect). */
export function sicheresZiel(ziel: string | null | undefined): string {
  return ziel && ziel.startsWith('/') && !ziel.startsWith('//') && !ziel.startsWith('/\\') ? ziel : '/';
}
