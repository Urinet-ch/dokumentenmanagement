package ch.urinet.archiv.ki.domain.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Sammelt die Ereignisse eines Durchgangs (Text, Werkzeugergebnisse, Tokens) und gibt die für den Client bestimmten
 * ({@link ChatEreignis.WerkzeugStart}, {@link ChatEreignis.Text}) in Reihenfolge weiter. Nach {@link ChatEreignis.Ende}
 * oder {@link ChatEreignis.Ausfall} ist der Durchgang abgeschlossen; spätere Ereignisse zählen nicht. Threadsicher, weil
 * ein Adapter aus anderen Threads melden darf.
 */
public final class ChatDurchgang {

    private final StringBuilder text = new StringBuilder();
    private final List<ChatEreignis.WerkzeugErgebnis> ergebnisse = new ArrayList<>();
    private ChatEreignis.Ende ende;
    private ChatEreignis.Ausfall ausfall;

    /** Nimmt das Ereignis auf und gibt es, falls für den Client bestimmt, unter derselben Sperre an {@code weiter}. */
    public synchronized void aufnehmen(ChatEreignis ereignis, Consumer<ChatEreignis> weiter) {
        if (abgeschlossen()) {
            return;
        }
        switch (ereignis) {
            case ChatEreignis.WerkzeugStart start -> weiter.accept(start);
            case ChatEreignis.Text teil -> {
                text.append(teil.delta());
                weiter.accept(teil);
            }
            case ChatEreignis.WerkzeugErgebnis ergebnis -> ergebnisse.add(ergebnis);
            case ChatEreignis.Ende e -> ende = e;
            case ChatEreignis.Ausfall a -> ausfall = a;
        }
    }

    /** Ausfall ausserhalb der Ereignisse (Ausnahme des Adapters, kein Abschluss); ohne Wirkung nach dem Abschluss. */
    public synchronized void ausfallen(String grund) {
        if (!abgeschlossen()) {
            ausfall = new ChatEreignis.Ausfall(grund);
        }
    }

    public synchronized boolean abgeschlossen() {
        return ende != null || ausfall != null;
    }

    /** Grund, falls der Durchgang nicht mit {@link ChatEreignis.Ende} schloss; ohne Abschluss «KeinEnde». */
    public synchronized Optional<String> ausfallgrund() {
        if (ausfall != null) {
            return Optional.of(ausfall.grund());
        }
        return ende == null ? Optional.of("KeinEnde") : Optional.empty();
    }

    /**
     * Antwort zum Speichern: {@code OK}, wenn der Durchgang mit {@link ChatEreignis.Ende} schloss; sonst
     * {@code ABGEBROCHEN}, wenn der Client den Stream geschlossen hat, sonst {@code FEHLER}. Quellen nach
     * {@link Quellen#aus}.
     */
    public synchronized ChatAntwort antwort(boolean abgebrochen) {
        ChatStatus status = ende != null && ausfall == null ? ChatStatus.OK
                : abgebrochen ? ChatStatus.ABGEBROCHEN : ChatStatus.FEHLER;
        return new ChatAntwort(text.toString(), ergebnisse.stream().map(WerkzeugAufruf::aus).toList(),
                Quellen.aus(ergebnisse), Optional.ofNullable(ende), status);
    }
}
