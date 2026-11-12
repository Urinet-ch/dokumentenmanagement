package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.Config;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Liest die Einstellungen aus {@code public.mandant} per JDBC (wie {@link JdbcMandantVerzeichnis}): {@code eigene_arten}
 * bildet {@code MandantEintrag} nicht ab, und die Tabelle ist immer qualifiziert, weil eine Verbindung aus dem Pool
 * noch auf einem Mandanten-Schema stehen kann. Ist {@code eigene_arten} nicht gesetzt (die Pflege über
 * {@code PATCH /mandant/einstellungen} folgt später), gilt {@code archiv.mandant.<code>.eigene-arten}.
 */
@ApplicationScoped
public class JdbcMandantEinstellungen implements MandantEinstellungen {

    private final Instance<AgroalDataSource> datenquelle;
    private final Config konfiguration;

    public JdbcMandantEinstellungen(Instance<AgroalDataSource> datenquelle, Config konfiguration) {
        this.datenquelle = datenquelle;
        this.konfiguration = konfiguration;
    }

    @Override
    public Werte lesen(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select geschaeftsjahr_ende, eigene_arten from public.mandant where code = ?")) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return new Werte(Geschaeftsjahresende.STANDARD, ausKonfiguration(mandant));
                }
                Array arten = zeile.getArray(2);
                Set<String> eigene = arten == null ? ausKonfiguration(mandant)
                        : Arrays.stream((String[]) arten.getArray()).filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                return new Werte(new Geschaeftsjahresende(zeile.getString(1)), eigene);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenregister nicht lesbar", e);
        }
    }

    private Set<String> ausKonfiguration(MandantCode mandant) {
        return konfiguration.getOptionalValues("archiv.mandant." + mandant.wert() + ".eigene-arten", String.class)
                .map(Set::copyOf).orElse(Set.of());
    }
}
