package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Liest Geschäftsjahr und Kennungen aus {@code public.mandant}; die selbst ausgestellten Arten kommen aus dem
 * Mandanten-Schema.
 */
@ApplicationScoped
public class JdbcMandantEinstellungen implements MandantEinstellungen {

    /** {@code kennungen} ist {@code {uid, ibans[]}} (design.md «Datenmodell»); andere Formen gelten als leer. */
    static final String KENNUNGEN = """
            select geschaeftsjahr_ende, name,
                case when jsonb_typeof(kennungen -> 'uid') = 'string' then kennungen ->> 'uid' end,
                case when jsonb_typeof(kennungen -> 'ibans') = 'array'
                    then array(select jsonb_array_elements_text(kennungen -> 'ibans')) end
            from public.mandant where code = ?""";

    private final Instance<AgroalDataSource> datenquelle;
    private final DokumentartRepository dokumentarten;

    public JdbcMandantEinstellungen(Instance<AgroalDataSource> datenquelle, DokumentartRepository dokumentarten) {
        this.datenquelle = datenquelle;
        this.dokumentarten = dokumentarten;
    }

    @Override
    public Werte lesen(MandantCode mandant) {
        Set<String> eigeneArten = dokumentarten.alle().stream().filter(art -> art.selbstAusgestellt())
                .map(art -> art.code()).collect(Collectors.toUnmodifiableSet());
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     KENNUNGEN)) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return new Werte(Geschaeftsjahresende.STANDARD, eigeneArten);
                }
                Array ibans = zeile.getArray(4);
                List<String> eigeneIbans = ibans == null ? List.of()
                        : Arrays.stream((String[]) ibans.getArray()).filter(Objects::nonNull).toList();
                return new Werte(new Geschaeftsjahresende(zeile.getString(1)), eigeneArten, zeile.getString(2),
                        zeile.getString(3), eigeneIbans);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenregister nicht lesbar", e);
        }
    }
}
