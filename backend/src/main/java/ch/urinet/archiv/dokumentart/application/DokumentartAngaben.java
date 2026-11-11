package ch.urinet.archiv.dokumentart.application;

import java.util.List;
import java.util.Set;

/**
 * Angaben zum Anlegen oder Ändern einer Dokumentart. {@code gesetzt} unterscheidet «nicht angegeben» von «ausdrücklich
 * {@code null}» (z. B. {@code aufbewahrungJahre = null} für unbefristet). {@code zusatzSchema} ist JSON-Text.
 */
public record DokumentartAngaben(Set<Feld> gesetzt, String code, String name, String beschreibung,
                                 List<String> gruppen, List<String> pflichtfelder, String zusatzSchema,
                                 String vertraulichkeit, Integer aufbewahrungJahre, String aufbewahrungAb,
                                 Boolean gesetzlichePflicht, Boolean aktiv) {

    public enum Feld {
        CODE, NAME, BESCHREIBUNG, GRUPPEN, PFLICHTFELDER, ZUSATZ_SCHEMA, VERTRAULICHKEIT, AUFBEWAHRUNG_JAHRE,
        AUFBEWAHRUNG_AB, GESETZLICHE_PFLICHT, AKTIV
    }

    public DokumentartAngaben {
        gesetzt = Set.copyOf(gesetzt);
        gruppen = gruppen == null ? null : List.copyOf(gruppen);
        pflichtfelder = pflichtfelder == null ? null : List.copyOf(pflichtfelder);
    }

    public boolean hat(Feld feld) {
        return gesetzt.contains(feld);
    }
}
