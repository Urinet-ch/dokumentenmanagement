import { HttpClient, HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';
import { retryAfterSekunden } from '../gemeinsam/retry-after';
import {
  ChatEreignis,
  ChatFehler,
  ChatPort,
  GespeicherteNachricht,
  GespraechKopf,
  ZuVieleGrund,
} from './chat-port';
import { SSE_START, SseZustand, chatEreignis, nachrichtIdAus, quellenAus, sseLesen } from './sse-leser';

const PFAD = '/api/v1/chat';

export { retryAfterSekunden as sekundenAus } from '../gemeinsam/retry-after';

/** Feld `grund` der Problem Details eines 429 (als Text bei `responseType: 'text'`, sonst als Objekt). */
export function grundAus(body: unknown): ZuVieleGrund | null {
  let wert = body;
  if (typeof wert === 'string') {
    try {
      wert = JSON.parse(wert);
    } catch {
      return null;
    }
  }
  const grund = typeof wert === 'object' && wert !== null ? (wert as { grund?: unknown }).grund : null;
  return grund === 'STUNDENLIMIT' || grund === 'LAEUFT' ? grund : null;
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
    case 403:
      return new ChatFehler('NICHT_ERLAUBT');
    case 404:
      return new ChatFehler('NICHT_GEFUNDEN');
    case 429:
      return new ChatFehler(
        'ZU_VIELE',
        retryAfterSekunden((fehler as HttpErrorResponse).headers.get('Retry-After')),
        grundAus((fehler as HttpErrorResponse).error),
      );
    case 422:
      return new ChatFehler('ANALYSE_NICHT_MOEGLICH');
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

  analyseVorschlagen(nachrichtId: string): Observable<string> {
    return this.http.post<{ id: string }>('/api/v1/wissensentwuerfe/analyse', { nachrichtId }).pipe(
      map((antwort) => {
        if (typeof antwort.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(antwort.id)) {
          throw new ChatFehler('ANTWORT');
        }
        return antwort.id;
      }),
      catchError((fehler: unknown) => throwError(() => chatFehlerAus(fehler))),
    );
  }
}

/** Nachrichten als Liste oder als `{nachrichten: [...]}`; Quellen nur in lesbarer Form. */
export function nachrichtenAus(wert: unknown): GespeicherteNachricht[] {
  const liste = Array.isArray(wert)
    ? wert
    : typeof wert === 'object' &&
        wert !== null &&
        Array.isArray((wert as { nachrichten?: unknown }).nachrichten)
      ? (wert as { nachrichten: unknown[] }).nachrichten
      : null;
  if (liste === null) {
    throw new ChatFehler('ANTWORT');
  }
  return liste.map((n) => {
    const x = (n ?? {}) as Record<string, unknown>;
    const id = nachrichtIdAus(x['id']);
    return {
      ...(id ? { id } : {}),
      ...(typeof x['analyseMoeglich'] === 'boolean' ? { analyseMoeglich: x['analyseMoeglich'] } : {}),
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
