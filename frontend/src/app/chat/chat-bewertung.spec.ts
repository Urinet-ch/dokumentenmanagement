import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../gemeinsam/api/provide-api';
import { ChatBewertung } from './chat-bewertung';

describe('Chat-Bewertung', () => {
  beforeEach(() => TestBed.configureTestingModule({
    imports: [ChatBewertung],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('')],
  }));

  it('speichert, lädt, ändert und entfernt eine Bewertung', async () => {
    const fixture = TestBed.createComponent(ChatBewertung);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('nachrichtId', '42');
    await fixture.whenStable();
    http.expectOne('/api/v1/chat/nachrichten/42/bewertung').flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const sterne = () => element.querySelectorAll<HTMLButtonElement>('[data-test=stern]');
    expect(sterne()).toHaveLength(5);
    expect(sterne()[1].getAttribute('aria-label')).toBe('Antwort bewerten, 2 von 5 Sternen');
    sterne()[1].click();
    await fixture.whenStable();
    const auswahl = element.querySelector('select')!;
    auswahl.value = 'FALSCH';
    auswahl.dispatchEvent(new Event('change'));
    const feld = element.querySelector('textarea')!;
    expect(feld.maxLength).toBe(500);
    feld.value = '<b>falsch</b>';
    feld.dispatchEvent(new Event('input'));
    [...element.querySelectorAll('button')].find((b) => b.textContent?.includes('Bewertung speichern'))!.click();
    const speichern = http.expectOne('/api/v1/chat/nachrichten/42/bewertung');
    expect(speichern.request.method).toBe('PUT');
    expect(speichern.request.body).toEqual({ sterne: 2, grund: 'FALSCH', freitext: '<b>falsch</b>' });
    speichern.flush({ nachrichtId: '42', sterne: 2, grund: 'FALSCH', freitext: '<b>falsch</b>', erstellt: '', geaendert: '' });
    await fixture.whenStable();
    expect(sterne()[1].getAttribute('aria-pressed')).toBe('true');
    fixture.componentRef.setInput('nachrichtId', '43');
    await fixture.whenStable();
    http.expectOne('/api/v1/chat/nachrichten/43/bewertung').flush({
      nachrichtId: '43', sterne: 4, grund: null, freitext: null, erstellt: '', geaendert: '',
    });
    await fixture.whenStable();
    expect(sterne()[3].getAttribute('aria-pressed')).toBe('true');
    [...element.querySelectorAll('button')].find((b) => b.textContent?.includes('Bewertung entfernen'))!.click();
    const entfernen = http.expectOne('/api/v1/chat/nachrichten/43/bewertung');
    expect(entfernen.request.method).toBe('DELETE');
    entfernen.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(element.querySelector('textarea')).toBeNull();
    http.verify();
  });

  it('zeigt Ladefehler und ermöglicht einen erneuten Versuch', async () => {
    const fixture = TestBed.createComponent(ChatBewertung);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('nachrichtId', '42');
    await fixture.whenStable();
    http.expectOne('/api/v1/chat/nachrichten/42/bewertung').flush({}, { status: 500, statusText: 'Fehler' });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role=alert]')?.textContent).toContain('konnte nicht geladen werden');
    element.querySelector('button')!.click();
    http.expectOne('/api/v1/chat/nachrichten/42/bewertung').flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(element.querySelectorAll('[data-test=stern]')).toHaveLength(5);
    http.verify();
  });
});
