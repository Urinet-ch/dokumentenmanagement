package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.AenderungArt;
import ch.urinet.archiv.integration.domain.webseite.FeedSeite;
import ch.urinet.archiv.integration.domain.webseite.LeseAenderung;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 03, Aufgabe 11.9 (Req 14.1, 14.3, 14.4) gegen die Datenbank: Der Trigger auf {@code dokument} und der feste
 * Filter der Leseendpunkte liefern dasselbe, jeder Stufenwechsel ergibt die erwartete Art und Stufe, und eine lang
 * offene Transaktion hält den Feed an, ohne dass danach ein Eintrag fehlt oder doppelt kommt (Req 14.3).
 */
@QuarkusTest
class AenderungFeedTest {

    private static final String MANDANT = "urinet";
    private static final Zugriffsstufe[] STUFEN = Zugriffsstufe.values();

    @Inject
    AgroalDataSource datenquelle;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantKontext kontext;

    @Inject
    AenderungFeed feed;

    @Inject
    WebseiteLesen lesen;

    @Inject
    AenderungFeedAufraeumen aufraeumen;

    @Inject
    AenderungFeedPort port;

    private final List<UUID> dokumente = new ArrayList<>();

    @BeforeEach
    void mandantAnlegen() {
        try {
            provisionierung.anlegen(MANDANT, null, null);
        } catch (MandantFehler e) {
            assertThat(e.grund()).isEqualTo(MandantFehler.Grund.MANDANT_EXISTIERT);
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenquelle, MANDANT, dokumente);
        dokumente.clear();
    }

    private static Lesezugriff schluessel(Zugriffsstufe stufe) {
        return new Lesezugriff("probe", stufe, stufe, Optional.empty());
    }

