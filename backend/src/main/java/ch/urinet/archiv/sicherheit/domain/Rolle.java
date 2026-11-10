package ch.urinet.archiv.sicherheit.domain;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Archiv-Rollen des Realms {@code urinet-archiv} (Req 2.2, ADR-009). */
public enum Rolle {
    INHABER, MITARBEITER, TREUHAENDER, GESCHAEFTSSTELLE;

    /** Rangfolge nach Req 2.2 (entschieden von Fabian am 10.11.2026), stärkste zuerst. */
    private static final List<Rolle> RANGFOLGE = List.of(INHABER, GESCHAEFTSSTELLE, MITARBEITER, TREUHAENDER);

    /** Name der Realm-Rolle in Keycloak, z. B. {@code inhaber}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Die stärkste Archiv-Rolle unter den Realm-Rollen (Req 2.2: {@code inhaber} > {@code geschaeftsstelle} >
     * {@code mitarbeiter} > {@code treuhaender}); die übrigen Archiv-Rollen zählen nicht, auch nicht aus Composite- oder
     * Default-Rollen. Die Rechte werden nicht vereinigt: Wer {@code mitarbeiter} und {@code treuhaender} hat, bekommt
     * nur die Rechte von {@code mitarbeiter}. Ohne Archiv-Rolle leer; andere Realm-Rollen zählen nicht.
     */
    public static Optional<Rolle> ausRealmRollen(Collection<String> realmRollen) {
        return RANGFOLGE.stream().filter(r -> realmRollen.contains(r.code())).findFirst();
    }
}
