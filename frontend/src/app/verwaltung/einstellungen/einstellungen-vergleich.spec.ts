import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { einstellungenVergleichen } from './einstellungen-vergleich';

describe('Vergleich von Einstellungen', () => {
  it('zeigt nur geänderte Einstellungspfade und verbirgt Kennungen', () => {
    const alt = { einstellungen: { kennungen: { uid: 'CHE-OLD', ibans: ['ALT'] },
      chat: { aufbewahrungMonate: 12 } }, arten: [{ code: 'ALT' }] };
    const neu = { einstellungen: { kennungen: { uid: 'CHE-NEW', ibans: ['NEU'] },
      chat: { aufbewahrungMonate: 24 } }, arten: [{ code: 'NEU' }] };
    const vergleich = einstellungenVergleichen(alt, neu);
    expect(vergleich).toEqual([
      { pfad: 'einstellungen.chat.aufbewahrungMonate', alt: '12', neu: '24' },
      { pfad: 'einstellungen.kennungen.ibans', alt: TEXTE.einstellungen.verborgen, neu: TEXTE.einstellungen.verborgen },
      { pfad: 'einstellungen.kennungen.uid', alt: TEXTE.einstellungen.verborgen, neu: TEXTE.einstellungen.verborgen },
    ]);
    expect(JSON.stringify(vergleich)).not.toContain('CHE-');
    expect(JSON.stringify(vergleich)).not.toContain('ALT');
  });

  it('behandelt fehlende historische Felder und null als gleich', () => {
    expect(einstellungenVergleichen({}, { einstellungen: { chat: { aufbewahrungMonate: null } } })).toEqual([]);
  });

  it('meldet ungültige Abbilder statt einen leeren Vergleich anzuzeigen', () => {
    expect(() => einstellungenVergleichen(null, {})).toThrow();
    expect(() => einstellungenVergleichen({ einstellungen: 'ungültig' }, {})).toThrow();
  });
});
