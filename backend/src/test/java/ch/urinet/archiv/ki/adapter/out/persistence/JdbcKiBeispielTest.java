package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.KiBeispielPort;
import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@code ki_beispiel} je Mandant (Aufgabe 3.3, Req 7.7, 7.9, 7.10): Jeder Mandant liest nur die Beispiele seines Schemas
 * (tech.md, Mandantentrennung in jedem Repository-Test); die Sicht gilt nach dem aktuellen Stand des bestätigten
 * Dokuments und filtert schon in der Abfrage, die Rangfolge nach gemeinsamen Merkmalen ebenso.
 */
@QuarkusTest
class JdbcKiBeispielTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");
    private static final BeispielSicht MITARBEITER = new BeispielSicht("joel", true, false);
    private static final BeispielSicht INHABER = new BeispielSicht("fabian", true, true);

    @Inject
    JdbcKiBeispiel beispiele;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    /** Eigene Art, weil der zweite Mandant ohne Seed keine Dokumentarten hat; nach jedem Test wieder entfernt. */
    private static final String ART = "KI_PROBE";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> dokumente = new ArrayList<>();
    private Merkmale merkmale;

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
            ausfuehren("insert into " + code.schema() + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht,"
                    + " aktiv) values ('" + ART + "', 'KI-Probe', 'NORMAL', false, true) on conflict do nothing");
        }
        merkmale = new Merkmale(Set.of(), Set.of(), Set.of("lauf-" + UUID.randomUUID() + "@beispiel.invalid"),
                Set.of());
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (MandantCode code : List.of(URINET, ZWEITER)) {
                for (String tabelle : List.of("ki_beispiel", "dokument")) {
                    String spalte = tabelle.equals("dokument") ? "id" : "dokument_id";
                    try (PreparedStatement loeschen = verbindung.prepareStatement(
                            "delete from " + code.schema() + "." + tabelle + " where " + spalte + " = any (?)")) {
                        loeschen.setArray(1, verbindung.createArrayOf("uuid", dokumente.toArray()));
                        loeschen.executeUpdate();
                    }
                }
                ausfuehren("delete from " + code.schema() + ".dokumentart where code = '" + ART + "'");
            }
        }
    }

    @Test
    void jederMandantLiestNurSeineBeispiele() {
        speichern(URINET, "Titel Urinet", "NORMAL", null);
        speichern(ZWEITER, "Titel VHF", "NORMAL", null);

        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, INHABER, 10)))).containsExactly("Titel Urinet");
        assertThat(titel(im(ZWEITER, () -> beispiele.kandidaten(merkmale, INHABER, 10)))).containsExactly("Titel VHF");
    }

    @Test
    void sichtUndMerkmaleFilternInDerAbfrage() {
        speichern(URINET, "normal", "NORMAL", null);
        speichern(URINET, "intern", "INTERN", null);
        speichern(URINET, "eigen", "PERSONAL", "joel");
        speichern(URINET, "fremd", "PERSONAL", "fabian");

        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, MITARBEITER, 10))))
                .containsExactly("eigen", "normal");
        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, INHABER, 10))))
                .containsExactly("fremd", "intern", "normal");
        Merkmale andere = new Merkmale(Set.of(), Set.of(), Set.of("andere-" + UUID.randomUUID() + "@beispiel.invalid"),
                Set.of());
        assertThat(im(URINET, () -> beispiele.kandidaten(andere, INHABER, 10))).isEmpty();
    }

    @Test
    void kandidatenUeberIbanUidUndMuster() {
        String lauf = UUID.randomUUID().toString();
        Merkmale iban = new Merkmale(Set.of("CH00" + lauf), Set.of(), Set.of(), Set.of());
        Merkmale uid = new Merkmale(Set.of(), Set.of("UID" + lauf), Set.of(), Set.of());
        Merkmale muster = new Merkmale(Set.of(), Set.of(), Set.of(), Set.of("^M" + lauf + "$"));
        speichern(URINET, "iban", "NORMAL", null, iban, true);
        speichern(URINET, "uid", "NORMAL", null, uid, true);
        speichern(URINET, "muster", "NORMAL", null, muster, true);

        for (Merkmale m : List.of(iban, uid, muster)) {
            assertThat(titel(im(URINET, () -> beispiele.kandidaten(m, MITARBEITER, 10))))
                    .containsExactly(titel(m, iban, uid));
        }
    }

    /** Ohne Rang in SQL schnitte das Limit das ältere Beispiel mit zwei gemeinsamen Merkmalen ab (Req 7.10). */
    @Test
    void mehrGemeinsameMerkmaleSchlagenNeuereBeispieleTrotzLimit() throws SQLException {
        String lauf = UUID.randomUUID().toString();
        Merkmale beleg = new Merkmale(Set.of("CH00" + lauf), Set.of(), Set.of(), Set.of("^M" + lauf + "$"));
        speichern(URINET, "aelter mit IBAN", "NORMAL", null, beleg, true);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement masse = verbindung.prepareStatement("with d as (insert into " + URINET.schema()
                     + ".dokument (id, art, titel, quelle, vertraulichkeit, erstellt_von, status, bestaetigt, "
                     + "bestaetigt_von) select gen_random_uuid(), '" + ART + "', 'masse', 'UPLOAD', 'NORMAL', "
                     + "'probe', 'ARCHIVIERT', now(), 'probe' from generate_series(1, 210) returning id) insert into "
                     + URINET.schema() + ".ki_beispiel (art, korrektur, muster, dokument_id, zeit) select "
                     + "'KORRESPONDENZ', '{\"titel\": \"neuer\"}', array[?], id, clock_timestamp() + interval '1 hour' "
                     + "from d returning dokument_id")) {
            masse.setString(1, "^M" + lauf + "$");
            try (ResultSet ids = masse.executeQuery()) {
                while (ids.next()) {
                    dokumente.add(ids.getObject(1, UUID.class));
                }
            }
        }

        List<Beispiel> kandidaten = im(URINET, () -> beispiele.kandidaten(beleg, MITARBEITER, 200));

        assertThat(kandidaten).hasSize(200);
        assertThat(titel(kandidaten).getFirst()).isEqualTo("aelter mit IBAN");
    }

    @Test
    void sichtFolgtDemAktuellenStandDesDokuments() throws SQLException {
        UUID dokument = speichern(URINET, "spaeter intern", "NORMAL", null, merkmale, true);
        speichern(URINET, "unbestaetigt", "NORMAL", null, merkmale, false);

        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, MITARBEITER, 10))))
                .containsExactly("spaeter intern");
        ausfuehren("update " + URINET.schema() + ".dokument set vertraulichkeit = 'INTERN' where id = '" + dokument
                + "'");

        assertThat(im(URINET, () -> beispiele.kandidaten(merkmale, MITARBEITER, 10))).isEmpty();
        assertThat(titel(im(URINET, () -> beispiele.neueste(MITARBEITER, 500)))).doesNotContain("spaeter intern")
                .doesNotContain("unbestaetigt");
        assertThat(im(URINET, () -> beispiele.kandidaten(merkmale, INHABER, 10)))
                .extracting(Beispiel::vertraulichkeit).containsExactly("INTERN");
    }

    private static String titel(Merkmale m, Merkmale iban, Merkmale uid) {
        return m == iban ? "iban" : m == uid ? "uid" : "muster";
    }

    private void speichern(MandantCode mandant, String titel, String vertraulichkeit, String person) {
        speichern(mandant, titel, vertraulichkeit, person, merkmale, true);
    }

    /** Ein Dokument mit der Vertraulichkeit und dazu das Beispiel; nur bestätigte Dokumente zählen. */
    private UUID speichern(MandantCode mandant, String titel, String vertraulichkeit, String person, Merkmale m,
                           boolean bestaetigt) {
        UUID dokument = UUID.randomUUID();
        dokumente.add(dokument);
        try {
            ausfuehren("insert into " + mandant.schema() + ".dokument (id, art, titel, quelle, vertraulichkeit, "
                    + "person_benutzer, erstellt_von, status, bestaetigt, bestaetigt_von) values ('" + dokument
                    + "', '" + ART + "', 'Probe', 'UPLOAD', '" + vertraulichkeit + "', "
                    + (person == null ? "null" : "'" + person + "'") + ", 'probe', "
                    + (bestaetigt ? "'ARCHIVIERT', now(), 'probe')" : "'EINGANGSKORB', null, null)"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        im(mandant, () -> {
            beispiele.speichern(new KiBeispielPort.Neu(dokument, "KORRESPONDENZ", Map.of(Feld.TITEL, titel), m,
                    Optional.empty()));
            return null;
        });
        return dokument;
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.executeUpdate();
        }
    }

    private static List<String> titel(List<Beispiel> liste) {
        return liste.stream().map(b -> b.korrektur().get(Feld.TITEL)).toList();
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

    private boolean vorhanden(MandantCode code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
