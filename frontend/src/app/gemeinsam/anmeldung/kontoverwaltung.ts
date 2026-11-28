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

export interface KontoverwaltungEinstiege {
  readonly passkeys: string;
  readonly passwort: string;
  readonly einmalcode: string;
  readonly geraete: string;
}

/**
 * Einstiege der Seite «Mein Konto» (Spec 06, Req 11.4). Geprüft gegen ein Wegwerf-Keycloak 26.8.2 (design.md, «Umsetzung
 * (Aufgabe 26.2 …)»): Passkeys, Passwort und Einmalcode liegen in der Kontoverwaltung gemeinsam auf «Anmeldung»
 * (`account-security/signing-in`), ohne eigene Adresse je Art; die Sitzungen auf «Geräteaktivität»
 * (`account-security/device-activity`). Beide Adressen überstehen die Anmeldung. Ohne Kontoverwaltung `null`.
 */
export function kontoverwaltungEinstiege(aussteller: string = UMGEBUNG.oidc.aussteller): KontoverwaltungEinstiege | null {
  const basis = kontoverwaltungUrl(aussteller);
  if (!basis) return null;
  const anmeldung = `${basis}/account-security/signing-in`;
  return { passkeys: anmeldung, passwort: anmeldung, einmalcode: anmeldung, geraete: `${basis}/account-security/device-activity` };
}
