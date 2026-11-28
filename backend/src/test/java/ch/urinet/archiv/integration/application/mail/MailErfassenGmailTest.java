package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.Typerkennung;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.Dateityp;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.integration.domain.mail.MailHerkunft;
import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MailErfassenGmailTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final String MESSAGE_ID = "<synthetic@example.invalid>";

    @TempDir
    Path verzeichnis;

    @Test
    void wiederholterLaufErfasstDieselbeNachrichtNichtNochmals() {
        TestRegister register = new TestRegister();
        MailErfassen erfassen = new MailErfassen(register, null, null, null);

        MailErfassen.GmailErgebnis erster = erfassen.erfassenGmail(MANDANT, nachricht(), false);
        MailErfassen.GmailErgebnis zweiter = erfassen.erfassenGmail(MANDANT, nachricht(), false);

        assertThat(erster).isEqualTo(new MailErfassen.GmailErgebnis(1, 0, 0, 1, 0, List.of()));
        assertThat(zweiter).isEqualTo(new MailErfassen.GmailErgebnis(1, 0, 0, 1, 0, List.of()));
        assertThat(register.eintraege).hasSize(1);
        assertThat(register.herkunft).containsExactly(MailHerkunft.GMAIL_LABEL);
        assertThat(register.dokumente).containsEntry(MESSAGE_ID, List.of());
    }

    @Test
    void messageIdAusPostfacheingangVerhindertAuchGmailDubletten() {
        TestRegister register = new TestRegister();
        register.eintraege.put(MESSAGE_ID, MailStatus.ERFASST);
        MailErfassen erfassen = new MailErfassen(register, null, null, null);

        MailErfassen.GmailErgebnis ergebnis = erfassen.erfassenGmail(MANDANT, nachricht(), false);

        assertThat(ergebnis).isEqualTo(new MailErfassen.GmailErgebnis(1, 0, 0, 1, 0, List.of()));
        assertThat(register.herkunft).isEmpty();
        assertThat(register.eintraege).containsEntry(MESSAGE_ID, MailStatus.ERFASST);
    }

    @Test
    void unterstuetzteAnhaengeWerdenGetrenntErfasstUndKleineBilderUebersprungen() throws IOException {
        TestRegister register = new TestRegister();
        TestDokumente dokumente = new TestDokumente();
        Path pdf = datei("synthetisch.pdf", 500);
        Path kleinesBild = datei("klein.jpg", 20 * 1024 - 1);
        Path bild = datei("bild.jpg", 20 * 1024);
        Path png = datei("bild.png", 20 * 1024);
        Path heic = datei("bild.heic", 20 * 1024);
        Path tiff = datei("bild.tif", 20 * 1024);
        Path unbekannt = datei("unbekannt.bin", 500);
        Map<Path, Dateityp> erkannteTypen = Map.of(pdf, Dateityp.PDF, kleinesBild, Dateityp.JPEG,
                bild, Dateityp.JPEG, png, Dateityp.PNG, heic, Dateityp.HEIC, tiff, Dateityp.TIFF);
        Typerkennung typen = pfad -> Optional.ofNullable(erkannteTypen.get(pfad));
        MailErfassen erfassen = new MailErfassen(register, dokumente, typen, textPdf());
        PostfachPort.Nachricht nachricht = new PostfachPort.Nachricht(MESSAGE_ID, Optional.of("from@example.invalid"),
                Optional.of("Synthetic"), Optional.of(Instant.parse("2026-01-02T03:04:00Z")),
                Path.of("synthetische-nachricht.eml"),
                List.of(new PostfachPort.Anhang(pdf, Optional.empty()),
                        new PostfachPort.Anhang(kleinesBild, Optional.empty()),
                        new PostfachPort.Anhang(bild, Optional.empty()),
                        new PostfachPort.Anhang(png, Optional.empty()),
                        new PostfachPort.Anhang(heic, Optional.empty()),
                        new PostfachPort.Anhang(tiff, Optional.empty()),
                        new PostfachPort.Anhang(unbekannt, Optional.empty())), "synthetic");

        MailErfassen.GmailErgebnis ergebnis = erfassen.erfassenGmail(MANDANT, nachricht, true);

        assertThat(ergebnis).isEqualTo(new MailErfassen.GmailErgebnis(7, 5, 0, 2, 0, dokumente.ids));
        assertThat(dokumente.uploads).hasSize(1);
        UploadAnfrage upload = dokumente.uploads.getFirst();
        assertThat(upload.seiten()).extracting(UploadAnfrage.Seite::original)
                .containsExactly(pdf, bild, png, heic, tiff);
        assertThat(upload.zusammenfassen()).isFalse();
        assertThat(upload.erfasser().quelle()).isEqualTo(DokumentErfassen.QUELLE_GMAIL);
        assertThat(upload.hinweis()).hasValueSatisfying(hinweis -> assertThat(hinweis)
                .contains("from@example.invalid").contains("Datum: 02.01.2026 04:04").contains("Synthetic"));
        assertThat(dokumente.korrespondenzQuellen).isEmpty();
    }

    @Test
    void nachrichtenOhneAnhangWerdenNurBeiAktivierterOptionAlsKorrespondenzErfasst() {
        TestRegister register = new TestRegister();
        TestDokumente dokumente = new TestDokumente();
        MailErfassen erfassen = new MailErfassen(register, dokumente, pfad -> Optional.empty(), textPdf());

        MailErfassen.GmailErgebnis ignoriert = erfassen.erfassenGmail(MANDANT, nachricht(), false);
        TestRegister zweiterRegister = new TestRegister();
        TestDokumente zweitesDokumente = new TestDokumente();
        MailErfassen.GmailErgebnis erfasst = new MailErfassen(zweiterRegister, zweitesDokumente,
                pfad -> Optional.empty(), textPdf()).erfassenGmail(MANDANT, nachricht(), true);

        assertThat(ignoriert).isEqualTo(new MailErfassen.GmailErgebnis(1, 0, 0, 1, 0, List.of()));
        assertThat(dokumente.korrespondenzQuellen).isEmpty();
        assertThat(erfasst.neu()).isEqualTo(1);
        assertThat(zweitesDokumente.korrespondenzQuellen).containsExactly(DokumentErfassen.QUELLE_GMAIL);
    }

    private static PostfachPort.Nachricht nachricht() {
        return new PostfachPort.Nachricht(MESSAGE_ID, Optional.empty(), Optional.empty(), Optional.empty(),
                Path.of("synthetische-nachricht.eml"), List.of(), "");
    }

    private Path datei(String name, int bytes) throws IOException {
        Path datei = verzeichnis.resolve(name);
        Files.write(datei, new byte[bytes]);
        return datei;
    }

    private static TextPdfPort textPdf() {
        return (kopf, text, ziel) -> {
            try {
                Files.write(ziel, new byte[] {1});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static final class TestDokumente extends DokumentErfassen {
        private final List<UploadAnfrage> uploads = new ArrayList<>();
        private final List<String> korrespondenzQuellen = new ArrayList<>();
        private final List<UUID> ids = new ArrayList<>();

        TestDokumente() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public List<UploadErgebnis> imKontextErfassen(UploadAnfrage anfrage) {
            uploads.add(anfrage);
            return anfrage.seiten().stream().map(seite -> {
                UUID id = UUID.randomUUID();
                ids.add(id);
                return (UploadErgebnis) new UploadErgebnis.Angenommen(seite.name() == null ? "synthetisch" : seite.name(),
                        id);
            }).toList();
        }

        @Override
        public UploadErgebnis korrespondenzErfassen(Path eml, Path textPdf, Optional<String> hinweis,
                                                    String erstelltVon, String quelle) {
            korrespondenzQuellen.add(quelle);
            UUID id = UUID.randomUUID();
            ids.add(id);
            return new UploadErgebnis.Angenommen("nachricht.eml", id);
        }
    }

    private static final class TestRegister implements MailEingangRegister {
        private final Map<String, MailStatus> eintraege = new HashMap<>();
        private final Map<String, List<UUID>> dokumente = new HashMap<>();
        private final List<MailHerkunft> herkunft = new java.util.ArrayList<>();

        @Override
        public Optional<MailStatus> status(MandantCode mandant, String messageId) {
            return Optional.ofNullable(eintraege.get(messageId));
        }

        @Override
        public boolean eintragen(MandantCode mandant, String messageId, Optional<String> absender,
                                 Optional<String> betreff, MailStatus status, MailHerkunft quelle) {
            herkunft.add(quelle);
            return eintraege.putIfAbsent(messageId, status) == null;
        }

        @Override
        public boolean ausQuarantaene(MandantCode mandant, String messageId) {
            return false;
        }

        @Override
        public void dokumenteSetzen(MandantCode mandant, String messageId, List<UUID> dokumente) {
            this.dokumente.put(messageId, List.copyOf(dokumente));
        }
    }
}
