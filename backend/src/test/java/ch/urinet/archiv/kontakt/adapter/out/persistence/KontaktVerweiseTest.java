package ch.urinet.archiv.kontakt.adapter.out.persistence;

import ch.urinet.archiv.kontakt.application.KontaktVerweise;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verweise auf Kontakte (Spec 01, design.md «Kontakte ansehen und pflegen», Schritt 4; Req 17.7) gegen PostgreSQL:
 * Jede Spalte des Mandanten-Schemas vom Typ {@code uuid}, die nach ihrem Namen auf einen Kontakt zeigen kann
 * ({@code partner_id}, {@code kontakt_id}, Endung {@code _partner_id} bzw. {@code _kontakt}), hängt das Zusammenführen um
 * oder nimmt es ausdrücklich aus; eine neue Spalte (z. B. aus Spec 02) fällt so auf. Dazu die Mandantentrennung des
 * Adapters mit zwei Mandanten (tech.md).
 */
@QuarkusTest
class KontaktVerweiseTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");

    @Inject
    JdbcKontaktVerweise verweise;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> kontakte = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (MandantCode code : List.of(URINET, ZWEITER)) {
                for (String sql : List.of("delete from " + code.schema() + ".verkaufschance where kontakt_id = any(?)",
                        "delete from " + code.schema() + ".kontakt where id = any(?)")) {
                    try (PreparedStatement loeschen = verbindung.prepareStatement(sql)) {
                        loeschen.setArray(1, verbindung.createArrayOf("uuid", kontakte.toArray()));
                        loeschen.executeUpdate();
                    }
                }
            }
        }
    }

    @Test
    void jedeSpalteMitVerweisAufEinenKontaktIstUmgehaengtOderAusgenommen() throws SQLException {
        mandantenAnlegen();
        Set<String> spalten = new HashSet<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select table_name, column_name from information_schema.columns "
                        + "where table_schema = ? and data_type = 'uuid' and (column_name in ('partner_id', "
                        + "'kontakt_id') or column_name like '%\\_partner\\_id' or column_name like '%\\_kontakt')")) {
            abfrage.setString(1, URINET.schema());
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    spalten.add(zeile.getString(1) + "." + zeile.getString(2));
                }
            }
        }
        Set<String> bekannt = new HashSet<>(KontaktVerweise.UMGEHAENGT);
        bekannt.addAll(KontaktVerweise.AUSGENOMMEN);

        assertThat(spalten).as("Spalten mit Verweis auf einen Kontakt (in KontaktVerweise aufnehmen)")
                .isNotEmpty().isSubsetOf(bekannt);
        assertThat(spalten).as("die umgehängten Spalten gibt es").containsAll(KontaktVerweise.UMGEHAENGT);
    }

    @Test
    void umhaengenUndZaehlenNurImEigenenMandanten() throws SQLException {
        mandantenAnlegen();
        UUID quelle = kontakt(URINET);
        UUID ziel = kontakt(URINET);
        UUID fremd = kontakt(ZWEITER);
        chance(URINET, quelle);
        chance(URINET, quelle);
        chance(ZWEITER, fremd);

        // derselbe Verweis im anderen Mandanten bleibt (die ID gibt es dort nicht, die Anweisung ändert nichts)
        assertThat(im(ZWEITER, () -> verweise.umhaengen(quelle, ziel)).verkaufschancen()).isZero();
        assertThat(im(URINET, () -> verweise.zaehlen(quelle)).verkaufschancen()).isEqualTo(2);
        assertThat(im(URINET, () -> verweise.umhaengen(fremd, ziel)).verkaufschancen()).isZero();

        KontaktVerweise.Zahlen umgehaengt = im(URINET, () -> verweise.umhaengen(quelle, ziel));

        assertThat(umgehaengt).isEqualTo(new KontaktVerweise.Zahlen(0, 2, 0));
        assertThat(im(URINET, () -> verweise.zaehlen(ziel))).isEqualTo(new KontaktVerweise.Zahlen(0, 2, 0));
        assertThat(im(URINET, () -> verweise.zaehlen(quelle))).isEqualTo(new KontaktVerweise.Zahlen(0, 0, 0));
        assertThat(im(ZWEITER, () -> verweise.zaehlen(fremd))).isEqualTo(new KontaktVerweise.Zahlen(0, 1, 0));
    }

    private UUID kontakt(MandantCode mandant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + mandant.schema() + ".kontakt (id, typ, name, "
                        + "name_normalisiert, quelle) values (?, 'ORGANISATION', 'Verweis Probe', 'verweis probe', "
                        + "'TEST')")) {
            einfuegen.setObject(1, id);
            einfuegen.executeUpdate();
        }
        kontakte.add(id);
        return id;
    }

    private void chance(MandantCode mandant, UUID kontakt) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + mandant.schema() + ".verkaufschance (id, kontakt_id, "
                        + "titel) values (?, ?, 'Probe')")) {
            einfuegen.setObject(1, UUID.randomUUID());
            einfuegen.setObject(2, kontakt);
            einfuegen.executeUpdate();
        }
    }

    private void mandantenAnlegen() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                    verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, code.wert());
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code.wert(), null, null);
                    }
                }
            }
        }
    }

    private <T> T im(MandantCode code, Supplier<T> arbeit) {
        try {
            return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> QuarkusTransaction.requiringNew()
                    .call(arbeit::get)), worker).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException laufzeit ? laufzeit : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
