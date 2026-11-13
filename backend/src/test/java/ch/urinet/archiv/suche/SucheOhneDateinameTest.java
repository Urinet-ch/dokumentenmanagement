package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Variante der Suche ohne Dateinamen (Req 3.13, design.md «Dateiname»): Werkzeuge anderer Sprachmodelle und
 * {@code /public/v1} erhalten weder das Feld {@code dateiname} noch den Namen im Auszug; die PWA-Variante schon
 * (Req 14.8, 14.9). Synthetische Daten.
 */
@QuarkusTest
class SucheOhneDateinameTest {

    private static final String MANDANT = "urinet";
    private static final String NAME = "Zebrafink_Abschluss-2026.pdf";

    @Inject
    SuchePort suche;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final String lauf = "lauf" + Long.toHexString(new Random().nextLong());
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void dokumentAnlegen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, MANDANT);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(MANDANT, null, null);
                    }
                }
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into "
                    + new MandantCode(MANDANT).schema() + ".dokument (id, titel, quelle, tags, vertraulichkeit, "
                    + "erstellt_von, status, dateiname, volltext) values (?, null, 'UPLOAD', ?, 'NORMAL', 'probe', "
                    + "'EINGANGSKORB', ?, 'Synthetischer Text ohne den Suchbegriff')")) {
                einfuegen.setObject(1, id);
                einfuegen.setArray(2, verbindung.createArrayOf("text", new Object[] {lauf}));
                einfuegen.setString(3, NAME);
                einfuegen.executeUpdate();
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), MANDANT, List.of(id));
    }

    @Test
    void ohneDateinameFehltErInTrefferUndAuszug() throws Exception {
        Suchtreffer treffer = einzigerTreffer(false);
        assertThat(treffer.dateiname()).isNull();
        assertThat(treffer.auszug()).extracting(AuszugTeil::text)
                .noneMatch(t -> t.contains("Zebrafink") || t.contains("Abschluss"));
    }

    @Test
    void mitDateinameLiefertDiePwaVarianteNameUndHervorhebung() throws Exception {
        Suchtreffer treffer = einzigerTreffer(true);
        assertThat(treffer.dateiname()).isEqualTo(NAME);
        assertThat(treffer.auszug()).contains(new AuszugTeil("Zebrafink", true));
    }

    /** Eigener Thread ohne Anfrage-Kontext, wie im {@link SucheLeistungTest}. */
    private Suchtreffer einzigerTreffer(boolean mitDateiname) throws Exception {
        Suchanfrage anfrage = new Suchanfrage("Zebrafink", null, null, null, null, null, null, null, List.of(lauf),
                null, null, null, null, 0, Suchanfrage.GROESSE_STANDARD);
        Sichtregel regel = Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        List<Suchtreffer> treffer;
        try {
            treffer = worker.submit(() -> kontext.mit(new MandantCode(MANDANT),
                    () -> QuarkusTransaction.requiringNew().call(() -> suche.suchen(anfrage, regel, mitDateiname))))
                    .get(30, TimeUnit.SECONDS).treffer();
        } finally {
            worker.shutdown();
        }
        assertThat(treffer).extracting(Suchtreffer::id).containsExactly(id);
        return treffer.getFirst();
    }
}
