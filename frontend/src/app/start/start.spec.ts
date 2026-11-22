import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Start } from './start';
import { EinfuehrungVermerk } from '../hilfe/einfuehrung-vermerk';
import { HilfeLeiste } from '../hilfe/hilfe-leiste.service';
import { BrandingService, STANDARD_BRANDING } from '../gemeinsam/branding.service';
import { Aktion, darf } from '../gemeinsam/anmeldung/rechte';

describe('Start', () => {
  const online = signal(true);

  async function zeigen(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      imports: [Start],
      providers: [
        provideRouter([]),
        { provide: ONLINE, useValue: online },
        { provide: EinfuehrungVermerk, useValue: { bekannt: signal(null), erneut: signal(false), schritt: signal(0) } },
        {
          provide: AnmeldungService,
          useValue: { angemeldet: () => false, benutzer: () => null, zustand: () => 'abgemeldet', darf: () => false },
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
    const element = await zeigen();
    expect(link(element)?.getAttribute('href')).toBe('/erfassen');
    const hilfeLink = element.querySelector<HTMLAnchorElement>('a[href="/hilfe#erfassen-offline"]')!;
    hilfeLink.click();
    expect(TestBed.inject(HilfeLeiste).anker()).toBe('erfassen-offline');
    expect(TestBed.inject(HilfeLeiste).offen()).toBe(true);
  });

  describe('Einführung auf der Startseite', () => {
    it('zeigt sie nur für einen neuen Benutzer und behält die Kacheln', async () => {
      const bekannt = signal<boolean | null>(null);
      const rolle = signal<'inhaber' | null>('inhaber');
      TestBed.configureTestingModule({
        imports: [Start],
        providers: [
          provideRouter([]),
          { provide: ONLINE, useValue: signal(true) },
          { provide: EinfuehrungVermerk, useValue: { bekannt, erneut: signal(false), schritt: signal(0) } },
          { provide: BrandingService, useValue: { branding: signal({ ...STANDARD_BRANDING, name: 'Testarchiv', logoUrl: '/logo.png' }) } },
          { provide: AnmeldungService, useValue: {
            angemeldet: () => true,
            benutzer: computed(() => ({ id: 'test', name: 'Test', mandant: 'test', rolle: rolle() })),
            darf: (aktion: Aktion) => darf(rolle(), aktion),
          } },
        ],
      });
      const fixture = TestBed.createComponent(Start);
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      expect(element.querySelector('.einfuehrung')).toBeNull();
      bekannt.set(false);
      await fixture.whenStable();
      expect(element.querySelector('.einfuehrung h2')?.textContent).toContain('Testarchiv');
      expect(element.querySelector('.einfuehrung img')?.getAttribute('src')).toBe('/logo.png');
      expect(element.querySelector('.kacheln a')).not.toBeNull();
      bekannt.set(true);
      await fixture.whenStable();
      expect(element.querySelector('.einfuehrung')).toBeNull();
      bekannt.set(null);
      rolle.set(null);
      await fixture.whenStable();
      expect(element.querySelector('.einfuehrung')).toBeNull();
      expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.anmeldung.ohneZugang);
      element.querySelector<HTMLAnchorElement>('a[href="/hilfe#probleme"]')!.click();
      expect(TestBed.inject(HilfeLeiste).anker()).toBe('probleme');
      expect(TestBed.inject(HilfeLeiste).offen()).toBe(true);
    });
  });

  it('bietet den Weg online nicht an', async () => {
    online.set(true);
    const element = await zeigen();
    expect(link(element)).toBeUndefined();
    expect(element.querySelector('a[href="/hilfe#erfassen-offline"]')).toBeNull();
  });
});
