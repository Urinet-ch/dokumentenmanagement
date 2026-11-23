// Version und Build der laufenden App. `scripts/ng-build.mjs` setzt die Konstanten beim Bauen (`ng build --define`);
// bei `ng test` und `ng serve` fehlen sie, dann gilt «Entwicklung».
declare const ARCHIV_VERSION: string | undefined;
declare const ARCHIV_BUILD: string | undefined;
declare const ARCHIV_GEBAUT: string | undefined;

export interface BuildInfo {
  /** Version des Archivs (pom.xml), z. B. `0.1.0-SNAPSHOT`. */
  readonly version: string;
  /** Gekürzter Commit des Builds oder `lokal`. */
  readonly build: string;
  /** Zeitpunkt des Builds (ISO 8601, UTC) oder `null`. */
  readonly gebaut: string | null;
}

export const BUILD_INFO: BuildInfo = {
  version: typeof ARCHIV_VERSION === 'string' ? ARCHIV_VERSION : 'Entwicklung',
  build: typeof ARCHIV_BUILD === 'string' ? ARCHIV_BUILD : 'lokal',
  gebaut: typeof ARCHIV_GEBAUT === 'string' ? ARCHIV_GEBAUT : null,
};

/** Datum und Uhrzeit des Builds in Zürcher Zeit (Europe/Zurich), z. B. `23.11.2026, 11:02`. */
export function gebautAm(gebaut: string | null): string | null {
  const zeit = gebaut ? new Date(gebaut) : null;
  if (!zeit || Number.isNaN(zeit.getTime())) {
    return null;
  }
  return new Intl.DateTimeFormat('de-CH', {
    timeZone: 'Europe/Zurich',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(zeit);
}
