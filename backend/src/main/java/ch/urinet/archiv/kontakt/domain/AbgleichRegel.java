package ch.urinet.archiv.kontakt.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Entscheid des Partner-Abgleichs (design.md «KI», {@code PartnerAbgleich}; Req 7.4). Die Ähnlichkeit rechnet die
 * Datenbank; hier wird nur entschieden.
 * <ol>
 *     <li>Exakte Treffer über E-Mail, UID, IBAN in dieser Reihenfolge. Genau ein Kontakt über alle Stufen → verknüpfen;
 *     mehrere Kontakte auf einer Stufe oder Stufen, die auf verschiedene Kontakte zeigen → «unsicher» (wie die mögliche
 *     Dublette beim CSV-Import, design.md «Kontakt und Importe»).</li>
 *     <li>Sonst Namensähnlichkeit: bester Kandidat ab {@code schwelle}; liegt ein zweiter weniger als {@code abstand}
 *     dahinter (auch unter der Schwelle), → «unsicher».</li>
 *     <li>Sonst «neuer Partner?».</li>
 * </ol>
 */
public final class AbgleichRegel {

    /** Abstand zum Zweitbesten, unter dem ein Treffer «unsicher» ist (design.md: 0.1). */
    public static final BigDecimal ABSTAND = new BigDecimal("0.1");

    private AbgleichRegel() {
    }

    /** Stufe 1; leer, wenn keine Stufe einen Kontakt gefunden hat. {@code treffer} je Merkmal, fehlende Stufen leer. */
    public static Optional<AbgleichErgebnis> exakt(Map<Merkmal, Set<UUID>> treffer) {
        Set<UUID> alle = new LinkedHashSet<>();
        Merkmal erstes = null;
        boolean mehrdeutig = false;
        for (Merkmal merkmal : List.of(Merkmal.EMAIL, Merkmal.UID, Merkmal.IBAN)) {
            Set<UUID> stufe = treffer.getOrDefault(merkmal, Set.of());
            if (stufe.isEmpty()) {
                continue;
            }
            if (erstes == null) {
                erstes = merkmal;
            }
            mehrdeutig |= stufe.size() > 1;
            alle.addAll(stufe);
        }
        if (erstes == null) {
            return Optional.empty();
        }
        if (mehrdeutig || alle.size() > 1) {
            return Optional.of(new AbgleichErgebnis.Unsicher(new ArrayList<>(alle), erstes));
        }
        return Optional.of(new AbgleichErgebnis.Verknuepft(alle.iterator().next(), erstes));
    }

    /**
     * Stufe 2: {@code kandidaten} absteigend nach Ähnlichkeit (die Datenbank liefert die zwei besten ab
     * {@code schwelle - ABSTAND}).
     */
    public static AbgleichErgebnis aehnlich(List<PartnerKandidat> kandidaten, BigDecimal schwelle) {
        if (kandidaten.isEmpty() || kandidaten.getFirst().aehnlichkeit().compareTo(schwelle) < 0) {
            return new AbgleichErgebnis.NeuerPartner();
        }
        PartnerKandidat bester = kandidaten.getFirst();
        if (kandidaten.size() > 1) {
            PartnerKandidat zweiter = kandidaten.get(1);
            if (bester.aehnlichkeit().subtract(zweiter.aehnlichkeit()).compareTo(ABSTAND) < 0) {
                return new AbgleichErgebnis.Unsicher(List.of(bester.kontaktId(), zweiter.kontaktId()), Merkmal.NAME);
            }
        }
        return new AbgleichErgebnis.Verknuepft(bester.kontaktId(), Merkmal.NAME);
    }

    /** Untergrenze für die Kandidatensuche in der Datenbank: so tief, dass auch der Zweitbeste im Abstand erscheint. */
    public static BigDecimal untergrenze(BigDecimal schwelle) {
        BigDecimal grenze = schwelle.subtract(ABSTAND);
        return grenze.signum() < 0 ? BigDecimal.ZERO : grenze;
    }
}
