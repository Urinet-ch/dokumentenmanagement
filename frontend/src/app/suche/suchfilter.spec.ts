import { LEERE_FILTER, Filterwerte, aktiveFilter, imModus, pruefen, wirksamerModus } from './suchfilter';

const mit = (werte: Partial<Filterwerte>): Filterwerte => ({ ...LEERE_FILTER, ...werte });

describe('pruefen (Req 14.2, Grenzen aus Umsetzung 4.5)', () => {
  it('sendet ohne Eingaben nur Seite und Grösse, ohne Sortierung', () => {
    expect(pruefen(LEERE_FILTER, 0)).toEqual({ anfrage: { modus: 'volltext', seite: 0, groesse: 20 } });
  });

  it('übernimmt alle Filter und sendet die Sortierung im Volltext nur mit Suchbegriff', () => {
    const { anfrage } = pruefen(
      mit({
        q: ' Rechnung ',
        art: 'AUSGANGSRECHNUNG',
        partnerId: 'p1',
        datumVon: '2026-01-01',
        datumBis: '2026-12-31',
        betragVon: "1'000",
        betragBis: '2000,50',
        richtung: 'EINNAHME',
        status: 'VERWORFEN',
        tags: 'a, b, a',
        geschaeftsjahr: '2026',
        belegnummer: ' 2026-003 ',
        sortierung: 'DATUM',
        volltext: true,
      }),
      2,
    );
    expect(anfrage).toEqual({
      modus: 'volltext',
      seite: 2,
      groesse: 20,
      q: 'Rechnung',
      sortierung: 'DATUM',
      art: 'AUSGANGSRECHNUNG',
      partner: 'p1',
      datumVon: '2026-01-01',
      datumBis: '2026-12-31',
      betragVon: '1000',
      betragBis: '2000.50',
      richtung: 'EINNAHME',
      status: 'VERWORFEN',
      tags: ['a', 'b'],
      geschaeftsjahr: 2026,
      belegnummer: '2026-003',
    });
  });

  it.each([
    [{ q: 'x'.repeat(201) }, 'begriff'],
    [{ q: 'a\u0007b' }, 'begriff'],
    [{ datumVon: '2026-02-01', datumBis: '2026-01-01' }, 'datum'],
    [{ betragVon: '12.345' }, 'betrag'],
    [{ betragVon: '12345678901' }, 'betrag'],
    [{ betragVon: '100', betragBis: '99.99' }, 'betrag'],
    [{ tags: Array.from({ length: 11 }, (_, i) => `t${i}`).join(',') }, 'tags'],
    [{ tags: 'x'.repeat(101) }, 'tags'],
    [{ geschaeftsjahr: '999' }, 'geschaeftsjahr'],
    [{ geschaeftsjahr: '20x6' }, 'geschaeftsjahr'],
    [{ belegnummer: 'x'.repeat(51) }, 'belegnummer'],
  ])('weist %o als «%s» zurück, bevor das Archiv mit 400 antwortet', (werte, pruefung) => {
    expect(pruefen(mit(werte as Partial<Filterwerte>), 0).fehler).toEqual([pruefung]);
  });

  it('erlaubt negative Beträge und vergleicht sie ohne Gleitkomma', () => {
    expect(pruefen(mit({ betragVon: '-10', betragBis: '-9.99' }), 0).anfrage).toMatchObject({
      betragVon: '-10',
      betragBis: '-9.99',
    });
    expect(pruefen(mit({ betragVon: '0.10', betragBis: '0.1' }), 0).fehler).toBeUndefined();
  });

  it('begrenzt die Seite auf 0 bis 9999', () => {
    expect(pruefen(LEERE_FILTER, -1).anfrage?.seite).toBe(0);
    expect(pruefen(LEERE_FILTER, 10_000).anfrage?.seite).toBe(9_999);
  });

  it('zählt gesetzte Filter ohne Suchbegriff und Sortierung', () => {
    expect(aktiveFilter(mit({ q: 'x', sortierung: 'DATUM' }))).toBe(0);
    expect(aktiveFilter(mit({ datumVon: '2026-01-01', datumBis: '2026-02-01', art: 'X', tags: ' ' }))).toBe(2);
  });

  it('sendet im Modus hybrid weder Sortierung noch Seite noch Grösse, aber alle Filter', () => {
    expect(pruefen(mit({ q: ' Miete ', art: 'X', sortierung: 'RELEVANZ' }), 3)).toEqual({
      anfrage: { modus: 'hybrid', q: 'Miete', art: 'X' },
    });
  });

  it('sendet im Volltext Seite und Grösse, die Sortierung nur mit Suchbegriff', () => {
    expect(pruefen(mit({ q: 'Miete', volltext: true }), 1).anfrage).toEqual({
      modus: 'volltext',
      seite: 1,
      groesse: 20,
      q: 'Miete',
      sortierung: 'RELEVANZ',
    });
    expect(pruefen(mit({ art: 'X', sortierung: 'DATUM' }), 0).anfrage).toEqual({
      modus: 'volltext',
      seite: 0,
      groesse: 20,
      art: 'X',
    });
  });
});

describe('wirksamerModus (Spec 02, Req 2.7)', () => {
  it.each([
    [{ q: '' }, 'volltext'],
    [{ q: '   ' }, 'volltext'],
    [{ q: 'Miete' }, 'hybrid'],
    [{ q: ' Miete ' }, 'hybrid'],
    [{ q: 'Miete', volltext: true }, 'volltext'],
    [{ q: 'Miete', sortierung: 'DATUM' }, 'volltext'],
    [{ q: '', art: 'X' }, 'volltext'],
  ])('wählt für %o den Modus «%s»', (werte, modus) => {
    expect(wirksamerModus(mit(werte as Partial<Filterwerte>))).toBe(modus);
  });
});

describe('imModus', () => {
  it('wechselt ab Seite 0 in den Volltext mit Relevanz und behält die Filter', () => {
    expect(imModus({ modus: 'hybrid', q: 'a', art: 'X' }, true)).toEqual({
      modus: 'volltext',
      seite: 0,
      groesse: 20,
      q: 'a',
      art: 'X',
      sortierung: 'RELEVANZ',
    });
  });

  it('wechselt in den Hybrid ohne Seite, Grösse und Sortierung', () => {
    expect(imModus({ modus: 'volltext', q: 'a', seite: 4, groesse: 20, sortierung: 'DATUM' }, false)).toEqual({
      modus: 'hybrid',
      q: 'a',
    });
  });
});
