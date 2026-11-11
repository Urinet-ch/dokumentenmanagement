package ch.urinet.archiv.gemeinsam.domain;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Ursachen-Kette eines Fehlers fürs Log (Req 16.7): nur Klassennamen, äusserste zuerst, bei einer {@link SQLException}
 * mit SQLState; nie Meldungen, denn die enthalten Werte wie «Key (code)=(…)», Token oder Belegdaten.
 */
public final class Ursachen {

    private static final int MAX_URSACHEN = 10;

    private Ursachen() {
    }

    public static String kette(Throwable fehler) {
        List<String> kette = new ArrayList<>();
        Set<Throwable> gesehen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = fehler; t != null && gesehen.add(t) && kette.size() < MAX_URSACHEN; t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                name += "[sqlstate=" + sql.getSQLState() + "]";
            }
            kette.add(name);
        }
        return String.join(" <- ", kette);
    }
}
