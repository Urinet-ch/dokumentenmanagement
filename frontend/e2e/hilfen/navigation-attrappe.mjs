import { basisUrl } from './keycloak-attrappe.mjs';

const origin = new URL(basisUrl).origin;
const ETAG = '"navigation-smoke"';

const json = (route, body, headers = {}) =>
  route.fulfill({
    status: 200,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers },
    body: JSON.stringify(body),
  });

/** Ergänzt für den Rollen-Rauchtest nur die Listenendpunkte, die beim Öffnen einer Seite aufgerufen werden. */
export async function navigationAttrappe(welt) {
  if (welt.navigationAttrappe) {
    return;
  }

  await welt.seite.route(
    (url) => url.origin === origin && url.pathname.startsWith('/api/v1/'),
    async (route) => {
      const anfrage = route.request();
      const url = new URL(anfrage.url());
      const pfad = url.pathname;
      if (anfrage.method() !== 'GET') {
        return route.fallback();
      }

      if (
        pfad === '/api/v1/eingangskorb' ||
        pfad === '/api/v1/wissensentwuerfe' ||
        pfad === '/api/v1/kontakte' ||
        /^\/api\/v1\/dokumentarten\/[^/]+\/klassifikationen$/.test(pfad)
      ) {
        return route.fallback();
      }
      if (pfad === '/api/v1/dokumente') {
        const seite = Number(url.searchParams.get('seite') ?? 0);
        const groesse = Number(url.searchParams.get('groesse') ?? 20);
        return json(route, { treffer: [], gesamt: 0, seite, groesse });
      }
      if (pfad === '/api/v1/kontakte/liste') {
        const seite = Number(url.searchParams.get('seite') ?? 0);
        const groesse = Number(url.searchParams.get('groesse') ?? 20);
        return json(route, { eintraege: [], gesamt: 0, seite, groesse });
      }
      if (pfad === '/api/v1/wissen/quellen' || pfad === '/api/v1/wissensseiten') {
        return json(
          route,
          pfad.endsWith('/wissensseiten') ? { treffer: [], degradiert: false } : [],
        );
      }
      if (pfad === '/api/v1/importe') {
        const seite = Number(url.searchParams.get('seite') ?? 0);
        const groesse = Number(url.searchParams.get('groesse') ?? 20);
        return json(route, { eintraege: [], gesamt: 0, seite, groesse });
      }
      if (pfad === '/api/v1/exporte' || pfad === '/api/v1/api-tokens') {
        return json(route, []);
      }
      if (pfad === '/api/v1/mandant/einstellungen') {
        return json(route, { katalog: [] }, { etag: ETAG });
      }
      if (
        pfad === '/api/v1/benutzer' ||
        pfad === '/api/v1/rollen' ||
        pfad === '/api/v1/benutzer/antraege'
      ) {
        return json(route, []);
      }
      if (pfad === '/api/v1/regeln' || pfad === '/api/v1/klassifikationen') {
        return json(route, [], { etag: ETAG });
      }
      if (pfad === '/api/v1/dokumentarten') {
        return json(route, [], { etag: ETAG });
      }
      if (pfad === '/api/v1/protokoll') {
        const seite = Number(url.searchParams.get('seite') ?? 0);
        return json(route, { eintraege: [], gesamt: 0, seite, groesse: 50 });
      }
      if (pfad === '/api/v1/protokoll/benutzer' || pfad === '/api/v1/chat/gespraeche') {
        return json(route, []);
      }
      if (pfad === '/api/v1/chat/auswertung') {
        return json(route, {
          anzahl: 0,
          durchschnitt: 0,
          verteilung: [],
          gruende: [],
          schlechteAntworten: [],
        });
      }
      if (pfad === '/api/v1/konfiguration/versionen') {
        return json(route, [], { etag: ETAG });
      }
      return route.fallback();
    },
  );
  welt.navigationAttrappe = true;
}
