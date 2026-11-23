package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Realmweite, rein lesende Sicht für den Verwaltungsabgleich. Auch Benutzer mit fehlendem oder mehrwertigem
 * Mandantenattribut müssen gefunden werden; diese Sicht darf nicht an Verwaltungsendpunkte weitergegeben werden.
 */
public interface AbgleichIdentitaet {

    record RealmBenutzer(UUID id, List<String> mandanten, Set<Rolle> rollen) {
        public RealmBenutzer {
            Objects.requireNonNull(id);
            mandanten = List.copyOf(mandanten);
            rollen = Set.copyOf(rollen);
        }

        @Override
        public String toString() {
            return "RealmBenutzer[geschützt]";
        }
    }

    /** Eine Realm-Seite mit höchstens 50 Einträgen, ohne Speicherung persönlicher Angaben. */
    List<RealmBenutzer> seite(int seite);
}
