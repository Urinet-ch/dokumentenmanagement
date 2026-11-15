package ch.urinet.archiv.ki.application.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sprachmodelle schreiben UUIDs beim Abschreiben aus einem Werkzeugergebnis unzuverlässig ab. Eine Gesprächsnummer wird
 * auf die vorherigen Suchtreffer abgebildet; eine veränderte ID nur auf eine eindeutige ID aus Werkzeugergebnissen des
 * Gesprächs. Es gibt nie ein Erraten fremder IDs: Kandidaten sind nur Treffer, die nach der Sichtregel im Gespräch
 * zurückgegeben wurden.
 */
public final class GeseheneIds {

    static final int ABWEICHUNGEN_MAX = 2;
    private static final int PRAEFIX = 8;

    private GeseheneIds() {
    }

    /** Die zugeordnete ID oder {@code id} unverändert (auch bei gültiger ID, bei mehreren oder keinem Kandidaten). */
    public static String zuordnen(String id, Set<UUID> gesehene) {
        return zuordnen(id, gesehene, List.of());
    }

    /** Ordnet zuerst eine Gesprächsnummer, danach eine veränderte ID einem gesehenen Treffer zu. */
    public static String zuordnen(String id, Set<UUID> gesehene, List<UUID> nummerierteTreffer) {
        Integer nummer = nummer(id);
        if (nummer != null && nummer <= nummerierteTreffer.size()) {
            return nummerierteTreffer.get(nummer - 1).toString();
        }
        UUID exakt = Werkzeugparameter.id(id);
        if (id == null || exakt != null && gesehene.contains(exakt)) {
            return id;
        }
        String roh = id.strip().toLowerCase(java.util.Locale.ROOT);
        if (roh.length() != 36) {
            return id;
        }
        List<UUID> kandidaten = gesehene.stream().filter(g -> passt(roh, g.toString())).toList();
        return kandidaten.size() == 1 ? kandidaten.get(0).toString() : id;
    }

    private static Integer nummer(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Integer.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean passt(String roh, String echt) {
        if (roh.regionMatches(0, echt, 0, PRAEFIX)) {
            return true;
        }
        int abweichend = 0;
        for (int i = 0; i < echt.length(); i++) {
            if (roh.charAt(i) != echt.charAt(i) && ++abweichend > ABWEICHUNGEN_MAX) {
                return false;
            }
        }
        return true;
    }
}
