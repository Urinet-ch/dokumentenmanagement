import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { aktiverPfad } from '../gemeinsam/navigation';
import { HilfeEintrag } from './hilfe-katalog';
import { sichtbareAbschnitte, sichtbareAnker } from './hilfe-sichtbar';

/** Liefert nur einen Kontext, der dem längsten passenden Pfad zugeordnet und für die Rolle sichtbar ist. */
export function hilfeKontext(
  url: string,
  rolle: Rolle | null,
  katalog: readonly HilfeEintrag[],
  abgemeldetOffline = false,
): string | null {
  const eintraege = katalog.filter((eintrag) => eintrag.pfade?.length);
  const pfad = aktiverPfad(
    url,
    eintraege.flatMap((eintrag) => eintrag.pfade ?? []),
  );
  const eintrag = eintraege.find((kandidat) => kandidat.pfade?.includes(pfad ?? ''));
  if (!eintrag) return null;
  const sichtbar = sichtbareAnker(sichtbareAbschnitte(katalog, rolle));
  if (abgemeldetOffline && eintrag.anker === 'erfassen' && sichtbar.has('erfassen-offline')) {
    return 'erfassen-offline';
  }
  return sichtbar.has(eintrag.anker) ? eintrag.anker : null;
}
