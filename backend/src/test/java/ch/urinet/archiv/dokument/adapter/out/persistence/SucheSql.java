package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;

import java.util.Map;

/** Zugang der Tests zum SQL der Suche ({@code PanacheDokumentSuche.sql} ist package-private), z. B. für {@code EXPLAIN}. */
public final class SucheSql {

    private SucheSql() {
    }

    public static String sql(Suchanfrage anfrage, Sichtregel regel, boolean mitDateiname, Map<String, Object> parameter) {
        return PanacheDokumentSuche.sql(anfrage, regel, mitDateiname, parameter);
    }
}
