package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.adapter.out.export.ChatBewertungZipExport;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class ChatBewertungAuswertungKnapperHeapTest {

    private static final int ANZAHL = 5_000;
    private static final String LANGE_ANTWORT = "x".repeat(32 * 1024);

    @Test
    void fuenftausendLangeAntwortenWerdenUnterKnappemHeapGestreamt(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("archiv.speicher-test"), "nur in der Ausführung «speicher»");
        assertThat(Runtime.getRuntime().maxMemory()).isLessThan((long) ANZAHL * LANGE_ANTWORT.length());

        ChatAuswertungSpeicher speicher = new ChatAuswertungSpeicher() {
            @Override
            public ExportSnapshot exportSnapshot(Filter filter) {
                return new ExportSnapshot() {
                    @Override
                    public long anzahl() {
                        return ANZAHL;
                    }

                    @Override
                    public List<List<String>> kontaktUndPartnerNamensgruppen() {
                        return List.of();
                    }

                    @Override
                    public void exportZeilen(java.util.function.Consumer<ExportZeile> verarbeiten) {
                        Instant zeit = Instant.parse("2026-11-22T11:00:00Z");
                        for (int id = 1; id <= ANZAHL; id++) {
                            verarbeiten.accept(new ExportZeile(zeit, id, 4, null, null, "Frage", LANGE_ANTWORT,
                                    "[]", "[]", null, null, null, null, null, Chatmodus.ARCHIV));
                        }
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
        };
        ChatBewertungAuswertung auswertung = new ChatBewertungAuswertung(
                speicher, eintrag -> {}, new ChatBewertungZipExport());
        LocalDate heute = LocalDate.now(ZoneId.of("Europe/Zurich"));
        Path zipPfad = temp.resolve("chat-bewertungen.zip");
        try (ChatBewertungAuswertung.Export export = auswertung.vorbereiten(heute.minusDays(1), heute, 1, 5,
                false, true, new MandantCode("urinet"), "inhaber-test", Optional.<IpAdresse>empty());
             OutputStream ziel = Files.newOutputStream(zipPfad)) {
            export.schreiben(ziel);
        }

        try (ZipFile zip = new ZipFile(zipPfad.toFile());
             BufferedReader zeilen = new BufferedReader(new InputStreamReader(
                     zip.getInputStream(zip.getEntry("bewertungen.jsonl")), StandardCharsets.UTF_8))) {
            int anzahl = 0;
            String zeile;
            while ((zeile = zeilen.readLine()) != null) {
                assertThat(zeile.length()).isGreaterThan(LANGE_ANTWORT.length());
                anzahl++;
            }
            assertThat(anzahl).isEqualTo(ANZAHL);
        }
    }
}
