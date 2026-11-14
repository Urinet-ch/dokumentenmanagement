import { HttpErrorResponse } from '@angular/common/http';
import { convertToParamMap } from '@angular/router';
import { DETAIL } from './kontakte-attrappe';
import {
  FormularWerte,
  LEERER_FILTER,
  aenderungen,
  anzahlFilter,
  fehlerArt,
  filterAusParametern,
  formularAusDetail,
  kontaktAuswahlText,
  kontaktName,
  parameterAusFilter,
  pruefen,
  seitenAnzahl,
  suchbegriffGueltig,
} from './kontakt-logik';

describe('Kontakte: Logik (Req 17.2, 17.6)', () => {
  describe('Suchbegriff', () => {
    it.each([
      ['a', true],
      ['  Muster  ', true],
      ['', false],
      ['   ', false],
      ['a'.repeat(200), true],
      ['a'.repeat(201), false],
      ['mit\u0000nul', false],
      ['zeilen\numbruch', false],
      ['tab\there', false],
    ])('«%s» gilt: %s', (q, erwartet) => {
      expect(suchbegriffGueltig(q)).toBe(erwartet);
    });
  });

  describe('Query-Parameter der Liste', () => {
    it('liest Standardwerte, wenn nichts in der Adresse steht', () => {
      expect(filterAusParametern(convertToParamMap({}))).toEqual(LEERER_FILTER);
    });

    it('liest und schreibt Suchbegriff, Filter und Seite; Standardwerte bleiben weg', () => {
      const filter = {
        q: 'garage',
        status: 'ALLE',
        quelle: 'CSV_IMPORT',
        nichtImExport: true,
        seite: 2,
      } as const;
      const parameter = parameterAusFilter(filter);
      expect(parameter).toEqual({
        q: 'garage',
        status: 'ALLE',
        quelle: 'CSV_IMPORT',
        nichtImExport: 'true',
        seite: 2,
      });
      const zurueck = filterAusParametern(
        convertToParamMap(
          Object.fromEntries(Object.entries(parameter).map(([k, v]) => [k, String(v)])),
        ),
      );
      expect(zurueck).toEqual(filter);
      expect(parameterAusFilter(LEERER_FILTER)).toEqual({
        q: null,
        status: null,
        quelle: null,
        nichtImExport: null,
        seite: null,
      });
    });

    it('fällt bei ungültigen Werten auf den Standard zurück', () => {
      const f = filterAusParametern(
        convertToParamMap({ status: 'X', quelle: 'Y', seite: '-3', nichtImExport: 'ja' }),
      );
      expect(f).toEqual(LEERER_FILTER);
      expect(filterAusParametern(convertToParamMap({ seite: 'abc' })).seite).toBe(0);
      expect(filterAusParametern(convertToParamMap({ seite: '1.5' })).seite).toBe(0);
    });

    it('zählt gesetzte Filter ohne den Standard «aktiv»', () => {
      expect(anzahlFilter(LEERER_FILTER)).toBe(0);
      expect(
        anzahlFilter({
          ...LEERER_FILTER,
          status: 'INAKTIV',
          quelle: 'MANUELL',
          nichtImExport: true,
        }),
      ).toBe(3);
    });

    it('rechnet die Seitenzahl', () => {
      expect(seitenAnzahl(0, 25)).toBe(1);
      expect(seitenAnzahl(26, 25)).toBe(2);
    });
  });

  describe('Namen', () => {
    it('setzt Vorname und Name zusammen und lässt Markup unverändert (nur als Text anzuzeigen)', () => {
      expect(kontaktName({ vorname: 'Anna', name: 'Muster' })).toBe('Anna Muster');
      expect(kontaktName({ vorname: null, name: '<b>Firma</b>' })).toBe('<b>Firma</b>');
      expect(
        kontaktAuswahlText({ vorname: 'Anna', name: 'Muster', plz: '6460', ort: 'Altdorf' }),
      ).toBe('Anna Muster, 6460 Altdorf');
    });
  });

  describe('Formular', () => {
    const werte = (teil: Partial<FormularWerte> = {}): FormularWerte => ({
      ...formularAusDetail(DETAIL),
      ...teil,
    });

    it('übernimmt das Detail und prüft Pflichtfelder', () => {
      expect(pruefen(werte())).toEqual({});
      expect(pruefen(werte({ name: '   ' }))).toEqual({ name: 'name' });
      expect(pruefen(werte({ typ: '' }))).toEqual({ typ: 'typ' });
    });

    it.each([
      ['name', 201],
      ['plz', 21],
      ['telefon', 51],
      ['uid', 31],
      ['iban', 41],
      ['kategorie', 101],
    ] as const)('prüft die Höchstlänge von %s ohne Leerraum am Rand', (feld, laenge) => {
      expect(pruefen(werte({ [feld]: 'x'.repeat(laenge) }))).toEqual({ [feld]: 'zuLang' });
      expect(pruefen(werte({ [feld]: ` ${'x'.repeat(laenge - 1)} ` }))).toEqual({});
    });

    it('sendet nur geänderte Felder; geleerte Felder gehen als leerer Text', () => {
      const vorher = werte();
      expect(aenderungen(vorher, vorher)).toEqual({});
      expect(aenderungen(vorher, werte({ ort: ' Luzern ' }))).toEqual({ ort: 'Luzern' });
      expect(aenderungen(vorher, werte({ telefon: '', typ: 'PERSON' }))).toEqual({
        telefon: '',
        typ: 'PERSON',
      });
      expect(aenderungen(vorher, werte({ email: DETAIL.email! + ' ' }))).toEqual({});
    });
  });

  describe('Fehler', () => {
    it.each([
      [0, 'verbindung'],
      [401, 'sitzung'],
      [403, 'verboten'],
      [404, 'nichtGefunden'],
      [409, 'konflikt'],
      [412, 'veraltet'],
      [428, 'versionFehlt'],
      [400, 'anfrage'],
      [500, 'sonst'],
    ])('ordnet Status %d zu: %s', (status, art) => {
      expect(fehlerArt(new HttpErrorResponse({ status }))).toBe(art);
    });
  });
});
