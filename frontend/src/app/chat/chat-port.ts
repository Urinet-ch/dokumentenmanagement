import { Observable } from 'rxjs';

// Schnittstelle des Chats nach Spec 02 design.md «Chat-Agent» (POST /api/v1/chat als SSE, Gesprächsliste); bis 7.3
// gemergt ist, von Hand geschrieben. HttpChatService setzt sie um, die Tests ersetzen sie durch eine Attrappe.

/** Quelle mit Dokumenttitel und relativem Link `/dokument/<id>` (Req 4.1). */
export interface DokumentQuelle {
  titel: string;
  link: string;
}

/** Quelle «Datenstand: <Zeitpunkt>, <n> Belege» der zählenden Werkzeuge (Req 4.1). */
export interface DatenstandQuelle {
  datenstand: { zeitpunkt: string; anzahl: number };
}

export type Quelle = DokumentQuelle | DatenstandQuelle;

/** Ereignisse des Stroms (`event:`-Name und JSON aus `data:`); unbekannte Ereignisse lässt der Leser weg. */
export type ChatEreignis =
  | { typ: 'werkzeug'; name: string }
  | { typ: 'text'; delta: string }
  | { typ: 'quellen'; quellen: Quelle[] }
  | { typ: 'fertig'; gespraechId: string }
  | { typ: 'fehler'; meldung: string; gespraechId?: string };

export interface GespraechKopf {
  id: string;
  titel: string;
  geaendert: string;
}

export type Rolle = 'BENUTZER' | 'ASSISTENT';
export type NachrichtStatus = 'OK' | 'FEHLER' | 'ABGEBROCHEN';

export interface GespeicherteNachricht {
  rolle: Rolle;
  inhalt: string;
  quellen: Quelle[];
  status: NachrichtStatus | null;
  zeit: string;
}

/**
 * Fehler vor oder während des Stroms: Sitzung abgelaufen (401), Gespräch fremd oder unbekannt (404), zu viele Fragen
 * (429 mit `Retry-After` in Sekunden), Frage ungültig (400), keine Verbindung, unlesbare oder abgebrochene Antwort.
 */
export type ChatFehlerArt =
  'SITZUNG' | 'NICHT_GEFUNDEN' | 'ZU_VIELE' | 'UNGUELTIG' | 'VERBINDUNG' | 'ANTWORT' | 'ALLGEMEIN';

export class ChatFehler extends Error {
  constructor(
    readonly art: ChatFehlerArt,
    /** Nur bei `ZU_VIELE`: Sekunden aus `Retry-After`, `null` ohne lesbaren Wert. */
    readonly sekunden: number | null = null,
  ) {
    super(art);
    this.name = 'ChatFehler';
  }
}

export abstract class ChatPort {
  /** Stellt eine Frage; der Strom endet nach `fertig` oder `fehler`, Abmelden bricht die Anfrage ab. */
  abstract fragen(frage: string, gespraechId: string | null): Observable<ChatEreignis>;
  /** Die letzten 20 eigenen Gespräche, neueste zuerst. */
  abstract gespraeche(): Observable<GespraechKopf[]>;
  abstract gespraech(id: string): Observable<GespeicherteNachricht[]>;
}
