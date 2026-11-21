import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { MeineAnalysen } from './meine-analysen';

describe('Meine Analysevorschläge', () => {
  const benutzer = signal({ id: 'u1', rolle: 'mitarbeiter', mandant: 'urinet' });
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: AnmeldungService, useValue: { benutzer } }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('zeigt nur Status und Entscheid des eigenen Vorschlags', async () => {
    const fixture = TestBed.createComponent(MeineAnalysen);
    fixture.componentRef.setInput('id', 'e1');
    fixture.detectChanges();
    http.expectOne('/api/v1/wissensentwuerfe/e1').flush({
      id: 'e1', status: 'ERLEDIGT', entscheidungen: [{ nr: 1, status: 'VERWORFEN', grund: 'Unklar' }],
    });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Erledigt');
    expect(fixture.nativeElement.textContent).toContain('Verworfen');
    expect(fixture.nativeElement.textContent).not.toContain('ERLEDIGT');
    expect(fixture.nativeElement.textContent).not.toContain('VERWORFEN');
    expect(fixture.nativeElement.textContent).toContain('Unklar');
  });

  it('fällt bei unbekannten Codes auf den Code zurück', async () => {
    const fixture = TestBed.createComponent(MeineAnalysen);
    fixture.componentRef.setInput('id', 'e2');
    fixture.detectChanges();
    http.expectOne('/api/v1/wissensentwuerfe/e2').flush({ id: 'e2', status: 'NEU_X', entscheidungen: [] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('NEU_X');
  });

  it('filtert die Vollansicht eines A17-Benutzers auf eigene Analysen', async () => {
    const fixture = TestBed.createComponent(MeineAnalysen);
    fixture.detectChanges();
    http.expectOne('/api/v1/wissensentwuerfe').flush([
      { entwurf: { id: 'eigene', status: 'OFFEN', anlass: 'ANALYSE', erstelltVon: 'u1' } },
      { entwurf: { id: 'fremde', status: 'OFFEN', anlass: 'ANALYSE', erstelltVon: 'u2' } },
      { entwurf: { id: 'andere', status: 'OFFEN', anlass: 'MANUELL', erstelltVon: 'u1' } },
    ]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('eigene');
    expect(fixture.nativeElement.textContent).not.toContain('fremde');
    expect(fixture.nativeElement.textContent).not.toContain('andere');
  });
});
