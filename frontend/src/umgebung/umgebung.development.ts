// Entwicklung (quarkus dev): Keycloak aus den Dev Services auf festem Port 8180 (application.properties, %dev).
export const UMGEBUNG = {
  oidc: {
    aussteller: 'http://localhost:8180/realms/urinet-archiv',
    clientId: 'archiv-pwa',
  },
} as const;
