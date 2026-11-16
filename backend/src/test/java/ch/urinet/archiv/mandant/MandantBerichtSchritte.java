package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantBerichtDaten;
import ch.urinet.archiv.mandant.application.MandantBerichtEinreihen;
import ch.urinet.archiv.mandant.application.MandantBerichtHandler;
import ch.urinet.archiv.mandant.application.MandantBerichtPlanen;
import ch.urinet.archiv.mandant.application.MandantBerichtSpeicher;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 8.1 mit ausschliesslich synthetischen Berichtsdaten. */
public class MandantBerichtSchritte {

    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    @Inject
    MandantKontext kontext;

    @Inject
    MandantBerichtDaten daten;

    @Inject
    MandantBerichtEinreihen einreihen;

    @Inject
    MandantBerichtHandler handler;

    @Inject
    MandantBerichtPlanen planen;

    @Inject
    MandantBerichtSpeicher speicher;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    Config config;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final String marker = "bericht-" + UUID.randomUUID();
    private final Map<String, MandantBericht> vorher = new HashMap<>();
    private final Map<String, List<UUID>> dokumente = new HashMap<>();
    private final Map<String, UUID> gespraeche = new HashMap<>();
    private final Map<String, List<Long>> oeffentlicheFragen = new HashMap<>();
    private final List<Long> auftragIds = new ArrayList<>();
    private YearMonth monat;
    private YearMonth planMonat;
    private boolean deaktiviert;

    @Angenommen("die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet")
    public void datenVorbereiten() throws Exception {
        monat = YearMonth.of(3000 + ThreadLocalRandom.current().nextInt(6000), 2);
        for (String code : MANDANTEN) {
            MandantBerichtFenster fenster = MandantBerichtFenster.fuer(monat);
            vorher.put(code, imMandanten(code, () -> daten.erheben(new MandantCode(code), fenster)));
            datenEinsetzen(code);
            OptionalLong id = imMandanten(code, () -> einreihen.einreihen(new MandantCode(code), fenster));
            assertThat(id).as("Berichtsauftrag für " + code).isPresent();
            auftragIds.add(id.orElseThrow());
            assertThat(imMandanten(code, () -> einreihen.einreihen(new MandantCode(code), fenster)))
                    .as("Doppelter Berichtsauftrag für " + code).isEmpty();
        }
    }

    @Wenn("der Monatsbericht für beide Mandanten zweimal verarbeitet wird")
    public void berichteVerarbeiten() throws Exception {
        for (String code : MANDANTEN) {
            Auftrag auftrag = new Auftrag(0, new MandantCode(code), MandantBerichtEinreihen.TYP,
                    new Nutzlast(Map.of(MandantBerichtEinreihen.MONAT, monat.atDay(1).toEpochDay())), 0);
            imMandanten(code, () -> {
                handler.ausfuehren(auftrag);
                handler.ausfuehren(auftrag);
                return null;
            });
        }
    }

    @Dann("enthält der Bericht je Mandant nur dessen eigene Dokumente, Speicherwerte und Kennzahlen")
    public void berichteGetrennt() throws Exception {
        for (String code : MANDANTEN) {
            MandantBericht bericht = gespeichert(code).bericht();
            MandantBericht vorheriger = vorher.get(code);
            assertThat(bericht.mandant()).isEqualTo(new MandantCode(code));
            assertThat(bericht.monat()).isEqualTo(monat);
            assertThat(bericht.speicherBytes()).isEqualTo(vorheriger.speicherBytes());
            Map<String, Long> neu = code.equals("urinet") ? Map.of("NEU", 1L, "ARCHIVIERT", 1L) : Map.of("FEHLER", 1L);
            for (var delta : neu.entrySet()) {
                assertThat(bericht.dokumente().getOrDefault(delta.getKey(), 0L)
                        - vorheriger.dokumente().getOrDefault(delta.getKey(), 0L)).isEqualTo(delta.getValue());
            }
        }
    }

    @Dann("zählen KI-Tokens und Chat-Fragen nur Einträge innerhalb des Monats")
    public void monatsgrenze() throws Exception {
        for (String code : MANDANTEN) {
            MandantBericht bericht = gespeichert(code).bericht();
            MandantBericht vorheriger = vorher.get(code);
            boolean urinet = code.equals("urinet");
            tokensDelta(bericht, vorheriger, "ERFASSUNG", urinet ? 10 : 3, urinet ? 20 : 4);
            tokensDelta(bericht, vorheriger, "CHAT", urinet ? 30 : 5, urinet ? 40 : 6);
            int interneFragen = urinet ? 2 : 1;
            assertThat(bericht.chatFragen() - vorheriger.chatFragen()).isEqualTo(interneFragen + 1);
        }
    }

