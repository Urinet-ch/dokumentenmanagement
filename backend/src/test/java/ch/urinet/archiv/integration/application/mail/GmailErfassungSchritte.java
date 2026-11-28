package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.Typerkennung;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.Dateityp;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.integration.domain.mail.MailHerkunft;
import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Szenarioschritte für die Gmail-Nachrichtenerfassung; die Dateien und Inhalte sind synthetisch. */
public class GmailErfassungSchritte {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final String MESSAGE_ID = "<synthetic-cucumber@example.invalid>";

    Path verzeichnis;

    private TestRegister register;
    private TestDokumente dokumente;
    private PostfachPort.Nachricht nachricht;
    private Typerkennung typerkennung;
    private MailErfassen.GmailErgebnis ergebnis;
    private MailErfassen.GmailErgebnis korrespondenzErgebnis;
    private TestDokumente ohneKorrespondenzDokumente;
    private TestDokumente korrespondenzDokumente;

    @Angenommen("eine Gmail-Nachricht mit unterstützten und nicht unterstützten Anhängen vorliegt")
    public void nachrichtMitAnhaengen() throws IOException {
        neuesVerzeichnis();
        register = new TestRegister();
        dokumente = new TestDokumente();
        Path pdf = datei("synthetisch.pdf", 500);
        Path klein = datei("klein.jpg", 20 * 1024 - 1);
        Path jpeg = datei("bild.jpg", 20 * 1024);
        Path png = datei("bild.png", 20 * 1024);
        Path heic = datei("bild.heic", 20 * 1024);
        Path tiff = datei("bild.tif", 20 * 1024);
        Path unbekannt = datei("unbekannt.bin", 500);
        Map<Path, Dateityp> typen = Map.of(pdf, Dateityp.PDF, klein, Dateityp.JPEG, jpeg, Dateityp.JPEG,
                png, Dateityp.PNG, heic, Dateityp.HEIC, tiff, Dateityp.TIFF);
        typerkennung = datei -> Optional.ofNullable(typen.get(datei));
        nachricht = new PostfachPort.Nachricht(MESSAGE_ID,
                Optional.of("from@example.invalid"), Optional.of("Synthetische Rechnung"),
                Optional.of(Instant.parse("2026-01-02T03:04:00Z")), datei("nachricht.eml", 200),
                List.of(new PostfachPort.Anhang(pdf, Optional.empty()),
                        new PostfachPort.Anhang(klein, Optional.empty()),
                        new PostfachPort.Anhang(jpeg, Optional.empty()),
                        new PostfachPort.Anhang(png, Optional.empty()),
                        new PostfachPort.Anhang(heic, Optional.empty()),
                        new PostfachPort.Anhang(tiff, Optional.empty()),
                        new PostfachPort.Anhang(unbekannt, Optional.empty())), "synthetic");
    }

    @Angenommen("eine Gmail-Nachricht ohne Anhänge vorliegt")
    public void nachrichtOhneAnhaenge() throws IOException {
        neuesVerzeichnis();
        register = new TestRegister();
        dokumente = new TestDokumente();
        typerkennung = datei -> Optional.empty();
        nachricht = new PostfachPort.Nachricht(MESSAGE_ID, Optional.empty(),
                Optional.empty(), Optional.empty(), datei("nachricht.eml", 100), List.of(), "Synthetischer Text");
    }

    @Angenommen("eine Message-ID ist bereits im E-Mail-Eingang erfasst")
    public void messageIdBereitsErfasst() {
        neuesVerzeichnis();
        register = new TestRegister();
        register.eintraege.put(MESSAGE_ID, MailStatus.ERFASST);
        dokumente = new TestDokumente();
        typerkennung = datei -> Optional.empty();
        nachricht = nachrichtOhneAnhaengeInhalt();
    }

    @Wenn("das Archiv dieselbe Gmail-Nachricht erneut erfasst")
    public void erneutErfassen() {
        erfassen(false);
        erfassen(false);
    }

    @Wenn("das Archiv die Gmail-Nachricht erfasst")
    public void erfassen() {
        erfassen(false);
    }

