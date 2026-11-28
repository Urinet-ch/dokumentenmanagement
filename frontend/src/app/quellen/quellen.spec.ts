import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { of } from 'rxjs';
import { Aktion, Rolle } from '../gemeinsam/anmeldung/rechte';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { QuellenService, Quelllauf, Registerquelle } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { QuellenSeite } from './quellen';

const LAUF: Quelllauf = {
  id: 'lauf-1',
  quelle: 'drive',
  art: Quelllauf.ArtEnum.Trocken,
  status: Quelllauf.StatusEnum.Fertig,
  zaehler: {
    gefunden: 8,
    neu: 3,
    dublettePruefsumme: 2,
    dubletteFachlich: 0,
    uebersprungen: 2,
    fehler: 1,
  },
  meldungen: [],
  gestartet: '2026-11-28T10:00:00Z',
};

const QUELLE: Registerquelle = {
  code: 'drive',
  name: 'Drive',
  typ: Registerquelle.TypEnum.Drive,
  konfiguration: {},
  status: Registerquelle.StatusEnum.Offen,
  bemerkung: null,
  geheimnisGesetzt: false,
  version: 3,
  letzterLauf: LAUF,
};

describe('Quellen-Seite', () => {
  let fixture: ComponentFixture<QuellenSeite>;
  let element: HTMLElement;
  const service = {
    apiV1QuellenGet: vi.fn(),
    apiV1QuellenCodeLaeufeGet: vi.fn(),
    apiV1QuellenCodePatch: vi.fn(),
    apiV1QuellenCodeGeheimnisPut: vi.fn(),
    apiV1QuellenCodeLaeufePost: vi.fn(),
    apiV1QuellenPost: vi.fn(),
  };

  async function starten(rolle: Rolle) {
    service.apiV1QuellenGet.mockReset().mockReturnValue(of([QUELLE]));
    service.apiV1QuellenCodeLaeufeGet.mockReset().mockReturnValue(of([LAUF]));
    service.apiV1QuellenCodePatch.mockReset().mockReturnValue(of(QUELLE));
    service.apiV1QuellenCodeGeheimnisPut.mockReset().mockReturnValue(of(undefined));
    service.apiV1QuellenCodeLaeufePost.mockReset().mockReturnValue(of(LAUF));
    service.apiV1QuellenPost.mockReset().mockReturnValue(of(QUELLE));
    TestBed.configureTestingModule({
      imports: [QuellenSeite],
      providers: [
        { provide: QuellenService, useValue: service },
        {
          provide: AnmeldungService,
          useValue: {
            darf: (aktion: Aktion) =>
              aktion === 'A27' || (aktion === 'A28' && rolle === 'inhaber'),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(QuellenSeite);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function knopf(text: string): HTMLButtonElement {
    return [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (eintrag) => eintrag.textContent?.trim() === text,
    )!;
  }

  it('zeigt Quellen und Laufzähler lesend mit A27', async () => {
    await starten('treuhaender');
    expect(element.textContent).toContain(QUELLE.name);
    expect(element.textContent).toContain(TEXTE.quellen.zaehler['gefunden']);
    expect(element.textContent).toContain('8');
    expect(element.textContent).toContain(TEXTE.quellen.laeufe);
    expect(element.textContent).not.toContain(TEXTE.quellen.bearbeiten);
    expect(element.textContent).not.toContain(TEXTE.quellen.trockenStarten);
    expect(element.querySelector('input[type=password]')).toBeNull();
  });

  it('zeigt Pflegeaktionen nur mit A28 und verwendet die Quellversion als If-Match', async () => {
    await starten('inhaber');
    knopf(TEXTE.quellen.bearbeiten).click();
    fixture.detectChanges();
    knopf(TEXTE.quellen.speichern).click();
    expect(service.apiV1QuellenCodePatch).toHaveBeenCalledWith(
      QUELLE.code,
      '"3"',
      expect.objectContaining({ status: QUELLE.status }),
    );
    expect(knopf(TEXTE.quellen.trockenStarten)).toBeDefined();
    expect(element.querySelector('input[type=password]')).not.toBeNull();
  });

  it('besteht die axe-Prüfung mit Pflegeaktionen', async () => {
    await starten('inhaber');
    const ergebnis = await axe.run(element, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    });
    expect(ergebnis.violations).toEqual([]);
  });
});
