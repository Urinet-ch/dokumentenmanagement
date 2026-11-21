package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.wissen.domain.paket.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PaketRegelnGleichheitTest {
    private Path arbeit;

    @BeforeEach void vorbereiten() throws IOException {
        arbeit = Files.createDirectories(Path.of("target", "gleichheit-" + UUID.randomUUID()));
    }

    @AfterEach void aufraeumen() throws IOException {
        try (var pfade = Files.walk(arbeit)) {
            for (Path p : pfade.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    @Test void identischerKorpusVonLeserImportUndWerkzeug() throws Exception {
        for (var beispiel : WissensPaketBeispiele.alle()) {
            Path zip = arbeit.resolve("eingabe.zip");
            Files.write(zip, beispiel.zip());
            String readerCode = null;
            try { new WissensPaketLeser().lesen(beispiel.zip()); }
            catch (WissensPaketException e) { readerCode = e.code().name(); }
            assertThat(readerCode).as(beispiel.name()).isEqualTo(beispiel.readerCode());
            StringWriter ausgabe = new StringWriter();
            int status = WissenPaketCli.command(new PrintWriter(ausgabe, true), new PrintWriter(new StringWriter()))
                    .execute("validate", zip.toString(), "--json");
            JsonNode bericht = new ObjectMapper().readTree(ausgabe.toString());
            List<String> codes = new ArrayList<>();
            bericht.get("befunde").forEach(b -> codes.add(b.get("code").asText()));
            List<String> erwartet = readerCode == null ? beispiel.befunde() : List.of(readerCode);
            assertThat(codes).as(beispiel.name()).containsExactlyInAnyOrderElementsOf(erwartet);
            if (readerCode == null && !erwartet.isEmpty()) {
                bericht.get("befunde").forEach(b -> assertThat(b.get("pfad").asText()).isEqualTo("konzepte/test"));
            }
            assertThat(status).as(beispiel.name()).isEqualTo(erwartet.isEmpty() ? 0 : 1);
        }
    }
}
