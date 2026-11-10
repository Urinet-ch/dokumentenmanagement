package ch.urinet.archiv.probe;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Sichtbarkeit;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sichtbarkeit nur für Tests, bis 2.2 den {@code VertraulichkeitFilter} bringt: feste Probe-Dokumente im Mandanten
 * {@code urinet}, sichtbar nach Zeile A2 ({@code NORMAL}) bzw. A3 ({@code INTERN}) der Rechtematrix und nur im eigenen
 * Mandanten (Req 1.4, 2.3).
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ProbeSichtbarkeit implements Sichtbarkeit {

    public static final Map<String, UUID> NAMEN = Map.of(
            "NORMAL", UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "INTERN", UUID.fromString("00000000-0000-4000-8000-000000000002"),
            "UNBEKANNT", UUID.fromString("00000000-0000-4000-8000-0000000000ff"));

    private record Dokument(MandantCode mandant, Aktion lesen) {
    }

    private static final Map<UUID, Dokument> DOKUMENTE = Map.of(
            NAMEN.get("NORMAL"), new Dokument(new MandantCode("urinet"), Aktion.A2),
            NAMEN.get("INTERN"), new Dokument(new MandantCode("urinet"), Aktion.A3));

    private final MandantAufloesung aufloesung;

    public ProbeSichtbarkeit(MandantAufloesung aufloesung) {
        this.aufloesung = aufloesung;
    }

    @Override
    public boolean sichtbar(Benutzer benutzer, UUID dokumentId) {
        Dokument dokument = DOKUMENTE.get(dokumentId);
        return dokument != null && dokument.mandant().equals(aufloesung.pruefen())
                && Set.of(Ergebnis.ERLAUBT, Ergebnis.NUR_LESEND)
                .contains(Rechtematrix.ergebnis(benutzer.rolle(), dokument.lesen()));
    }
}
