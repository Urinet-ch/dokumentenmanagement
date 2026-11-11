package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.AbgleichRegel;
import ch.urinet.archiv.kontakt.domain.Merkmal;
import ch.urinet.archiv.kontakt.domain.PartnerAngaben;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Gleicht einen vorgeschlagenen Partner mit den Kontakten des Mandanten ab (Req 7.4, design.md «KI»): E-Mail → UID →
 * IBAN → Namensähnlichkeit ab {@code archiv.abgleich.aehnlichkeit} ({@code pg_trgm}); entschieden wird in
 * {@link AbgleichRegel}. Für den Worker (Aufgabe 3.2) im Mandanten-Kontext des Auftrags. Das Log nennt nur IDs.
 */
@ApplicationScoped
public class PartnerAbgleich {

    private static final Logger LOG = Logger.getLogger(PartnerAbgleich.class);

    private final KontaktRegister register;
    private final BigDecimal schwelle;

    public PartnerAbgleich(KontaktRegister register,
                           @ConfigProperty(name = "archiv.abgleich.aehnlichkeit") BigDecimal schwelle) {
        if (schwelle.signum() <= 0 || schwelle.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("archiv.abgleich.aehnlichkeit muss zwischen 0 und 1 liegen");
        }
        this.register = register;
        this.schwelle = schwelle;
    }

    @Transactional
    public AbgleichErgebnis abgleichen(PartnerAngaben angaben) {
        Map<Merkmal, Set<UUID>> treffer = new EnumMap<>(Merkmal.class);
        stufe(treffer, Merkmal.EMAIL, angaben.emailNormalisiert(), register::mitEmail);
        stufe(treffer, Merkmal.UID, angaben.uidNormalisiert(), register::mitUid);
        stufe(treffer, Merkmal.IBAN, angaben.ibanNormalisiert(), register::mitIban);
        AbgleichErgebnis ergebnis = AbgleichRegel.exakt(treffer).orElseGet(() -> {
            String name = angaben.nameNormalisiert();
            return name.isEmpty() ? new AbgleichErgebnis.NeuerPartner()
                    : AbgleichRegel.aehnlich(register.aehnlichste(name, AbgleichRegel.untergrenze(schwelle), 2), schwelle);
        });
        protokollieren(ergebnis);
        return ergebnis;
    }

    private static void stufe(Map<Merkmal, Set<UUID>> treffer, Merkmal merkmal, String wert,
                              Function<String, Set<UUID>> suche) {
        if (!wert.isEmpty()) {
            treffer.put(merkmal, suche.apply(wert));
        }
    }

    private static void protokollieren(AbgleichErgebnis ergebnis) {
        switch (ergebnis) {
            case AbgleichErgebnis.Verknuepft v -> LOG.debugf("Partner-Abgleich: Kontakt %s über %s", v.kontaktId(),
                    v.merkmal());
            case AbgleichErgebnis.Unsicher u -> LOG.debugf("Partner-Abgleich: unsicher über %s, Kandidaten %s",
                    u.merkmal(), u.kandidaten());
            case AbgleichErgebnis.NeuerPartner n -> LOG.debug("Partner-Abgleich: neuer Partner");
        }
    }
}
