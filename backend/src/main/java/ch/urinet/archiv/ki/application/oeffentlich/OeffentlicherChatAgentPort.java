package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;

import java.util.function.Consumer;

/**
 * Driven Port des öffentlichen Chat-Agenten (Spec 03, Aufgabe 11.3). Vertrag wie {@code ChatAgentPort}: Der Aufruf
 * blockiert bis zur fertigen Antwort, meldet jedes {@link ChatEreignis} in Reihenfolge, das letzte ist genau eines von
 * {@link ChatEreignis.Ende} oder {@link ChatEreignis.Ausfall}; er läuft auf einem eigenen Thread im Mandanten-Kontext der
 * Anfrage, ohne Transaktion. Der Adapter prüft {@link ChatAbbruch#gesetzt()} vor jedem Modell- und Werkzeugaufruf. Weder
 * Frage noch Antwort noch Werkzeugparameter gehören ins Log.
 */
public interface OeffentlicherChatAgentPort {

    void beantworten(OeffentlicheAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch);
}
