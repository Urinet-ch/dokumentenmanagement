import { HttpClient, HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';
import {
  ChatEreignis,
  ChatFehler,
  ChatPort,
  GespeicherteNachricht,
  GespraechKopf,
} from './chat-port';
import { SSE_START, SseZustand, chatEreignis, quellenAus, sseLesen } from './sse-leser';

const PFAD = '/api/v1/chat';

/** `Retry-After` in Sekunden oder als HTTP-Datum; `null` ohne lesbaren Wert. */
export function sekundenAus(wert: string | null, jetzt = Date.now()): number | null {
  if (wert === null || wert.trim() === '') {
    return null;
  }
  if (/^\d+$/.test(wert.trim())) {
    return Number(wert.trim());
  }
  const zeit = Date.parse(wert);
  return Number.isNaN(zeit) ? null : Math.max(0, Math.ceil((zeit - jetzt) / 1000));
}

export function chatFehlerAus(fehler: unknown): ChatFehler {
  if (fehler instanceof ChatFehler) {
    return fehler;
  }
  const status = fehler instanceof HttpErrorResponse ? fehler.status : -1;
  switch (status) {
    case 0:
      return new ChatFehler('VERBINDUNG');
    case 400:
      return new ChatFehler('UNGUELTIG');
    case 401:
      return new ChatFehler('SITZUNG');
    case 404:
      return new ChatFehler('NICHT_GEFUNDEN');
    case 429:
      return new ChatFehler(
        'ZU_VIELE',
        sekundenAus((fehler as HttpErrorResponse).headers.get('Retry-After')),
      );
    default:
      return new ChatFehler('ALLGEMEIN');
  }
}

/**
 * Chat über `HttpClient` (Spec 02, Aufgabe 7.4): Der Interceptor hängt das Token an und beendet bei 401 die Sitzung,
 * darum kein `EventSource` (kann keinen Header senden, das Token liegt nur im Arbeitsspeicher). Der Fetch-Backend von
 * Angular meldet mit `reportProgress` jedes gelesene Stück als `partialText`; der Leser bekommt nur den neuen Teil.
 */
@Injectable()
export class HttpChatService extends ChatPort {
  private readonly http = inject(HttpClient);

  fragen(frage: string, gespraechId: string | null): Observable<ChatEreignis> {
    const body = gespraechId ? { frage, gespraechId } : { frage };
    return new Observable<ChatEreignis>((abonnent) => {
      let zustand: SseZustand = SSE_START;
      let gelesen = 0;
      let ok = false;
      let beendet = false;

      const lesen = (text: string) => {
        const neu = text.slice(gelesen);
        gelesen = text.length;
        const ergebnis = sseLesen(zustand, neu);
        zustand = ergebnis.zustand;
        for (const sse of ergebnis.ereignisse) {
          const ereignis = beendet ? null : chatEreignis(sse);
          if (ereignis) {
            abonnent.next(ereignis);
            beendet = ereignis.typ === 'fertig' || ereignis.typ === 'fehler';
          }
        }
      };

      const anfrage = this.http
        .post(PFAD, body, {
          observe: 'events',
          reportProgress: true,
          responseType: 'text',
          headers: { Accept: 'text/event-stream' },
        })
        .subscribe({
          next: (ereignis) => {
            try {
              if (ereignis.type === HttpEventType.ResponseHeader) {
                ok = ereignis.ok;
              } else if (ereignis.type === HttpEventType.DownloadProgress && ok) {
                lesen(ereignis.partialText ?? '');
              } else if (ereignis.type === HttpEventType.Response) {
                lesen(ereignis.body ?? '');
                // ohne `fertig` oder `fehler` ist die Antwort unvollständig (Verbindung abgerissen, falsche Seite)
                if (beendet) {
                  abonnent.complete();
                } else {
                  abonnent.error(new ChatFehler('ANTWORT'));
                }
              }
            } catch (fehler) {
              abonnent.error(chatFehlerAus(fehler));
            }
          },
          error: (fehler: unknown) => abonnent.error(chatFehlerAus(fehler)),
        });
      // Abmelden (Taste «Abbrechen», Seite verlassen) bricht die Anfrage ab (AbortController im Fetch-Backend)
      return () => anfrage.unsubscribe();
    });
  }

  gespraeche(): Observable<GespraechKopf[]> {
    return this.http
      .get<GespraechKopf[]>(`${PFAD}/gespraeche`)
      .pipe(catchError((fehler: unknown) => throwError(() => chatFehlerAus(fehler))));
  }

  gespraech(id: string): Observable<GespeicherteNachricht[]> {
    return this.http.get<unknown>(`${PFAD}/gespraeche/${encodeURIComponent(id)}`).pipe(
      map(nachrichtenAus),
      catchError((fehler: unknown) => throwError(() => chatFehlerAus(fehler))),
    );
  }
}

/** Nachrichten als Liste oder als `{nachrichten: [...]}`; Quellen nur in lesbarer Form. */
export function nachrichtenAus(wert: unknown): GespeicherteNachricht[] {
  const liste =
    Array.isArray(wert)
      ? wert
      : typeof wert === 'object' && wert !== null && Array.isArray((wert as { nachrichten?: unknown }).nachrichten)
        ? (wert as { nachrichten: unknown[] }).nachrichten
        : null;
  if (liste === null) {
    throw new ChatFehler('ANTWORT');
  }
  return liste.map((n) => {
    const x = (n ?? {}) as Record<string, unknown>;
    return {
      rolle: x['rolle'] === 'BENUTZER' ? 'BENUTZER' : 'ASSISTENT',
      inhalt: typeof x['inhalt'] === 'string' ? x['inhalt'] : '',
      quellen: quellenAus(x['quellen']),
      status:
        x['status'] === 'OK' || x['status'] === 'FEHLER' || x['status'] === 'ABGEBROCHEN'
          ? x['status']
          : null,
      zeit: typeof x['zeit'] === 'string' ? x['zeit'] : '',
    };
  });
}
