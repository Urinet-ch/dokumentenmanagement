import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Start } from './start';

describe('Start', () => {
  const online = signal(true);

  async function zeigen(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      imports: [Start],
      providers: [
        provideRouter([]),
        { provide: ONLINE, useValue: online },
        {
          provide: AnmeldungService,
          useValue: { angemeldet: () => false, zustand: () => 'abgemeldet', darf: () => false },
        },
      ],
    });
    const fixture = TestBed.createComponent(Start);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const link = (element: HTMLElement) =>
    [...element.querySelectorAll('a')].find((a) => a.textContent?.trim() === TEXTE.handy.offlineErfassen);

  it('führt ohne Verbindung und ohne Anmeldung zu «Erfassen» (Req 4.4)', async () => {
    online.set(false);
    expect(link(await zeigen())?.getAttribute('href')).toBe('/erfassen');
  });

  it('bietet den Weg online nicht an', async () => {
    online.set(true);
    expect(link(await zeigen())).toBeUndefined();
  });
});
