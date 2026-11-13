package ch.urinet.archiv.export.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.util.Set;

/**
 * Wer einen Jahresexport anfordern und abrufen darf (Spec 02, Req 7.1, 7.5): {@code inhaber} und {@code treuhaender};
 * {@code mitarbeiter} und {@code geschaeftsstelle} erhalten 403. Die Rechtematrix aus Spec 01, Req 2.6 hat für den
 * Export keine eigene Zeile; massgeblich ist dieses Requirement. Es gilt die stärkste Rolle (Req 2.2): Wer
 * {@code mitarbeiter} und {@code treuhaender} hat, gilt als {@code mitarbeiter} und darf nicht exportieren.
 */
public final class Exportrecht {

    public static final Set<Rolle> ERLAUBT = Set.of(Rolle.INHABER, Rolle.TREUHAENDER);

    private Exportrecht() {
    }

    public static boolean erlaubt(Rolle rolle) {
        return rolle != null && ERLAUBT.contains(rolle);
    }
}
