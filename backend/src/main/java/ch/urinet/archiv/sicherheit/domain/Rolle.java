package ch.urinet.archiv.sicherheit.domain;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Archiv-Rollen des Realms {@code urinet-archiv} (Req 2.2, ADR-009). */
public enum Rolle {
    INHABER, MITARBEITER, TREUHAENDER, GESCHAEFTSSTELLE;

    /** Name der Realm-Rolle in Keycloak, z. B. {@code inhaber}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Genau eine Archiv-Rolle unter den Realm-Rollen, sonst leer: ohne Archiv-Rolle oder mit mehreren (fail closed;
     * eine Vereinigung der Rechte würde etwa {@code treuhaender}, nur lesend, aufweichen). Andere Realm-Rollen zählen
     * nicht.
     */
    public static Optional<Rolle> ausRealmRollen(Collection<String> realmRollen) {
        List<Rolle> gefunden = List.of(values()).stream().filter(r -> realmRollen.contains(r.code())).toList();
        return gefunden.size() == 1 ? Optional.of(gefunden.getFirst()) : Optional.empty();
    }
}
