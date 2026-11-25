import { UMGEBUNG } from '../../../umgebung/umgebung';

/**
 * Adresse der Kontoverwaltung des Realms (`<Aussteller>/account`), abgeleitet aus der OIDC-Konfiguration der App
 * (Spec 06, Req 10.1): Die Einrichtung des Passkeys bleibt in Keycloak, die App kennt nur den Einstieg.
 * Ohne https-Aussteller (leer, relativ; `http://localhost` nur für die Entwicklung) gibt es keinen Link (`null`), nie einen toten relativen.
 */
export function kontoverwaltungUrl(aussteller: string = UMGEBUNG.oidc.aussteller): string | null {
  if (!/^(https:\/\/|http:\/\/localhost[:/])/.test(aussteller)) return null;
  return `${aussteller.replace(/\/+$/, '')}/account`;
}
