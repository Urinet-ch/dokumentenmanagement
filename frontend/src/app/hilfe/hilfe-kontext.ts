import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { aktiverPfad } from '../gemeinsam/navigation';
import { HilfeEintrag } from './hilfe-katalog';
import { sichtbareAbschnitte, sichtbareAnker } from './hilfe-sichtbar';

/** Liefert nur einen Kontext, der dem längsten passenden Pfad zugeordnet und für die Rolle sichtbar ist. */
export function hilfeKontext(
  url: string,
  rolle: Rolle | null,
  katalog: readonly HilfeEintrag[],
): string | null {
  const eintraege = katalog.filter((eintrag) => eintrag.pfade?.length);
  const pfad = aktiverPfad(
    url,
    eintraege.flatMap((eintrag) => eintrag.pfade ?? []),
  );
  const eintrag = eintraege.find((kandidat) => kandidat.pfade?.includes(pfad ?? ''));
  if (!eintrag) return null;
  return sichtbareAnker(sichtbareAbschnitte(katalog, rolle)).has(eintrag.anker) ? eintrag.anker : null;
}
