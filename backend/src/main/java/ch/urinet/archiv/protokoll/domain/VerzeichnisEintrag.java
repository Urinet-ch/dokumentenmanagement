package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Eine Zeile des Benutzerverzeichnisses (Req 10.10): ordnet die Kennung {@code benutzer} des Zugriffsprotokolls
 * (Keycloak-{@code sub} oder {@code token:<id>}) Name und Rolle zu. Gültig ist eine Zeile ab {@code gueltigVon} bis zu
 * {@code gueltigBis} oder, ohne Ende, bis zur nächsten Zeile derselben Kennung. Nur Einfügen: eine Änderung ist eine
 * neue Zeile.
 */
public record VerzeichnisEintrag(long id, Instant zeit, String benutzer, Optional<String> name, Optional<String> rolle,
                                 Instant gueltigVon, Optional<Instant> gueltigBis, Anlass anlass,
                                 Optional<String> erfasstVon) {

    /** Rolle der Zeilen von API-Tokens; die übrigen Werte sind Namen der Archiv-Rollen. */
    public static final String ROLLE_API_TOKEN = "API_TOKEN";
    public static final String TOKEN_PRAEFIX = "token:";

    /** Warum die Zeile entstand; gespeichert als Text wie der Check {@code benutzer_verzeichnis_anlass_ck}. */
    public enum Anlass {
        /** erste Aktion der Kennung, geänderter Name oder Rolle oder wieder aktiv nach einem Austritt */
        AKTION,
        /** Austritt einer Person, erfasst vom Inhaber */
        AUSTRITT,
        /** Widerruf eines API-Tokens (A14) */
        WIDERRUF
    }

    public VerzeichnisEintrag {
        Objects.requireNonNull(zeit, "zeit");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(gueltigVon, "gueltigVon");
        Objects.requireNonNull(gueltigBis, "gueltigBis");
        Objects.requireNonNull(anlass, "anlass");
        Objects.requireNonNull(erfasstVon, "erfasstVon");
    }

    /** Die Zeile hat ein Ende (Austritt oder Widerruf). */
    public boolean beendet() {
        return gueltigBis.isPresent();
    }

    public static boolean istToken(String benutzer) {
        return benutzer.startsWith(TOKEN_PRAEFIX);
    }
}