    @Dann("ist je Mandant genau ein Bericht gespeichert und genau eine Mail an dessen Empfänger versandt")
    public void idempotent() throws Exception {
        for (String code : MANDANTEN) {
            assertThat(gespeichert(code).mailVersandt()).isTrue();
            assertThat(mail(code).jsonPath().<String>getList("To.Address"))
                    .containsExactly(config.getValue("archiv.benachrichtigung.empfaenger." + code, String.class));
        }
    }

    @Dann("enthalten gespeicherter Bericht und Mail nur Zahlen und keine Inhalte")
    public void ohneInhalte() throws Exception {
        for (String code : MANDANTEN) {
            assertThat(serialisierteZeile(code)).doesNotContain(marker);
            assertThat(gespeichert(code).bericht().toString()).doesNotContain(marker);
            long fragenNeu = code.equals("urinet") ? 3 : 2;
            String text = mail(code).jsonPath().getString("Text");
            assertThat(text).contains("Chat-Fragen: " + fragenNeu);
            assertThat(text).doesNotContain(marker, "synthetischer Titel", "synthetischer Fragetext",
                    "synthetische Person");
        }
    }

    @Angenommen("der Mandant «vhf-gsk» ist deaktiviert")
    public void mandantDeaktivieren() throws Exception {
        planMonat = YearMonth.of(3000 + ThreadLocalRandom.current().nextInt(6000), 3);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement update = verbindung.prepareStatement(
                     "update public.mandant set aktiv = false where code = 'vhf-gsk' and aktiv")) {
            deaktiviert = update.executeUpdate() == 1;
        }
    }

    @Wenn("die Monatsberichte des Vormonats geplant werden")
    public void planen() throws Exception {
        // wie der Takt in einem eigenen Thread, ausserhalb des Request-Kontexts des Tests
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> planen.planen(planMonat.plusMonths(1).atDay(5).atStartOfDay(ZUERICH).toInstant()))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    @Dann("ist nur für den aktiven Mandanten «urinet» ein Berichtsauftrag eingereiht")
    public void nurAktiveGeplant() throws Exception {
        assertThat(berichtsauftraege("urinet")).isEqualTo(1);
        assertThat(berichtsauftraege("vhf-gsk")).isZero();
    }

    @After("@req-8.1")
    public void planungAufraeumen() throws Exception {
        if (planMonat == null) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from public.auftrag where typ = 'MANDANT_BERICHT' and nutzlast ->> 'monat' = ?")) {
                loeschen.setString(1, String.valueOf(planMonat.atDay(1).toEpochDay()));
                loeschen.executeUpdate();
            }
            if (deaktiviert) {
                try (PreparedStatement update = verbindung.prepareStatement(
                        "update public.mandant set aktiv = true where code = 'vhf-gsk'")) {
                    update.executeUpdate();
                }
            }
        }
    }

    private long berichtsauftraege(String code) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from public.auftrag where typ = 'MANDANT_BERICHT' and mandant = ? "
                             + "and nutzlast ->> 'monat' = ?")) {
            abfrage.setString(1, code);
            abfrage.setString(2, String.valueOf(planMonat.atDay(1).toEpochDay()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    @After("@req-8.1")
    public void aufraeumen() throws Exception {
        if (monat == null) {
            return;
        }
        for (String code : MANDANTEN) {
            imMandanten(code, () -> {
                QuarkusTransaction.requiringNew().run(() -> {
                    try (Connection verbindung = datenbank.get().getConnection()) {
                        String schema = new MandantCode(code).schema();
                        try (PreparedStatement bericht = verbindung.prepareStatement(
                                "delete from " + schema + ".mandant_bericht where monat = ?")) {
                            bericht.setObject(1, monat.atDay(1));
                            bericht.executeUpdate();
                        }
                        try (PreparedStatement loeschen = verbindung.prepareStatement(
                                "delete from " + schema + ".dokument where id = ?")) {
                            for (UUID id : dokumente.getOrDefault(code, List.of())) {
                                loeschen.setObject(1, id);
                                loeschen.addBatch();
                            }
                            loeschen.executeBatch();
                        }
                        UUID gespraech = gespraeche.get(code);
                        if (gespraech != null) {
                            try (PreparedStatement loeschen = verbindung.prepareStatement(
                                    "delete from " + schema + ".chat_gespraech where id = ?")) {
                                loeschen.setObject(1, gespraech);
                                loeschen.executeUpdate();
                            }
                        }
                        try (PreparedStatement loeschen = verbindung.prepareStatement(
                                "delete from " + schema + ".oeffentliche_frage where id = ?")) {
                            for (Long id : oeffentlicheFragen.getOrDefault(code, List.of())) {
                                loeschen.setLong(1, id);
                                loeschen.addBatch();
                            }
                            loeschen.executeBatch();
                        }
                        try (PreparedStatement loeschen = verbindung.prepareStatement(
                                "delete from " + schema + ".ki_verbrauch where modell = ?")) {
                            loeschen.setString(1, modell());
                            loeschen.executeUpdate();
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("Monatsbericht-Testdaten nicht bereinigt", e);
                    }
                });
                return null;
            });
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement("delete from public.auftrag where id = ?")) {
            for (Long id : auftragIds) {
                loeschen.setLong(1, id);
                loeschen.addBatch();
            }
            loeschen.executeBatch();
        }
    }

    private MandantBerichtSpeicher.Eintrag gespeichert(String code) throws Exception {
        return imMandanten(code, () -> speicher.lesen(monat).orElseThrow());
    }

    private static void tokensDelta(MandantBericht bericht, MandantBericht vorheriger, String zweck,
                                   long eingabe, long ausgabe) {
        MandantBericht.Tokens aktuell = bericht.kiTokens().get(zweck);
        MandantBericht.Tokens vorher = vorheriger.kiTokens().getOrDefault(zweck, new MandantBericht.Tokens(0, 0));
        assertThat(aktuell.eingabe() - vorher.eingabe()).isEqualTo(eingabe);
        assertThat(aktuell.ausgabe() - vorher.ausgabe()).isEqualTo(ausgabe);
    }

    /** Die einzige Mail des Mandanten zum Monat (genau eine, auch nach zweimaliger Verarbeitung). */
    private Response mail(String code) {
        String betreff = "UriNet Archiv: Monatsbericht " + code + " " + monat;
        int port = mailpitPort.orElseThrow(() -> new IllegalStateException("Mailpit-Port für das Szenario fehlt"));
        Response suche = given().baseUri("http://localhost:" + port)
                .queryParam("query", "subject:\"" + betreff + "\"").when().get("/api/v1/search");
        assertThat(suche.statusCode()).isEqualTo(200);
        List<Map<String, Object>> nachrichten = suche.jsonPath().getList("messages");
        List<Map<String, Object>> passend = nachrichten.stream()
                .filter(n -> betreff.equals(String.valueOf(n.get("Subject")))).toList();
        assertThat(passend).hasSize(1);
        Response mail = given().baseUri("http://localhost:" + port)
                .when().get("/api/v1/message/" + passend.getFirst().get("ID"));
        assertThat(mail.statusCode()).isEqualTo(200);
        return mail;
    }

    private String serialisierteZeile(String code) throws Exception {
        return imMandanten(code, () -> {
            String schema = new MandantCode(code).schema();
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select to_jsonb(b)::text from " + schema + ".mandant_bericht b where monat = ?")) {
                abfrage.setObject(1, monat.atDay(1));
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Gespeicherter Monatsbericht fehlt");
                    }
                    return zeile.getString(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Monatsbericht-Testdaten nicht gelesen", e);
            }
        });
    }

    private void datenEinsetzen(String code) throws Exception {
        imMandanten(code, () -> {
            QuarkusTransaction.requiringNew().run(() -> {
                try (Connection verbindung = datenbank.get().getConnection()) {
                    String schema = new MandantCode(code).schema();
                    List<UUID> ids = new ArrayList<>();
                    dokumente.put(code, ids);
                    if (code.equals("urinet")) {
                        dokument(verbindung, schema, "NEU", ids);
                        dokument(verbindung, schema, "ARCHIVIERT", ids);
                    } else {
                        dokument(verbindung, schema, "FEHLER", ids);
                    }
                    gespraeche.put(code, gespraech(verbindung, schema, code));
                    kiDaten(verbindung, schema, code);
                    oeffentlicheFragen.put(code, fragen(verbindung, schema, code));
                } catch (SQLException e) {
                    throw new IllegalStateException("Synthetische Monatsbericht-Daten nicht angelegt", e);
                }
            });
            return null;
        });
    }

    private void dokument(Connection verbindung, String schema, String status, List<UUID> ids)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into " + schema + ".dokument (id, quelle, erstellt_von, status, titel) "
                        + "values (?, 'UPLOAD', ?, ?, ?)")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, marker + "-person");
            einfuegen.setString(3, status);
            einfuegen.setString(4, "synthetischer Titel " + marker);
            einfuegen.executeUpdate();
        }
        ids.add(id);
    }

    private UUID gespraech(Connection verbindung, String schema, String code) throws SQLException {
        UUID id = UUID.randomUUID();
        Instant start = monat.atDay(1).atStartOfDay(ZUERICH).toInstant();
        Instant ende = monat.plusMonths(1).atDay(1).atStartOfDay(ZUERICH).toInstant();
        try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, ?, ?, ?)")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, marker + "-person");
            einfuegen.setString(3, "synthetischer Titel " + marker);
            einfuegen.setTimestamp(4, Timestamp.from(start));
            einfuegen.setTimestamp(5, Timestamp.from(start));
            einfuegen.executeUpdate();
        }
        int imMonat = code.equals("urinet") ? 2 : 1;
        for (int i = 0; i < imMonat + 2; i++) {
            Instant zeit = switch (i) {
                case 0 -> start;
                case 1 -> ende.minusSeconds(1);
                case 2 -> ende;
                default -> start.minusSeconds(1);
            };
            if (i >= imMonat) {
                zeit = i == imMonat ? ende : start.minusSeconds(1);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                    + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', ?, ?)")) {
                einfuegen.setObject(1, id);
                einfuegen.setString(2, "synthetischer Fragetext " + marker + "-" + i);
                einfuegen.setTimestamp(3, Timestamp.from(zeit));
                einfuegen.executeUpdate();
            }
        }
        return id;
    }

    private void kiDaten(Connection verbindung, String schema, String code) throws SQLException {
        long erfassungEin = code.equals("urinet") ? 10 : 3;
        long erfassungAus = code.equals("urinet") ? 20 : 4;
        long chatEin = code.equals("urinet") ? 30 : 5;
        long chatAus = code.equals("urinet") ? 40 : 6;
        Instant start = monat.atDay(1).atStartOfDay(ZUERICH).toInstant();
        Instant ende = monat.plusMonths(1).atDay(1).atStartOfDay(ZUERICH).toInstant();
        kiZeile(verbindung, schema, "ERFASSUNG", erfassungEin, erfassungAus, start);
        kiZeile(verbindung, schema, "CHAT", chatEin, chatAus, ende.minusSeconds(1));
        kiZeile(verbindung, schema, "ERFASSUNG", 100, 200, ende);
        kiZeile(verbindung, schema, "ERFASSUNG", 300, 400, start.minusSeconds(1));
    }

    private void kiZeile(Connection verbindung, String schema, String zweck, long tokensEin, long tokensAus,
                         Instant zeit) throws SQLException {
        try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                + ".ki_verbrauch (zweck, modell, tokens_ein, tokens_aus, zeit) values (?, ?, ?, ?, ?)")) {
            einfuegen.setString(1, zweck);
            einfuegen.setString(2, modell());
            einfuegen.setLong(3, tokensEin);
            einfuegen.setLong(4, tokensAus);
            einfuegen.setTimestamp(5, Timestamp.from(zeit));
            einfuegen.executeUpdate();
        }
    }

    private List<Long> fragen(Connection verbindung, String schema, String code) throws SQLException {
        Instant start = monat.atDay(1).atStartOfDay(ZUERICH).toInstant();
        Instant ende = monat.plusMonths(1).atDay(1).atStartOfDay(ZUERICH).toInstant();
        List<Long> ids = new ArrayList<>();
        Instant[] zeiten = code.equals("urinet")
                ? new Instant[] {ende.minusSeconds(1), ende}
                : new Instant[] {start, ende};
        for (int i = 0; i < zeiten.length; i++) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                    + ".oeffentliche_frage (frage, quellen, tokens, zeit) values (?, '[]'::jsonb, 0, ?) returning id")) {
                einfuegen.setString(1, "synthetischer Fragetext " + marker + "-" + i);
                einfuegen.setTimestamp(2, Timestamp.from(zeiten[i]));
                try (ResultSet zeile = einfuegen.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Synthetische öffentliche Frage nicht gespeichert");
                    }
                    ids.add(zeile.getLong(1));
                }
            }
        }
        return ids;
    }

    private String modell() {
        return marker + "-model";
    }

    private <T> T imMandanten(String code, Callable<T> arbeit) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            return worker.submit(() -> kontext.mit(new MandantCode(code), () -> {
                try {
                    return arbeit.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("Mandantenbericht-Test fehlgeschlagen", e);
                }
            })).get(30, TimeUnit.SECONDS);
        }
    }
}
