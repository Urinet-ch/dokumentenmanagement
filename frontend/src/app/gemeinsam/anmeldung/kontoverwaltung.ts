import { UMGEBUNG } from '../../../umgebung/umgebung';

/**
 * Adresse der Kontoverwaltung des Realms (`<Aussteller>/account`), abgeleitet aus der OIDC-Konfiguration der App
 * (Spec 06, Req 10.1): Die Einrichtung des Passkeys bleibt in Keycloak, die App kennt nur den Einstieg.
 */
export function kontoverwaltungUrl(aussteller: string = UMGEBUNG.oidc.aussteller): string {
  return `${aussteller.replace(/\/+$/, '')}/account`;
}
