package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsabweichung;
import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Vergleich ohne Keycloak-Schreibzugriff und ohne persönliche Angaben. */
public final class AbgleichPruefung {

    public record Abweichung(Verwaltungsabweichung code, UUID id) {
        public Abweichung {
            Objects.requireNonNull(code);
            Objects.requireNonNull(id);
        }
    }

    private AbgleichPruefung() {
    }

    /**
     * Fehlerhafte Mandantenzuordnungen werden nur dem Betriebsmandanten zugeordnet, nicht einem möglicherweise
     * fremden Mandanten. {@code verwaltet} enthält nur noch nicht regulär gelöschte Benutzer der Verwaltung.
     */
    public static List<Abweichung> vergleichen(MandantCode mandant, boolean betrieb,
            Collection<RealmBenutzer> realm, Set<Rolle> erlaubteRollen, Set<UUID> verwaltet,
            Set<UUID> dokumentierteInhaber, Set<UUID> offeneVorgaenge) {
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(realm);
        Objects.requireNonNull(erlaubteRollen);
        Objects.requireNonNull(verwaltet);
        Objects.requireNonNull(dokumentierteInhaber);
        Objects.requireNonNull(offeneVorgaenge);
        List<Abweichung> befunde = new ArrayList<>();
        Set<UUID> zugeordnet = new HashSet<>();
        Set<UUID> gesehen = new HashSet<>();
        for (RealmBenutzer benutzer : realm) {
            if (!gesehen.add(benutzer.id())) {
                throw new IllegalStateException("Realm-Abgleich enthält doppelte Benutzerkennungen");
            }
            if (benutzer.mandanten().isEmpty()
                    || benutzer.mandanten().size() == 1 && benutzer.mandanten().getFirst().isBlank()) {
                if (betrieb && !benutzer.rollen().isEmpty()) {
                    befunde.add(new Abweichung(Verwaltungsabweichung.KEIN_MANDANT, benutzer.id()));
                    if (benutzer.rollen().size() > 1) {
                        befunde.add(new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, benutzer.id()));
                    }
                }
                continue;
            }
            if (benutzer.mandanten().size() != 1) {
                if (betrieb && !benutzer.rollen().isEmpty()) {
                    befunde.add(new Abweichung(Verwaltungsabweichung.MEHRERE_MANDANTEN, benutzer.id()));
                    if (benutzer.rollen().size() > 1) {
                        befunde.add(new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, benutzer.id()));
                    }
                }
                continue;
            }
            if (!benutzer.mandanten().getFirst().equals(mandant.wert())) {
                continue;
            }
            zugeordnet.add(benutzer.id());
            if (benutzer.rollen().size() > 1) {
                befunde.add(new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, benutzer.id()));
            }
            if (!erlaubteRollen.containsAll(benutzer.rollen())) {
                befunde.add(new Abweichung(Verwaltungsabweichung.FREMDE_ROLLE, benutzer.id()));
            }
            if (benutzer.rollen().contains(Rolle.INHABER) && !dokumentierteInhaber.contains(benutzer.id())) {
                befunde.add(new Abweichung(Verwaltungsabweichung.ROLLE_OHNE_VORGANG, benutzer.id()));
            }
        }
        verwaltet.stream().filter(id -> !zugeordnet.contains(id))
                .forEach(id -> befunde.add(new Abweichung(Verwaltungsabweichung.BENUTZER_FEHLT, id)));
        offeneVorgaenge.forEach(id -> befunde.add(new Abweichung(Verwaltungsabweichung.VORGANG_OFFEN, id)));
        return List.copyOf(befunde);
    }
}
