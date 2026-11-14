package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.ChatEreignis;

import java.util.function.Consumer;

/**
 * Driven Port des Chat-Agenten (Spec 02, Aufgabe 7.3; design.md «Chat-Agent»). Der Aufruf blockiert, bis die Antwort
 * fertig ist, und meldet unterwegs jedes {@link ChatEreignis} in Reihenfolge an {@code ausgabe}; das letzte ist genau
 * eines von {@link ChatEreignis.Ende} oder {@link ChatEreignis.Ausfall}. Der Aufruf läuft auf einem eigenen Thread im
 * {@code MandantKontext} des Anfragenden, ohne offene Transaktion und ohne Request-Kontext der Anfrage.
 * <p>
 * Der Adapter prüft {@link ChatAbbruch#gesetzt()} vor jedem Modell- und Werkzeugaufruf und kehrt nach einem Abbruch
 * ohne weitere Aufrufe zurück. Eine Ausnahme gilt als {@link ChatEreignis.Ausfall} mit ihrem Klassennamen. Weder Frage
 * noch Antwort noch Werkzeugparameter gehören ins Log (Spec 01, Req 16.7).
 */
public interface ChatAgentPort {

    void beantworten(ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch);
}
