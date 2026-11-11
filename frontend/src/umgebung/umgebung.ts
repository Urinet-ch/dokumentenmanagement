// Produktion und Stufe A (gleiche Domains, tech.md «Plattform und Betrieb»): Keycloak unter archiv-auth.apps.urinet.ch.
export const UMGEBUNG = {
  oidc: {
    aussteller: 'https://archiv-auth.apps.urinet.ch/realms/urinet-archiv',
    clientId: 'archiv-pwa',
  },
} as const;
