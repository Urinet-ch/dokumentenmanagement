package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.AbgleichRegel;
import ch.urinet.archiv.kontakt.domain.Kontakt;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Auflösung eines Partnernamens aus dem Chat zu einem aktiven Kontakt (Spec 02, Req 4; Chat-Nutzer nennen meist nur einen
 * Teil des Namens): (a) exakter normalisierter Name, (b) alle Wörter der Eingabe als Wortanfänge im normalisierten Namen,
 * (c) sonst der Ähnlichkeitsabgleich des Imports ({@link AbgleichRegel}). Nie eine Vermutung ausser einem Ähnlichkeitstreffer über der Schwelle (Stufe c, wie im Import): genau ein Kandidat
 * verknüpft, mehrere sind {@link NichtEindeutig}, keiner {@link NichtGefunden}. Nur aktive Kontakte (Spec 01, Req 17.12)
 * und nur lesend; in einer Transaktion aufrufen (Namensähnlichkeit).
 */
final class PartnerAufloesung {

    /** Höchstens so viele Namen nennt {@link NichtEindeutig}. */
    static final int KANDIDATEN_MAX = 5;
    static final int NAME_MAX = 80;

    sealed interface Ergebnis permits Gefunden, NichtEindeutig, NichtGefunden {
    }

    record Gefunden(UUID kontaktId) implements Ergebnis {
    }

    /** {@code namen}: höchstens {@link #KANDIDATEN_MAX}, nur Namen; leer, wenn der Aufrufer sie nicht zeigen darf. */
    record NichtEindeutig(List<String> namen) implements Ergebnis {
    }

    record NichtGefunden() implements Ergebnis {
    }

    private final KontaktRegister kontakte;
    private final BigDecimal schwelle;

    PartnerAufloesung(KontaktRegister kontakte, BigDecimal schwelle) {
        this.kontakte = kontakte;
        this.schwelle = schwelle;
    }

    /** @param namenNennen ob bei mehreren Treffern die Kontaktnamen genannt werden dürfen (Recht A6, Kontakte ansehen) */
    Ergebnis aufloesen(String partner, boolean namenNennen) {
        String name = Normalisierer.name(partner);
        if (name.isEmpty()) {
            return new NichtGefunden();
        }
        List<String> woerter = Arrays.asList(name.split(" "));
        List<Kontakt> treffer = kontakte.mitWortanfaengen(name, woerter, KANDIDATEN_MAX + 1);
        List<Kontakt> exakt = treffer.stream().filter(k -> name.equals(Normalisierer.name(k.name()))
                || name.equals(Normalisierer.personName(k.vorname(), k.name()))).toList();
        if (exakt.size() == 1) {
            return new Gefunden(exakt.get(0).id());
        }
        if (treffer.size() == 1) {
            return new Gefunden(treffer.get(0).id());
        }
        if (!treffer.isEmpty()) {
            return nichtEindeutig(treffer, namenNennen);
        }
        return switch (AbgleichRegel.aehnlich(kontakte.aehnlichste(name, AbgleichRegel.untergrenze(schwelle), 2),
                schwelle)) {
            case AbgleichErgebnis.Verknuepft v -> new Gefunden(v.kontaktId());
            case AbgleichErgebnis.Unsicher u -> new NichtEindeutig(namenNennen
                    ? kontakte.finden(u.kandidaten().stream().limit(KANDIDATEN_MAX).toList()).stream().map(PartnerAufloesung::anzeigeName).toList()
                    : List.of());
            case AbgleichErgebnis.NeuerPartner n -> new NichtGefunden();
        };
    }

    private static NichtEindeutig nichtEindeutig(List<Kontakt> treffer, boolean namenNennen) {
        if (!namenNennen) {
            return new NichtEindeutig(List.of());
        }
        return new NichtEindeutig(treffer.stream().limit(KANDIDATEN_MAX).map(PartnerAufloesung::anzeigeName).toList());
    }

    /** Kontaktnamen sind fremde Daten (Import): ohne Steuerzeichen und auf {@link #NAME_MAX} Zeichen gekürzt. */
    private static String anzeigeName(Kontakt k) {
        String name = k.vorname() == null || k.vorname().isBlank() ? k.name() : k.vorname() + " " + k.name();
        name = name.replaceAll("\\p{Cntrl}", " ").strip();
        return name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }
}
