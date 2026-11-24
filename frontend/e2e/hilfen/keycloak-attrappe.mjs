// Attrappe für Keycloak im Browser-Test (Teilnachweis Req 2.1): Playwright fängt alle Anfragen an den Aussteller ab.
// Sie prüft PKCE S256 wie Keycloak (code_verifier gegen code_challenge) und liefert unsignierte Tokens; die Signatur
// prüft im Betrieb die API, nicht die PWA.
import { createHash, randomUUID } from 'node:crypto';

export const basisUrl = process.env.ARCHIV_URL ?? 'http://localhost:8080';
export const AUSSTELLER = 'https://archiv-auth.apps.urinet.ch/realms/urinet-archiv';

const teil = (wert) => Buffer.from(JSON.stringify(wert)).toString('base64url');
const jwt = (nutzlast) => `${teil({ alg: 'none', typ: 'JWT' })}.${teil(nutzlast)}.`;

export function benutzer(rolle, mandant) {
  return { id: randomUUID(), name: `Test ${rolle}`, rollen: [rolle, 'offline_access'], mandant };
}

/** Richtet die Attrappe im Browser-Kontext ein; fremde Adressen ausser Archiv und Keycloak werden blockiert. */
export async function keycloakAttrappe(welt) {
  if (welt.keycloak) {
    return welt.keycloak;
  }
  const origin = new URL(basisUrl).origin;
  const kc = {
    benutzer: null,
    ablaufSekunden: 300,
    erneuerungAblehnen: false,
    vorgaenge: new Map(),
    pkce: [],
    erneuerungen: 0,
    abmeldungen: [],
    tokens: [],
    get accessToken() {
      return this.tokens.findLast((t) => t.typ === 'access')?.wert;
    },
  };
  welt.keycloak = kc;

  const ausstellen = (nonce) => {
    const jetzt = Math.floor(Date.now() / 1000);
    const b = kc.benutzer;
    const access = jwt({
      iss: AUSSTELLER,
      aud: 'archiv-api',
      azp: 'archiv-pwa',
      sub: b.id,
      exp: jetzt + kc.ablaufSekunden,
      name: b.name,
      preferred_username: b.name.toLowerCase().replace(/\s+/g, '.'),
      realm_access: { roles: b.rollen },
      ...(b.mandant ? { mandant: b.mandant } : {}),
      jti: randomUUID(),
    });
    const id = jwt({ iss: AUSSTELLER, aud: 'archiv-pwa', sub: b.id, exp: jetzt + kc.ablaufSekunden, nonce });
    const refresh = randomUUID();
    kc.tokens.push({ typ: 'access', wert: access }, { typ: 'id', wert: id }, { typ: 'refresh', wert: refresh });
    return { access_token: access, id_token: id, refresh_token: refresh, expires_in: kc.ablaufSekunden, token_type: 'Bearer' };
  };

  await welt.kontext.route('**/*', (route) =>
    route.request().url().startsWith(origin) ? route.continue() : route.abort('blockedbyclient'),
  );

  await welt.kontext.route(`${AUSSTELLER}/protocol/openid-connect/**`, async (route) => {
    const anfrage = route.request();
    const url = new URL(anfrage.url());
    const endpunkt = url.pathname.split('/').pop();
    const p = url.searchParams;
    const cors = { 'access-control-allow-origin': origin, 'content-type': 'application/json' };

    if (endpunkt === 'auth') {
      const code = randomUUID();
      kc.vorgaenge.set(code, {
        challenge: p.get('code_challenge'),
        methode: p.get('code_challenge_method'),
        nonce: p.get('nonce'),
        redirectUri: p.get('redirect_uri'),
        clientId: p.get('client_id'),
      });
      const ziel = new URL(p.get('redirect_uri'));
      ziel.searchParams.set('state', p.get('state'));
      ziel.searchParams.set('code', code);
      return route.fulfill({ status: 302, headers: { location: ziel.toString() } });
    }

    if (endpunkt === 'token') {
      const felder = new URLSearchParams(anfrage.postData() ?? '');
      const ablehnen = () =>
        route.fulfill({ status: 400, headers: cors, body: JSON.stringify({ error: 'invalid_grant' }) });
      if (felder.get('grant_type') === 'authorization_code') {
        const vorgang = kc.vorgaenge.get(felder.get('code'));
        kc.vorgaenge.delete(felder.get('code'));
        const verifier = felder.get('code_verifier') ?? '';
        const pkceRichtig =
          !!vorgang &&
          vorgang.methode === 'S256' &&
          verifier.length >= 43 &&
          createHash('sha256').update(verifier).digest('base64url') === vorgang.challenge;
        kc.pkce.push(pkceRichtig);
        if (!pkceRichtig || vorgang.redirectUri !== felder.get('redirect_uri') || vorgang.clientId !== 'archiv-pwa') {
          return ablehnen();
        }
        return route.fulfill({ status: 200, headers: cors, body: JSON.stringify(ausstellen(vorgang.nonce)) });
      }
      if (felder.get('grant_type') === 'refresh_token') {
        kc.erneuerungen++;
        if (kc.erneuerungAblehnen) {
          return ablehnen();
        }
        return route.fulfill({ status: 200, headers: cors, body: JSON.stringify(ausstellen(undefined)) });
      }
      return ablehnen();
    }

    if (endpunkt === 'logout') {
      kc.abmeldungen.push(Object.fromEntries(p));
      return route.fulfill({ status: 302, headers: { location: p.get('post_logout_redirect_uri') ?? origin } });
    }
    return route.abort('blockedbyclient');
  });
  return kc;
}
