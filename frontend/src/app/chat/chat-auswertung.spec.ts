import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ChatService } from '../gemeinsam/api/api/chat.service';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { ChatAuswertung } from './chat-auswertung';

describe('Chat-Auswertung', () => {
  const api = { apiV1ChatAuswertungGet: vi.fn() };

  beforeEach(() => {
    api.apiV1ChatAuswertungGet.mockReset();
    TestBed.configureTestingModule({
      imports: [ChatAuswertung],
      providers: [
        provideRouter([]),
        { provide: ChatService, useValue: api },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: AnmeldungService, useValue: { anmelden: vi.fn() } },
      ],
    });
  });

  it('zeigt geladene Bewertung und berechnet die Verteilung', async () => {
    api.apiV1ChatAuswertungGet.mockReturnValue(of({
      anzahl: 4, durchschnitt: 3.5, verteilung: [{ sterne: 5, anzahl: 3 }],
    }));
    const fixture = TestBed.createComponent(ChatAuswertung);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[data-test=anzahl-bewertungen]')?.textContent?.trim()).toBe('4');
    expect(root.querySelector('progress')?.value).toBe(75);
  });

  it('leitet bei fehlendem Recht zur Nicht-gefunden-Seite', async () => {
    api.apiV1ChatAuswertungGet.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    const navigation = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ChatAuswertung);
    await fixture.whenStable();
    expect(navigation).toHaveBeenCalledWith('/nicht-gefunden');
  });
});
