package ch.urinet.archiv.protokoll.application;

import java.util.Map;

/** Driven Port: liest ein JSON-Objekt als Baum aus Map, List, String, Number, Boolean und null (Prüfwerkzeug). */
public interface JsonLeser {

    /**
     * @throws IllegalArgumentException wenn die Bytes kein JSON-Objekt sind oder ein Schlüssel doppelt vorkommt
     */
    Map<String, Object> objekt(byte[] json);
}
