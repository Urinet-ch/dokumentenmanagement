import { BenutzerAnsicht, VerwaltungRollenrecht } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

type Gruppe = keyof typeof TEXTE.benutzer.gruppen;

const GRUPPEN: Record<string, Gruppe> = {
  A1: 'erfassen',
  A2: 'lesen',
  A3: 'lesen',
  A4: 'lesen',
  A5: 'lesen',
  A6: 'bestaetigen',
  A7: 'bestaetigen',
  A8: 'bestaetigen',
  A9: 'bestaetigen',
  A10: 'erfassen',
  A15: 'lesen',
  A16: 'erfassen',
  A17: 'bestaetigen',
  A18: 'erfassen',
  A24: 'lesen',
  A25: 'lesen',
  A26: 'erfassen',
  A27: 'lesen',
};

export function name(benutzer: BenutzerAnsicht): string {
  return [benutzer.vorname, benutzer.nachname].filter(Boolean).join(' ')
    || benutzer.benutzername || TEXTE.benutzer.unbekannt;
}

export function rollen(benutzer: BenutzerAnsicht): string {
  const namen = Array.from(benutzer.rollen ?? [])
    .map((rolle) => TEXTE.benutzer.rollenNamen[rolle] ?? rolle);
  return namen.length ? namen.join(', ') : TEXTE.benutzer.keineRolle;
}

export function einladungAblauf(benutzer: BenutzerAnsicht): string {
  if (!benutzer.einladungGueltigBis) return TEXTE.benutzer.einladungAblaufUnbekannt;
  const ablauf = Date.parse(benutzer.einladungGueltigBis);
  if (!Number.isFinite(ablauf)) return TEXTE.benutzer.einladungAblaufUnbekannt;
  return ablauf <= Date.now()
    ? TEXTE.benutzer.einladungAbgelaufen : TEXTE.benutzer.einladungNichtAbgelaufen;
}

export function rechteGruppen(rechte: readonly VerwaltungRollenrecht[]) {
  return (Object.keys(TEXTE.benutzer.gruppen) as Gruppe[]).map((gruppe) => ({
    titel: TEXTE.benutzer.gruppen[gruppe],
    rechte: rechte.filter((recht) => (GRUPPEN[recht.aktion] ?? 'verwalten') === gruppe),
  })).filter((gruppe) => gruppe.rechte.length > 0);
}