    @Wenn("das Archiv die Gmail-Nachricht ohne Anhang mit beiden Optionen erfasst")
    public void ohneAnhangErfassen() {
        erfassen(false);
        ohneKorrespondenzDokumente = dokumente;
        register = new TestRegister();
        dokumente = new TestDokumente();
        korrespondenzErgebnis = new MailErfassen(register, dokumente, typerkennung, textPdf())
                .erfassenGmail(MANDANT, nachricht, true);
        korrespondenzDokumente = dokumente;
    }

    @Dann("werden nur unterstützte Dateien einzeln erfasst und kleine Bilder übersprungen")
    public void anhaengeGeprueft() {
        assertThat(ergebnis).isEqualTo(new MailErfassen.GmailErgebnis(7, 5, 0, 2, 0, dokumente.ids));
        assertThat(dokumente.uploads).hasSize(1);
        UploadAnfrage upload = dokumente.uploads.getFirst();
        assertThat(upload.seiten()).extracting(UploadAnfrage.Seite::original)
                .containsExactly(verzeichnis.resolve("synthetisch.pdf"), verzeichnis.resolve("bild.jpg"),
                        verzeichnis.resolve("bild.png"), verzeichnis.resolve("bild.heic"),
                        verzeichnis.resolve("bild.tif"));
        assertThat(upload.erfasser().quelle()).isEqualTo(DokumentErfassen.QUELLE_GMAIL);
        assertThat(upload.hinweis()).hasValueSatisfying(hinweis -> assertThat(hinweis)
                .contains("from@example.invalid").contains("Datum: 02.01.2026 04:04")
                .contains("Synthetische Rechnung"));
    }

    @Dann("wird die Nachricht ohne Anhang nur mit aktivierter Option als Korrespondenz erfasst")
    public void korrespondenzGeprueft() {
        assertThat(ergebnis).isEqualTo(new MailErfassen.GmailErgebnis(1, 0, 0, 1, 0, List.of()));
        assertThat(ohneKorrespondenzDokumente.korrespondenzQuellen).isEmpty();
        assertThat(korrespondenzErgebnis.neu()).isEqualTo(1);
        assertThat(korrespondenzDokumente.korrespondenzQuellen).containsExactly(DokumentErfassen.QUELLE_GMAIL);
    }

    @Dann("erzeugt die wiederholte oder bereits bekannte Message-ID keine neuen Dokumente")
    public void messageIdGeprueft() {
        assertThat(ergebnis.neu()).isZero();
        assertThat(dokumente.uploads).isEmpty();
        assertThat(register.herkunft).isEmpty();
    }

    @After("@gmail-verarbeitung")
    public void aufraeumen() throws IOException {
        if (verzeichnis != null && Files.exists(verzeichnis)) {
            try (var dateien = Files.walk(verzeichnis)) {
                for (Path datei : dateien.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(datei);
                }
            }
        }
    }

    private void erfassen(boolean ohneAnhang) {
        ergebnis = new MailErfassen(register, dokumente, typerkennung, textPdf())
                .erfassenGmail(MANDANT, nachricht, ohneAnhang);
    }

    private void neuesVerzeichnis() {
        try {
            verzeichnis = Files.createTempDirectory("gmail-szenario-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path datei(String name, int bytes) throws IOException {
        Path datei = verzeichnis.resolve(name);
        Files.write(datei, new byte[bytes]);
        return datei;
    }

    private static PostfachPort.Nachricht nachrichtOhneAnhaengeInhalt() {
        return new PostfachPort.Nachricht(MESSAGE_ID, Optional.empty(), Optional.empty(), Optional.empty(),
                Path.of("synthetische-nachricht.eml"), List.of(), "");
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

    private static final class TestRegister implements MailEingangRegister {
        private final Map<String, MailStatus> eintraege = new HashMap<>();
        private final List<MailHerkunft> herkunft = new ArrayList<>();

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
        }
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
                String name = Optional.ofNullable(seite.name()).filter(n -> DateiName.aus(n).gueltig())
                        .orElse("synthetisch");
                return (UploadErgebnis) new UploadErgebnis.Angenommen(name, id);
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
}
