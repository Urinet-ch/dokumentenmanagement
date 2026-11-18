package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.dokument.application.DokumentKlassifikationenPort;
import ch.urinet.archiv.konfiguration.application.KlassifikationRepository;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mandantentrennung der Klassifikationen (tech.md «Tests und Qualität», Spec 05 Req 1.2): Klassifikationen, Werte,
 * Zuordnungen zu Arten und Werte an Dokumenten liegen im Schema des Mandanten aus dem Kontext. Gleiche Codes in zwei
 * Mandanten stören sich nicht. Läuft in eigenen Threads ohne Request-Kontext, schreibt direkt über die Repositories.
 */
@QuarkusTest
class KlassifikationMandantentrennungTest {

    private static final String CODE = "trennprobe";

    @Inject
    KlassifikationRepository klassifikationen;

    @Inject
    DokumentKlassifikationenPort dokumentKlassifikationen;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private UUID dokument;

    @BeforeEach
    void mandanten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
    }

    @AfterEach
    void aufraeumen() throws Exception {
        try {
            if (dokument != null) {
                Testdokumente.loeschen(datenbank, "urinet", List.of(dokument));
            }
            for (String mandant : List.of("urinet", "vhf-gsk")) {
                in(mandant, () -> {
                    klassifikationen.zuordnungen(art(mandant)).stream()
                            .filter(zuordnung -> CODE.equals(zuordnung.klassifikation()))
                            .forEach(zuordnung -> klassifikationen.zuordnungEntfernen(art(mandant), CODE));
                    if (klassifikationen.finden(CODE).isPresent()) {
                        klassifikationen.loeschen(CODE);
                    }
                    return null;
                });
            }
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    void klassifikationenZuordnungenUndDokumentwerteBleibenImEigenenMandanten() throws Exception {
        in("urinet", () -> {
            klassifikationen.anlegen(klassifikation("Urinet", "bau"));
            klassifikationen.zuordnen("PROJEKTDOKUMENT", new ArtKlassifikation(CODE, false, "bau"));
            return null;
        });
        dokument = Testdokumente.anlegen(datenbank, new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT",
                "NORMAL", true, "probe", null, "ARCHIVIERT"));
        in("urinet", () -> {
            dokumentKlassifikationen.ersetzen(dokument, Map.of(CODE, List.of("bau")));
            return null;
        });

        in("vhf-gsk", () -> {
            assertThat(klassifikationen.alle()).extracting(Klassifikation::code).doesNotContain(CODE);
            assertThat(klassifikationen.finden(CODE)).isEmpty();
            assertThat(klassifikationen.zugeordnet("PROJEKTDOKUMENT", CODE)).isFalse();
            assertThat(klassifikationen.inGebrauch(CODE)).isFalse();
            assertThat(klassifikationen.wertInGebrauch(CODE, "bau")).isFalse();
            assertThat(dokumentKlassifikationen.laden(dokument)).isEmpty();
            return null;
        });

        // Gleicher Code im zweiten Mandanten: eigene Werte, eigene Zuordnung, eigenes Löschen.
        in("vhf-gsk", () -> {
            klassifikationen.anlegen(klassifikation("VHF", "statik"));
            klassifikationen.zuordnen("PROBE_VHF_BULLETIN", new ArtKlassifikation(CODE, true, "statik"));
            assertThat(klassifikationen.finden(CODE).orElseThrow().werte())
                    .extracting(KlassifikationWert::code).containsExactly("statik");
            assertThat(klassifikationen.zuordnungen("PROBE_VHF_BULLETIN"))
                    .contains(new ArtKlassifikation(CODE, true, "statik"));
            dokumentKlassifikationen.ersetzen(dokument, Map.of());
            klassifikationen.zuordnungEntfernen("PROBE_VHF_BULLETIN", CODE);
            klassifikationen.loeschen(CODE);
            assertThat(klassifikationen.finden(CODE)).isEmpty();
            return null;
        });

        in("urinet", () -> {
            Klassifikation eigene = klassifikationen.finden(CODE).orElseThrow();
            assertThat(eigene.name()).isEqualTo("Urinet");
            assertThat(eigene.werte()).extracting(KlassifikationWert::code).containsExactly("bau");
            assertThat(klassifikationen.zuordnungen("PROJEKTDOKUMENT"))
                    .contains(new ArtKlassifikation(CODE, false, "bau"));
            assertThat(klassifikationen.zugeordnet("PROBE_VHF_BULLETIN", CODE)).isFalse();
            assertThat(klassifikationen.inGebrauch(CODE)).isTrue();
            assertThat(klassifikationen.wertInGebrauch(CODE, "bau")).isTrue();
            assertThat(dokumentKlassifikationen.laden(dokument)).containsExactly(Map.entry(CODE, List.of("bau")));
            return null;
        });
    }

    private <T> T in(String mandant, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(mandant), () -> QuarkusTransaction.requiringNew()
                .call(arbeit))).get(30, TimeUnit.SECONDS);
    }

    private static String art(String mandant) {
        return "urinet".equals(mandant) ? "PROJEKTDOKUMENT" : "PROBE_VHF_BULLETIN";
    }

    private static Klassifikation klassifikation(String name, String wert) {
        return new Klassifikation(CODE, name, null, List.of(new KlassifikationWert(wert, wert, null, 0, true)),
                false, false, true, false, true);
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
