import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { ApiToken, APITokensService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { ApiTokens } from './api-tokens';

const TOKEN: ApiToken = {
  id: 'a1b2c3d4e5f6',
  name: 'Rechnungsgenerator',
  scope: 'generator',
  aktiv: true,
  erstellt: '2026-11-13T08:00:00Z',
  zuletzt: null,
};

describe('API-Token-Übersicht', () => {
  let fixture: ComponentFixture<ApiTokens>;
  let element: HTMLElement;
  const service = {
    apiV1ApiTokensGet: vi.fn(),
    apiV1ApiTokensTokenIdDelete: vi.fn(),
  };

  async function starten(tokens: ApiToken[] = [TOKEN]) {
    service.apiV1ApiTokensGet.mockReset().mockReturnValue(of(tokens));
    service.apiV1ApiTokensTokenIdDelete.mockReset().mockReturnValue(of(undefined));
    TestBed.configureTestingModule({
      imports: [ApiTokens],
      providers: [{ provide: APITokensService, useValue: service }],
    });
    fixture = TestBed.createComponent(ApiTokens);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function zeile(name = TOKEN.name!): HTMLElement {
    return [...element.querySelectorAll<HTMLElement>('[data-test=token]')].find(
      (eintrag) => eintrag.querySelector('bdi')?.textContent === name,
    )!;
  }

  function knopf(eintrag: HTMLElement, text: string): HTMLButtonElement {
    return [...eintrag.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!;
  }

  it('zeigt Token ohne Geheimnis, isoliert den Namen und meldet ungenutzte Tokens', async () => {
    await starten([{ ...TOKEN, name: '<img src=x>' }]);
    expect(zeile('<img src=x>').querySelector('bdi')?.textContent).toBe('<img src=x>');
    expect(element.querySelector('img')).toBeNull();
    expect(zeile('<img src=x>').textContent).toContain(TEXTE.apiTokens.nieGenutzt);
    expect(element.textContent).not.toContain('ua_');
  });

  it('zeigt einen eigenen Hinweis bei leerer Liste', async () => {
    await starten([]);
    expect(element.textContent).toContain(TEXTE.apiTokens.keine);
    expect(element.querySelector('[data-test=token]')).toBeNull();
  });

  it('widerruft erst nach Bestätigung und lädt die Liste neu', async () => {
    await starten();
    service.apiV1ApiTokensGet.mockReturnValueOnce(of([]));
    knopf(zeile(), TEXTE.apiTokens.widerrufen).click();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.apiTokens.widerrufFrage);
    knopf(zeile(), TEXTE.apiTokens.widerrufBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(service.apiV1ApiTokensTokenIdDelete).toHaveBeenCalledExactlyOnceWith(TOKEN.id);
    expect(service.apiV1ApiTokensGet).toHaveBeenCalledTimes(2);
    expect(element.textContent).toContain(TEXTE.apiTokens.widerrufErfolgreich);
    expect(element.textContent).toContain(TEXTE.apiTokens.keine);
  });

  it('meldet NICHT_GEFUNDEN und lädt die Liste neu', async () => {
    await starten();
    service.apiV1ApiTokensGet.mockReturnValueOnce(of([]));
    service.apiV1ApiTokensTokenIdDelete.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 404, error: { fehlercode: 'NICHT_GEFUNDEN' } }),
      ),
    );
    knopf(zeile(), TEXTE.apiTokens.widerrufen).click();
    fixture.detectChanges();
    knopf(zeile(), TEXTE.apiTokens.widerrufBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.apiTokens.nichtMehrVorhanden);
    expect(service.apiV1ApiTokensGet).toHaveBeenCalledTimes(2);
  });

  it('meldet bei 403 das fehlende Recht', async () => {
    await starten();
    service.apiV1ApiTokensTokenIdDelete.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 403, error: { fehlercode: 'NICHT_ERLAUBT' } }),
      ),
    );
    knopf(zeile(), TEXTE.apiTokens.widerrufen).click();
    fixture.detectChanges();
    knopf(zeile(), TEXTE.apiTokens.widerrufBestaetigen).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      TEXTE.apiTokens.fehlerRecht,
    );
    expect(service.apiV1ApiTokensGet).toHaveBeenCalledTimes(1);
  });

  it('sendet bei einem Doppelklick nur eine Widerrufsanfrage', async () => {
    await starten();
    const wartend = new Subject<void>();
    service.apiV1ApiTokensTokenIdDelete.mockReturnValue(wartend);
    knopf(zeile(), TEXTE.apiTokens.widerrufen).click();
    fixture.detectChanges();
    const bestaetigen = knopf(zeile(), TEXTE.apiTokens.widerrufBestaetigen);
    bestaetigen.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    bestaetigen.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(service.apiV1ApiTokensTokenIdDelete).toHaveBeenCalledExactlyOnceWith(TOKEN.id);
    wartend.next();
    wartend.complete();
  });
});
