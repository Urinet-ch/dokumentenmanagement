import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { KonfigurationService } from '../gemeinsam/api';
import { DokumentKlassifikationenService } from './klassifikationen';

describe('DokumentKlassifikationenService', () => {
  it('verbindet die Klassifikationswerte mit den Zuordnungen der Art und cached die Abrufe', async () => {
    const api = {
      apiV1KlassifikationenGet: vi.fn().mockReturnValue(
        of([
          {
            code: 'fachgebiet',
            name: 'Fachgebiet',
            aktiv: true,
            mehrfach: false,
            werte: [
              { code: 'technik', name: 'Technik', aktiv: true },
              { code: 'alt', name: 'Alt', aktiv: false },
            ],
          },
        ]),
      ),
      apiV1DokumentartenArtKlassifikationenGet: vi
        .fn()
        .mockReturnValue(
          of([{ klassifikation: 'fachgebiet', pflicht: true, standardWert: 'technik' }]),
        ),
    };
    TestBed.configureTestingModule({
      providers: [
        DokumentKlassifikationenService,
        { provide: KonfigurationService, useValue: api },
      ],
    });
    const service = TestBed.inject(DokumentKlassifikationenService);

    const result = await firstValueFrom(service.fuerArt('BULLETIN'));
    await firstValueFrom(service.fuerArt('BULLETIN'));

    expect(result.art).toEqual([
      {
        code: 'fachgebiet',
        name: 'Fachgebiet',
        aktiv: true,
        mehrfach: false,
        pflicht: true,
        standardWert: 'technik',
        werte: [
          { code: 'technik', name: 'Technik', aktiv: true },
          { code: 'alt', name: 'Alt', aktiv: false },
        ],
      },
    ]);
    expect(api.apiV1KlassifikationenGet).toHaveBeenCalledTimes(1);
    expect(api.apiV1DokumentartenArtKlassifikationenGet).toHaveBeenCalledTimes(1);

    service.aktualisieren();
    await firstValueFrom(service.fuerArt('BULLETIN'));
    expect(api.apiV1KlassifikationenGet).toHaveBeenCalledTimes(2);
    expect(api.apiV1DokumentartenArtKlassifikationenGet).toHaveBeenCalledTimes(2);
  });
});
