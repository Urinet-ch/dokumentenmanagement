package ch.urinet.archiv.ki.application.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sprachmodelle schreiben UUIDs beim Abschreiben aus einem Werkzeugergebnis unzuverlässig ab. Eine unbekannte ID wird
 * darum einer ID zugeordnet, die {@code dokumente_suchen} im selben Lauf geliefert hat, wenn genau eine passt: gleiche
 * Länge und höchstens {@link #ABWEICHUNGEN_MAX} abweichende Zeichen oder gleiche ersten 8 Zeichen. Es gibt nie ein
 * Erraten fremder IDs: Kandidaten sind nur Treffer dieses Laufs, die der Benutzer nach der Sichtregel schon gesehen hat.
 */
public final class GeseheneIds {

    static final int ABWEICHUNGEN_MAX = 2;
    private static final int PRAEFIX = 8;

    private GeseheneIds() {
    }

    /** Die zugeordnete ID oder {@code id} unverändert (auch bei gültiger ID, bei mehreren oder keinem Kandidaten). */
    public static String zuordnen(String id, Set<UUID> gesehene) {
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
