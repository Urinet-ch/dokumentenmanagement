package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.ChatSpeicher;
import ch.urinet.archiv.ki.application.chat.ChatWerkzeugDokument;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.WerkzeugAufruf;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Req 5.3: {@code werkzeugDokumente} liefert nur die Treffer des angefragten Gesprächs, in der Reihenfolge des ersten
 * Auftretens; ein anderes Gespräch (gleicher oder anderer Benutzer) und ein anderer Mandant sehen sie nie, ein neues
 * Gespräch beginnt leer (die Nummerierung bei 1).
 */
@QuarkusTest
class JdbcChatSpeicherWerkzeugDokumenteTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");

    @Inject
    ChatSpeicher speicher;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> urinetGespraeche = new ArrayList<>();
    private final List<UUID> zweiterGespraeche = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            loeschen(verbindung, URINET, urinetGespraeche);
            loeschen(verbindung, ZWEITER, zweiterGespraeche);
        }
    }

    private static void loeschen(Connection verbindung, MandantCode mandant, List<UUID> gespraeche)
            throws SQLException {
        for (UUID id : gespraeche) {
            for (String tabelle : List.of("chat_nachricht where gespraech_id", "chat_gespraech where id")) {
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from " + mandant.schema() + "." + tabelle + " = ?")) {
                    befehl.setObject(1, id);
                    befehl.executeUpdate();
                }
            }
        }
    }

    @Test
    void werkzeugDokumenteLiefernNurDieTrefferDesGespraechs() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID a = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID gleicherBenutzer = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID anderer = gespraech(URINET, "benutzer-b", urinetGespraeche);
        UUID neu = gespraech(URINET, "benutzer-a", urinetGespraeche);
        UUID fremderMandant = gespraech(ZWEITER, "benutzer-a", zweiterGespraeche);
        UUID eins = UUID.randomUUID();
        UUID zwei = UUID.randomUUID();
        UUID drei = UUID.randomUUID();
        UUID nurAndere = UUID.randomUUID();
        antwort(URINET, a, new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(eins, zwei), Optional.empty()));
        antwort(URINET, a, new WerkzeugAufruf("dokument_zeigen", Map.of(), List.of(zwei), Optional.empty()),
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(drei), Optional.empty()));
        antwort(URINET, gleicherBenutzer,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));
        antwort(URINET, anderer,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));
        antwort(ZWEITER, fremderMandant,
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(nurAndere), Optional.empty()));

        assertThat(im(URINET, () -> speicher.werkzeugDokumente(a))).containsExactly(
                new ChatWerkzeugDokument("dokumente_suchen", eins), new ChatWerkzeugDokument("dokumente_suchen", zwei),
                new ChatWerkzeugDokument("dokument_zeigen", zwei), new ChatWerkzeugDokument("dokumente_suchen", drei));
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(gleicherBenutzer))).extracting(
                ChatWerkzeugDokument::dokumentId).containsExactly(nurAndere);
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(neu))).as("neues Gespräch beginnt leer").isEmpty();
        assertThat(im(URINET, () -> speicher.werkzeugDokumente(fremderMandant)))
                .as("Gespräch eines anderen Mandanten ist im Schema nicht vorhanden").isEmpty();
        assertThat(im(ZWEITER, () -> speicher.werkzeugDokumente(a))).isEmpty();
    }

    private UUID gespraech(MandantCode mandant, String benutzer, List<UUID> merken) {
        UUID id = UUID.randomUUID();
        im(mandant, () -> {
            speicher.gespraechAnlegen(id, benutzer, "Test " + id, Instant.now());
            return null;
        });
        merken.add(id);
        return id;
    }

    private void antwort(MandantCode mandant, UUID gespraech, WerkzeugAufruf... aufrufe) {
        ChatAntwort antwort = new ChatAntwort("Antwort", List.of(aufrufe), List.of(), Optional.empty(), ChatStatus.OK);
        im(mandant, () -> {
            speicher.antwortSpeichern(gespraech, antwort, Instant.now());
            return null;
        });
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
