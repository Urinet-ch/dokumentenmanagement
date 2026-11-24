package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.adapter.out.export.ChatBewertungZipExport;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.ki.domain.chat.Schwaerzung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatBewertungAuswertungTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void exportIstVollstaendigGeschwaerztUndSchemaKonform() throws Exception {
        FakeSpeicher speicher = new FakeSpeicher();
        speicher.namen = List.of(List.of("Ada Muster"));
        speicher.zeilen.add(new ChatAuswertungSpeicher.ExportZeile(Instant.parse("2026-11-22T11:00:00Z"),
                17L, 2, "FALSCH", "Kontakt Ada Muster", "E-Mail ada@example.test, Betrag CHF 42.50 am 22.11.2026",
                "Telefon +41 79 123 45 67; Ada Muster", """
                [{"name":"dokumente_suchen","parameter":{"frage":"ada@example.test","id":"%s"},"dokumentIds":["%s"],
                  "suchweg":"HYBRID","aehnlichkeiten":{"%s":0.87}}]
                """.formatted(DOKUMENT, DOKUMENT, DOKUMENT),
                "[{\"id\":\"" + DOKUMENT + "\",\"titel\":\"Synthetische Quelle\",\"link\":\"/dokument/"
                        + DOKUMENT + "\"}]", 12, 9, 350L, "test-modell", "prompt-v1", Chatmodus.WISSEN));
        List<ZugriffEintrag> protokoll = new ArrayList<>();
        ChatBewertungAuswertung anwendung = new ChatBewertungAuswertung(
                speicher, protokoll::add, new ChatBewertungZipExport());
        ByteArrayOutputStream ziel = new ByteArrayOutputStream();
        LocalDate heute = LocalDate.now(ZoneId.of("Europe/Zurich"));

        try (ChatBewertungAuswertung.Export export = anwendung.vorbereiten(heute.minusDays(1), heute, 1, 5,
                true, false, new MandantCode("urinet"), "inhaber-test", Optional.empty())) {
            export.schreiben(ziel);
        }

        Map<String, String> dateien = entpacken(ziel.toByteArray());
        assertThat(dateien.keySet()).containsExactlyInAnyOrder("schema.json", "README.txt", "bewertungen.jsonl");
        JsonNode export = JSON.readTree(dateien.get("bewertungen.jsonl").lines().findFirst().orElseThrow());
        assertThat(schema(dateien.get("schema.json")).validate(export.toString(), InputFormat.JSON)).isEmpty();
        assertThat(export.path("modus").asText()).isEqualTo("WISSEN");
        ObjectNode archivExport = (ObjectNode) export.deepCopy();
        archivExport.put("modus", "ARCHIV");
        assertThat(schema(dateien.get("schema.json"))
                .validate(archivExport.toString(), InputFormat.JSON)).isEmpty();
        assertThat(export.path("frage").asText()).contains("[E-MAIL]", "[BETRAG]", "[DATUM]")
                .doesNotContain("ada@example.test", "42.50", "Ada Muster");
        assertThat(export.path("freitext").asText()).contains("[PARTNER-1]").doesNotContain("Ada Muster");
        assertThat(export.path("antwort").asText()).contains("[TELEFON]", "[PARTNER-1]");
        assertThat(export.path("werkzeuge").get(0).path("parameter").path("frage").asText()).isEqualTo("[E-MAIL]");
        assertThat(export.path("werkzeuge").get(0).path("parameter").path("id").asText())
                .matches("[0-9a-f]{64}").isNotEqualTo(DOKUMENT.toString());
        assertThat(export.path("treffer").get(0).path("kennung").asText()).matches("[0-9a-f]{64}")
                .isNotEqualTo(DOKUMENT.toString());
        assertThat(export.path("treffer").get(0).path("aehnlichkeit").asDouble()).isEqualTo(0.87);
        assertThat(export.path("suchweg").toString()).contains("HYBRID");
        assertThat(dateien.get("bewertungen.jsonl")).doesNotContain(DOKUMENT.toString(), "Ada Muster", "ada@example.test");
        assertThat(speicher.snapshotEingerichtet).isTrue();
        assertThat(protokoll).hasSize(1);
    }

    @Test
    void zuVieleZeilenWerdenVorBeginnDesZipExportsAbgelehnt() {
        FakeSpeicher speicher = new FakeSpeicher();
        speicher.anzahl = ChatBewertungAuswertung.MAX_EXPORT_ZEILEN + 1L;
        List<ZugriffEintrag> protokoll = new ArrayList<>();
        ChatBewertungAuswertung anwendung = new ChatBewertungAuswertung(
                speicher, protokoll::add, new ChatBewertungZipExport());
        ByteArrayOutputStream ziel = new ByteArrayOutputStream();
        LocalDate heute = LocalDate.now(ZoneId.of("Europe/Zurich"));

        assertThatThrownBy(() -> anwendung.vorbereiten(heute.minusDays(1), heute, 1, 5, true, false,
                new MandantCode("urinet"), "inhaber-test", Optional.empty()))
                .isInstanceOf(FachFehler.class)
                .extracting(fehler -> ((FachFehler) fehler).code())
                .isEqualTo(Fehlercode.BEWERTUNG_EXPORT_ZU_GROSS);
        assertThat(ziel.size()).isZero();
        assertThat(speicher.zeilenExportiert).isZero();
        assertThat(protokoll).isEmpty();
    }

    private static Map<String, String> entpacken(byte[] zip) throws IOException {
        Map<String, String> dateien = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var eintrag = in.getNextEntry(); eintrag != null; eintrag = in.getNextEntry()) {
                dateien.put(eintrag.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return dateien;
    }

    private static Schema schema(String inhalt) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(inhalt, InputFormat.JSON);
    }

    private static final class FakeSpeicher implements ChatAuswertungSpeicher {
        private final List<ExportZeile> zeilen = new ArrayList<>();
        private List<List<String>> namen = List.of();
        private long anzahl = -1;
        private boolean snapshotEingerichtet;
        private int zeilenExportiert;

        @Override
        public ExportSnapshot exportSnapshot(Filter filter) {
            snapshotEingerichtet = true;
            return new ExportSnapshot() {
                @Override
                public long anzahl() {
                    return anzahl < 0 ? zeilen.size() : anzahl;
                }

                @Override
                public List<List<String>> kontaktUndPartnerNamensgruppen() {
                    return namen;
                }

                @Override
                public void exportZeilen(java.util.function.Consumer<ExportZeile> verarbeiten) {
                    zeilen.forEach(zeile -> {
                        zeilenExportiert++;
                        verarbeiten.accept(zeile);
                    });
                }

                @Override
                public void commit() {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public Auswertung auswertung(Filter filter) {
            return new Auswertung(0, 0, new long[5], List.of(), List.of());
        }
    }
}
