import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungenVerlauf } from './einstellungen-verlauf';

describe('Verlauf der Einstellungen', () => {
  const pfad = '/api/v1/konfiguration/versionen';

  it('vergleicht ausgewählte Versionen ohne Kennungswerte', () => {
    TestBed.configureTestingModule({
      imports: [EinstellungenVerlauf],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(EinstellungenVerlauf);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne(pfad).flush([
      { version: 2, zeit: '2026-11-23T09:00:00Z' },
      { version: 1, zeit: '2026-11-22T09:00:00Z' },
    ]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('22.11.2026');
    element.querySelector<HTMLButtonElement>('button')!.click();
    http.expectOne(`${pfad}/1`).flush({ inhalt: { einstellungen: { kennungen: { uid: 'CHE-OLD' }, chat: { aufbewahrungMonate: 12 } } } });
    http.expectOne(`${pfad}/2`).flush({ inhalt: { einstellungen: { kennungen: { uid: 'CHE-NEW' }, chat: { aufbewahrungMonate: 24 } } } });
    fixture.detectChanges();
    expect(element.textContent).toContain('einstellungen.chat.aufbewahrungMonate');
    expect(element.textContent).toContain('12');
    expect(element.textContent).toContain('24');
    expect(element.textContent).toContain(TEXTE.einstellungen.verborgen);
    expect(element.textContent).not.toContain('CHE-OLD');
    expect(element.textContent).not.toContain('CHE-NEW');
    http.verify();
  });

  it('zeigt bei einer ungültigen Versionsliste einen Fehler', () => {
    TestBed.configureTestingModule({
      imports: [EinstellungenVerlauf],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(EinstellungenVerlauf);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(pfad).flush([{ version: 'falsch' }]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent)
      .toContain(TEXTE.einstellungen.vergleichFehler);
    http.verify();
  });
});
