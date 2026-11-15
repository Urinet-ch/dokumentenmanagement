package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Kontaktregister im Speicher für Tests der Partnerauflösung: bildet {@code mitWortanfaengen} wie die SQL-Abfrage nach. */
final class PartnerTestdaten {

    private PartnerTestdaten() {
    }

    static Kontakt kontakt(String name, boolean aktiv) {
        Instant jetzt = Instant.parse("2026-11-15T10:00:00Z");
        return new Kontakt(UUID.randomUUID(), null, KontaktTyp.ORGANISATION, name, null, null, null, null, null, null,
                null, null, null, "TEST", false, jetzt, jetzt, 1, aktiv, aktiv ? null : jetzt, null);
    }

    /** {@code aehnlich}: was {@code aehnlichste} liefert (Rückfall (c)). */
    static KontaktRegister register(List<Kontakt> kontakte, List<PartnerKandidat> aehnlich) {
        return (KontaktRegister) Proxy.newProxyInstance(PartnerTestdaten.class.getClassLoader(),
                new Class<?>[]{KontaktRegister.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "mitWortanfaengen" -> {
                        String name = (String) args[0];
                        @SuppressWarnings("unchecked") List<String> woerter = (List<String>) args[1];
                        List<Kontakt> treffer = new ArrayList<>(kontakte.stream().filter(Kontakt::aktiv)
                                .filter(k -> woerter.stream().allMatch(
                                        w -> (" " + Normalisierer.name(k.name())).contains(" " + w))).toList());
                        treffer.sort(java.util.Comparator.comparing(k -> Normalisierer.name(k.name())));
                        treffer.sort((a, b) -> Boolean.compare(name.equals(Normalisierer.name(b.name())),
                                name.equals(Normalisierer.name(a.name()))));
                        yield treffer.stream().limit((int) args[2]).toList();
                    }
                    case "aehnlichste" -> aehnlich;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    static final BigDecimal SCHWELLE = new BigDecimal("0.8");
}