    /** Eigener Thread ohne Request-Kontext wie der Worker; {@code MandantKontext.mit} verlangt das. */
    private <T> T imMandanten(Supplier<T> arbeit) {
        try {
            return CompletableFuture.supplyAsync(() -> kontext.mit(new MandantCode(MANDANT), arbeit)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw e;
        }
    }

    private String cursor() {
        return imMandanten(() -> feed.lesen(schluessel(Zugriffsstufe.VORSTAND), Optional.empty(), 100).cursor().kodieren());
    }

    private FeedSeite<LeseAenderung> lesen(Zugriffsstufe stufe, String seit) {
        return imMandanten(() -> feed.lesen(schluessel(stufe), Optional.of(seit), 500));
    }

    private UUID anlegen(String vertraulichkeit, boolean bestaetigt, String status) throws SQLException {
        UUID id = Testdokumente.anlegen(datenquelle,
                new Testdokumente.Angaben(MANDANT, null, vertraulichkeit, bestaetigt, "probe", null, status));
        dokumente.add(id);
        return id;
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenquelle.getConnection(); PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }

    private void zusatz(UUID id, String json) throws SQLException {
        ausfuehren("update m_urinet.dokument set zusatz = ?::jsonb, version = version + 1 where id = ?", json, id);
    }

    /** Zustand für die Webseite: {@code null} heisst nicht veröffentlicht (Stufe bleibt gesetzt, zählt aber nicht). */
    private void setzen(UUID id, Zugriffsstufe stufe) throws SQLException {
        zusatz(id, "{\"zugriff\": \"" + (stufe == null ? "vorstand" : stufe.code()) + "\", \"veroeffentlicht\": "
                + (stufe != null) + "}");
    }

    private List<String> letzterEintrag(UUID id) throws SQLException {
        try (Connection verbindung = datenquelle.getConnection(); PreparedStatement befehl = verbindung.prepareStatement(
                "select stufe_vorher, stufe_nachher from m_urinet.webseite_aenderung where dokument_id = ? "
                        + "order by id desc limit 1")) {
            befehl.setObject(1, id);
            try (ResultSet z = befehl.executeQuery()) {
                assertThat(z.next()).as("Eintrag zu %s", id).isTrue();
                return java.util.Arrays.asList(z.getString(1), z.getString(2));
            }
        }
    }

    @Test
    void triggerUndFesterFilterLiefernDasselbe() throws SQLException {
        String[] zugriffe = {"oeffentlich", "mitglieder", "vorstand", "unbekannt", null};
        String[] veroeffentlicht = {"true", "false", "\"true\"", null};
        int geprueft = 0;
        for (String status : new String[]{"ARCHIVIERT", "EINGANGSKORB"}) {
            for (String vertraulichkeit : new String[]{"NORMAL", "INTERN"}) {
                for (boolean bestaetigt : new boolean[]{true, false}) {
                    for (String zugriff : zugriffe) {
                        for (String ver : veroeffentlicht) {
                            UUID id = anlegen(vertraulichkeit, bestaetigt, status);
                            List<String> felder = new ArrayList<>();
                            if (zugriff != null) {
                                felder.add("\"zugriff\": \"" + zugriff + "\"");
                            }
                            if (ver != null) {
                                felder.add("\"veroeffentlicht\": " + ver);
                            }
                            zusatz(id, "{" + String.join(", ", felder) + "}");
                            String nachher = letzterEintrag(id).get(1);
                            for (Zugriffsstufe stufe : STUFEN) {
                                boolean sichtbar = imMandanten(() -> {
                                    try {
                                        lesen.details(schluessel(stufe), id);
                                        return true;
                                    } catch (FachFehler e) {
                                        assertThat(e.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN);
                                        return false;
                                    }
                                });
                                boolean erwartet = nachher != null
                                        && !Zugriffsstufe.ausCode(nachher).orElseThrow().hoeherAls(stufe);
                                assertThat(sichtbar).as("%s %s bestaetigt=%s zugriff=%s veroeffentlicht=%s bei Stufe %s",
                                        status, vertraulichkeit, bestaetigt, zugriff, ver, stufe).isEqualTo(erwartet);
                            }
                            geprueft++;
                        }
                    }
                }
            }
        }
        assertThat(geprueft).isEqualTo(2 * 2 * 2 * 5 * 4);
    }

    @Test
    void jederStufenwechselMeldetArtUndStufeFuerJedenSchluessel() throws SQLException {
        Zugriffsstufe[] zustaende = {null, Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.MITGLIEDER, Zugriffsstufe.VORSTAND};
        for (Zugriffsstufe vorher : zustaende) {
            for (Zugriffsstufe nachher : zustaende) {
                UUID id = anlegen("NORMAL", true, "ARCHIVIERT");
                setzen(id, vorher);
                String cursor = cursor();
                setzen(id, nachher);
                // Stufen der Webseite: nicht veröffentlicht zählt als nicht sichtbar
                for (Zugriffsstufe stufe : STUFEN) {
                    Optional<LeseAenderung> erwartet = LeseAenderung.bewerten(id, vorher, nachher, stufe, Instant.now());
                    List<LeseAenderung> ist = lesen(stufe, cursor).eintraege().stream()
                            .filter(a -> a.dokumentId().equals(id)).toList();
                    String fall = vorher + " -> " + nachher + " bei Schlüssel " + stufe;
                    assertThat(ist.stream().map(a -> a.art() + " " + a.stufe()).toList()).as(fall)
                            .isEqualTo(erwartet.stream().map(a -> a.art() + " " + a.stufe()).toList());
                }
            }
        }
    }

    @Test
    void einPhysischesLoeschenMeldetNichtSichtbarOhneInhalt() throws SQLException {
        UUID id = anlegen("NORMAL", true, "ARCHIVIERT");
        setzen(id, Zugriffsstufe.MITGLIEDER);
        String cursor = cursor();

        ausfuehren("delete from m_urinet.dokument where id = ?", id);
        dokumente.remove(id);

        assertThat(letzterEintrag(id)).containsExactly("mitglieder", null);
        assertThat(lesen(Zugriffsstufe.MITGLIEDER, cursor).eintraege()).singleElement()
                .satisfies(a -> {
                    assertThat(a.art()).isEqualTo(AenderungArt.NICHT_SICHTBAR);
                    assertThat(a.stufe()).isEqualTo(Zugriffsstufe.MITGLIEDER);
                });
        assertThat(lesen(Zugriffsstufe.OEFFENTLICH, cursor).eintraege()).isEmpty();
        ausfuehren("delete from m_urinet.webseite_aenderung where dokument_id = ?", id);
    }

    @Test
    void ohneAenderungEntstehtKeinEintrag() throws SQLException {
        UUID id = anlegen("NORMAL", true, "ARCHIVIERT");
        setzen(id, Zugriffsstufe.OEFFENTLICH);
        String cursor = cursor();

        ausfuehren("update m_urinet.dokument set titel = 'anderer Titel', volltext = 'Text' where id = ?", id);

        assertThat(lesen(Zugriffsstufe.VORSTAND, cursor).eintraege()).isEmpty();
    }

    @Test
    void eineLangOffeneTransaktionHaeltDenFeedAnUndNachherFehltNichts() throws SQLException {
        UUID a = anlegen("NORMAL", true, "ARCHIVIERT");
        UUID b = anlegen("NORMAL", true, "ARCHIVIERT");
        UUID c = anlegen("NORMAL", true, "ARCHIVIERT");
        setzen(a, Zugriffsstufe.OEFFENTLICH);
        setzen(b, Zugriffsstufe.OEFFENTLICH);
        setzen(c, Zugriffsstufe.OEFFENTLICH);
        String start = cursor();

        try (Connection offen = datenquelle.getConnection()) {
            offen.setAutoCommit(false);
            try (PreparedStatement befehl = offen.prepareStatement(
                    "update m_urinet.dokument set version = version + 1 where id = ?")) {
                // A schreibt zuerst und bleibt offen; B und C schliessen danach ab
                befehl.setObject(1, a);
                befehl.executeUpdate();
                ausfuehren("update m_urinet.dokument set version = version + 1 where id = ?", b);
                ausfuehren("update m_urinet.dokument set version = version + 1 where id = ?", c);

                FeedSeite<LeseAenderung> waehrend = lesen(Zugriffsstufe.VORSTAND, start);
                assertThat(waehrend.eintraege()).as("nichts vor dem Ende der offenen Transaktion").isEmpty();

                AenderungFeedPort.Horizont horizont = port.horizont();
                assertThat(horizont.xmin()).as("Horizont steht, solange A offen ist").isLessThan(horizont.xmax());

                offen.commit();
            }
        }

        FeedSeite<LeseAenderung> danach = lesen(Zugriffsstufe.VORSTAND, start);
        assertThat(danach.eintraege().stream().map(LeseAenderung::dokumentId).toList())
                .as("lückenlos, einmal und in Reihenfolge der Transaktionen").containsExactly(a, b, c);

        FeedSeite<LeseAenderung> weiter = lesen(Zugriffsstufe.VORSTAND, danach.cursor().kodieren());
        assertThat(weiter.eintraege()).as("kein Eintrag doppelt").isEmpty();
    }

    @Test
    void einAbgebrochenerSchreiberHinterlaesstNichts() throws SQLException {
        UUID a = anlegen("NORMAL", true, "ARCHIVIERT");
        setzen(a, Zugriffsstufe.OEFFENTLICH);
        String start = cursor();

        try (Connection offen = datenquelle.getConnection()) {
            offen.setAutoCommit(false);
            try (PreparedStatement befehl = offen.prepareStatement(
                    "update m_urinet.dokument set version = version + 1 where id = ?")) {
                befehl.setObject(1, a);
                befehl.executeUpdate();
                offen.rollback();
            }
        }

        assertThat(lesen(Zugriffsstufe.VORSTAND, start).eintraege()).isEmpty();
    }

    @Test
    void dieBereinigungLoeschtNurAlteEintraegeUndMachtAlteCursorUngueltig() throws SQLException {
        UUID id = anlegen("NORMAL", true, "ARCHIVIERT");
        setzen(id, Zugriffsstufe.OEFFENTLICH);
        String alt = cursor();
        zusatz(id, "{\"zugriff\": \"oeffentlich\", \"veroeffentlicht\": false}");
        // der Eintrag der letzten Änderung ist 100 Tage alt, der nächste ist neu
        ausfuehren("update m_urinet.webseite_aenderung set zeit = now() - interval '100 days' where dokument_id = ?", id);
        String mitte = cursor();
        zusatz(id, "{\"zugriff\": \"oeffentlich\", \"veroeffentlicht\": true}");

        int geloescht = imMandanten(() -> aufraeumen.aufraeumen(new MandantCode(MANDANT), Instant.now()));

        assertThat(geloescht).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> lesen(Zugriffsstufe.VORSTAND, alt)).isInstanceOfSatisfying(FachFehler.class,
                e -> assertThat(e.code()).isEqualTo(Fehlercode.CURSOR_ABGELAUFEN));
        assertThat(lesen(Zugriffsstufe.VORSTAND, mitte).eintraege()).singleElement()
                .satisfies(e -> assertThat(e.art()).isEqualTo(AenderungArt.SICHTBAR));
    }
}
