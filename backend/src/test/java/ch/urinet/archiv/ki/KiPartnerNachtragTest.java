package ch.urinet.archiv.ki;

import ch.urinet.archiv.ki.application.KorrekturLernen;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset {@code 022-dokument-ki-partner-nachtrag} auf Bestandsdaten (Nachprüfung PR #109, Req 7.7): Nur
 * unbestätigte Dokumente mit KI-Vorschlag und Partner, die vor {@code 016-dokument-ki-partner} eingegangen sind und noch
 * kein {@code ki_partner_id} haben, bekommen den Partner als Stand des Abgleichs; danach gilt dieser Partner beim
 * Bestätigen als übernommen. Die Ausführungszeit von 016 setzt der Test auf einen festen Zeitpunkt, die Dokumente liegen
 * eine Minute davor oder danach; eine falsch gerechnete Zeitzone (in Europe/Zurich mindestens eine Stunde) fiele auf.
 */
@QuarkusTest
class KiPartnerNachtragTest {

    private static final MandantCode MANDANT = new MandantCode("ki-nachtrag");
    /** Einzige Art im Test-Seed {@code seed/ki-nachtrag}. */
    private static final String ART = "KI_PROBE";
    private static final Instant AUSFUEHRUNG_016 = Instant.parse("2026-06-15T10:00:00Z");
    private static final String VORSCHLAG = "{\"art\": \"" + ART + "\", \"titel\": \"Probe\", \"partnerName\": "
            + "\"Muster AG\", \"vertraulichkeit\": \"NORMAL\"}";

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantDatenbank mandantDatenbank;

    @Inject
    MandantKontext kontext;

    @Inject
    KorrekturLernen lernen;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> dokumente = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String sql : List.of("delete from " + MANDANT.schema() + ".ki_beispiel where dokument_id = any (?)",
                    "delete from " + MANDANT.schema() + ".dokument where id = any (?)")) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(sql)) {
                    loeschen.setArray(1, verbindung.createArrayOf("uuid", dokumente.toArray()));
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void bestandVor016BekommtKiPartnerUndGiltBeimBestaetigenAlsUebernommen() throws Exception {
        Instant vor = Instant.now();
        boolean neu = !vorhanden();
        if (neu) {
            provisionierung.anlegen(MANDANT.wert(), null, null);
        }
        Instant nach = Instant.now();
        if (neu) {
            // Liquibase schreibt dateexecuted mit now() in der Zeitzone der Sitzung; 022 rechnet in derselben zurück
            OffsetDateTime ausgefuehrt;
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement("select min(dateexecuted) at time zone "
                         + "current_setting('TimeZone') from " + MANDANT.schema() + ".databasechangelog where id = "
                         + "'016-dokument-ki-partner'");
                 ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                ausgefuehrt = zeile.getObject(1, OffsetDateTime.class);
            }
            assertThat(ausgefuehrt.toInstant()).isBetween(vor.minus(Duration.ofMinutes(1)),
                    nach.plus(Duration.ofMinutes(1)));
        }
        UUID partner = UUID.randomUUID();
        UUID andererPartner = UUID.randomUUID();
        Map<String, UUID> fall = new LinkedHashMap<>();
        fall.put("a", einfuegen(-1, true, false, partner, null));
        fall.put("b", einfuegen(1, true, false, partner, null));
        fall.put("c", einfuegen(-1, true, true, partner, null));
        fall.put("d", einfuegen(-1, false, false, partner, null));
        fall.put("e", einfuegen(-1, true, false, partner, andererPartner));
        fall.put("f", einfuegen(-1, true, false, null, null));

        // Bestand wie in prod: 016 lief zu einem festen Zeitpunkt, 022 noch nicht
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement setzen = verbindung.prepareStatement("update " + MANDANT.schema()
                     + ".databasechangelog set dateexecuted = ? where id = '016-dokument-ki-partner'")) {
            setzen.setObject(1, LocalDateTime.ofInstant(AUSFUEHRUNG_016, ZoneId.systemDefault()));
            assertThat(setzen.executeUpdate()).isEqualTo(1);
        }
        assertThat(ausfuehren("delete from " + MANDANT.schema() + ".databasechangelog where id = "
                + "'022-dokument-ki-partner-nachtrag'")).isEqualTo(1);

        mandantDatenbank.schemaMigrieren(MANDANT.schema());

        assertThat(wert("select count(*) from " + MANDANT.schema() + ".databasechangelog where id = "
                + "'022-dokument-ki-partner-nachtrag'")).isEqualTo("1");
        assertThat(kiPartner(fall.get("a"))).isEqualTo(partner.toString());
        assertThat(kiPartner(fall.get("b"))).isNull();
        assertThat(kiPartner(fall.get("c"))).isNull();
        assertThat(kiPartner(fall.get("d"))).isNull();
        assertThat(kiPartner(fall.get("e"))).isEqualTo(andererPartner.toString());
        assertThat(kiPartner(fall.get("f"))).isNull();

        // Req 7.7: derselbe Kontakt mit seinem eigenen Namen gilt in (a) als übernommen, in (b) als Korrektur
        bestaetigen(fall.get("a"), partner);
        bestaetigen(fall.get("b"), partner);
        assertThat(wert("select count(*) from " + MANDANT.schema() + ".ki_beispiel where dokument_id = '"
                + fall.get("a") + "'")).isEqualTo("0");
        assertThat(wert("select korrektur::text from " + MANDANT.schema() + ".ki_beispiel where dokument_id = '"
                + fall.get("b") + "'")).isEqualTo("{\"partnerName\": \"Kontakt Muster\"}");
    }

    private void bestaetigen(UUID dokument, UUID partner) throws Exception {
        KorrekturLernen.Ergebnis ergebnis = new KorrekturLernen.Ergebnis(ART, "Probe", null, partner,
                "Kontakt Muster", false, null, null, null, null, null, null, "NORMAL", List.of());
        CompletableFuture.runAsync(() -> kontext.mit(MANDANT, () -> QuarkusTransaction.requiringNew().run(() ->
                lernen.lernen(MANDANT, dokument, lernen.vorher(dokument).orElseThrow(), ergebnis))), worker)
                .get(1, TimeUnit.MINUTES);
    }

    /** Dokument {@code minuten} Minuten vor (negativ) oder nach der Ausführung von 016 eingegangen. */
    private UUID einfuegen(int minuten, boolean vorschlag, boolean bestaetigt, UUID partner, UUID kiPartner)
            throws SQLException {
        UUID id = UUID.randomUUID();
        dokumente.add(id);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".dokument (id, art, titel, quelle, vertraulichkeit, erstellt_von, status, bestaetigt, "
                     + "bestaetigt_von, eingang, ki_vorschlag, partner_id, ki_partner_id) values (?, ?, 'Probe', "
                     + "'UPLOAD', 'NORMAL', 'probe', ?, ?, ?, ?, cast(? as jsonb), ?, ?)")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, ART);
            einfuegen.setString(3, bestaetigt ? "ARCHIVIERT" : "EINGANGSKORB");
            einfuegen.setObject(4, bestaetigt ? OffsetDateTime.now(ZoneOffset.UTC) : null);
            einfuegen.setString(5, bestaetigt ? "probe" : null);
            einfuegen.setObject(6, AUSFUEHRUNG_016.plus(Duration.ofMinutes(minuten)).atOffset(ZoneOffset.UTC));
            einfuegen.setString(7, vorschlag ? VORSCHLAG : null);
            einfuegen.setObject(8, partner);
            einfuegen.setObject(9, kiPartner);
            einfuegen.executeUpdate();
        }
        return id;
    }

    private String kiPartner(UUID dokument) throws SQLException {
        return wert("select ki_partner_id::text from " + MANDANT.schema() + ".dokument where id = '" + dokument + "'");
    }

    private boolean vorhanden() throws SQLException {
        return wert("select count(*) from public.mandant where code = '" + MANDANT.wert() + "'").equals("1");
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql);
             ResultSet zeile = abfrage.executeQuery()) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }

    private int ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            return anweisung.executeUpdate();
        }
    }
}
