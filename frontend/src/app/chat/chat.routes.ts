import { Routes } from '@angular/router';
import { Chat } from './chat';
import { ChatPort } from './chat-port';
import { HttpChatService } from './http-chat.service';

/** Route der Seite «Chat» mit ihrem Port, lazy geladen, damit Dienst und SSE-Leser nicht im Initial-Bundle liegen. */
export const CHAT_ROUTES: Routes = [
  { path: '', component: Chat, providers: [{ provide: ChatPort, useClass: HttpChatService }] },
];
