import { EinstellungskatalogEintrag } from '../../gemeinsam/api';
import { EinstellungenEingabeFehler, einstellungenPatch } from './einstellungen-patch';

describe('Einstellungen als Merge Patch', () => {
  const katalog: EinstellungskatalogEintrag[] = [
    { schluessel: 'chat.aufbewahrungMonate', typ: 'GANZZAHL' },
    { schluessel: 'kennungen.ibans', typ: 'IBANS' },
    { schluessel: 'branding.name', typ: 'TEXT' },
    { schluessel: 'wissen.lintZeitplan.stunde', typ: 'GANZZAHL' },
  ];

  it('übernimmt nur geänderte Felder, Listen und ausdrückliche Rücksetzungen', () => {
    expect(einstellungenPatch(katalog, {
      'chat.aufbewahrungMonate': '12', 'kennungen.ibans': 'CH00\nCH01\n',
      'branding.name': null, 'wissen.lintZeitplan.stunde': '4',
    })).toEqual({
      chat: { aufbewahrungMonate: 12 }, kennungen: { ibans: ['CH00', 'CH01'] },
      branding: { name: null }, wissen: { lintZeitplan: { stunde: 4 } },
    });
  });

  it.each(['', '1.5', 'Infinity', '2147483648'])('weist ungültige ganze Zahl %s zurück', (zahl) => {
    expect(() => einstellungenPatch(katalog, { 'chat.aufbewahrungMonate': zahl }))
      .toThrow(EinstellungenEingabeFehler);
  });

  it('weist unbekannte und gefährliche Pfade zurück', () => {
    expect(() => einstellungenPatch(katalog, { 'fremd.feld': 'a' })).toThrow(EinstellungenEingabeFehler);
    expect(() => einstellungenPatch([{ schluessel: 'constructor.x', typ: 'TEXT' }], { 'constructor.x': 'a' }))
      .toThrow(EinstellungenEingabeFehler);
  });
});
