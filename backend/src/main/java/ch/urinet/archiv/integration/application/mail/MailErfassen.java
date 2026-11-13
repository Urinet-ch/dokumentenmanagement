package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.Typerkennung;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Anhang;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Nachricht;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Ziel;
import ch.urinet.archiv.integration.domain.mail.MailHinweis;
import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Erfasst eine Nachricht in einer Transaktion (Spec 02, Req 1.2–1.6; design.md «E-Mail-Eingang», «Umsetzung 6.1»),
 * im Mandanten-Kontext des Aufrufers. Zuerst die Zeile in {@code mail_eingang}: Ist die Message-ID schon eingetragen,
 * entsteht nichts (Req 1.5). Ein nicht zugelassener Absender wird nur eingetragen ({@code QUARANTAENE}, Req 1.4). Sonst
 * kommt jeder Anhang vom Typ PDF, JPEG, PNG, HEIC oder TIFF wie ein Upload als eigenes Dokument ins Archiv (Quelle
 * {@code EMAIL}, Dublettenprüfung, Dateiname nach Spec 01, 3.9, Hinweis mit Absender, Datum und Betreff; Req 1.2, 1.6).
 * Ohne angenommenen oder als Dublette vorhandenen Anhang wird die Nachricht selbst ein Dokument der Art «Korrespondenz»
 * mit {@code .eml} und PDF aus dem Text (Req 1.3). Nie direkt archiviert: Alle Dokumente gehen mit {@code VERARBEITEN}
 * in den Eingangskorb.
 * <p>
 * Das Log nennt nur Mandant, eine Kurzform der Message-ID (SHA-256) und Ergebnisse, nie Absender, Betreff oder Inhalt
 * (Req 16.7).
 */
@ApplicationScoped
public class MailErfassen {

    /** {@code erstellt_von} der Dokumente aus dem Mail-Eingang. */
    public static final String ERFASSER = "mail-eingang";

    private static final Logger LOG = Logger.getLogger(MailErfassen.class);
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Zurich"));
    private static final UploadAnfrage.Erfasser EMAIL = new UploadAnfrage.Erfasser(ERFASSER, Optional.empty(),
            DokumentErfassen.QUELLE_EMAIL);

    private final MailEingangRegister register;
    private final DokumentErfassen dokumente;
    private final Typerkennung typerkennung;
    private final TextPdfPort pdf;

    public MailErfassen(MailEingangRegister register, DokumentErfassen dokumente, Typerkennung typerkennung,
                        TextPdfPort pdf) {
        this.register = register;
        this.dokumente = dokumente;
        this.typerkennung = typerkennung;
        this.pdf = pdf;
    }

    @Transactional
    public Ziel erfassen(MandantCode mandant, Nachricht nachricht, Zulassungsliste zulassung) {
        String kurz = kurzform(nachricht.messageId());
        Optional<MailStatus> vorhanden = register.status(mandant, nachricht.messageId());
        if (vorhanden.isPresent()) {
            LOG.infof("Mail schon verarbeitet: mandant=%s nachricht=%s status=%s", mandant.wert(), kurz,
                    vorhanden.get());
            return ziel(vorhanden.get());
        }
        MailStatus status = zulassung.zugelassen(nachricht.absender()) ? MailStatus.ERFASST : MailStatus.QUARANTAENE;
        if (!register.eintragen(mandant, nachricht.messageId(), nachricht.absender(), nachricht.betreff(), status)) {
            // gleichzeitig von einem anderen Lauf eingetragen; der Status steht erst nach dessen Commit fest
            throw new IllegalStateException("Nachricht wird gleichzeitig verarbeitet");
        }
        if (status == MailStatus.QUARANTAENE) {
            LOG.infof("Mail in Quarantäne: mandant=%s nachricht=%s grund=ABSENDER", mandant.wert(), kurz);
            return Ziel.QUARANTAENE;
        }
        String hinweis = MailHinweis.text(nachricht.absender(), nachricht.betreff(), nachricht.datum());
        List<UUID> neu = new ArrayList<>();
        boolean erfasst = false;
        List<UploadAnfrage.Seite> seiten = nachricht.anhaenge().stream()
                .filter(a -> typerkennung.erkennen(a.datei()).isPresent())
                .map(a -> new UploadAnfrage.Seite(a.datei(), name(a), Optional.empty()))
                .toList();
        if (!seiten.isEmpty()) {
            for (UploadErgebnis ergebnis : dokumente.imKontextErfassen(new UploadAnfrage(seiten, false, Optional.of(hinweis),
                    EMAIL))) {
                switch (ergebnis) {
                    case UploadErgebnis.Angenommen a -> {
                        neu.add(a.id());
                        erfasst = true;
                    }
                    case UploadErgebnis.Dublette d -> erfasst = true;
                    case UploadErgebnis.Abgelehnt a -> {
                        // Grund steht schon im Log des Uploads (mit Prüfsumme)
                    }
                }
            }
        }
        if (!erfasst) {
            Optional<UUID> korrespondenz = korrespondenz(nachricht, hinweis);
            korrespondenz.ifPresent(neu::add);
            if (korrespondenz.isEmpty()) {
                // abgelehnt oder Dublette; der Grund steht mit der Prüfsumme im Log, die Mail bleibt in «Archiviert»
                LOG.warnf("Mail ohne neues Dokument: mandant=%s nachricht=%s", mandant.wert(), kurz);
            }
        }
        register.dokumenteSetzen(mandant, nachricht.messageId(), neu);
        LOG.infof("Mail erfasst: mandant=%s nachricht=%s anhaenge=%d unterstuetzt=%d dokumente=%s", mandant.wert(),
                kurz, nachricht.anhaenge().size(), seiten.size(), neu);
        return Ziel.ARCHIVIERT;
    }

    /** Req 1.3: die Nachricht als «Korrespondenz», {@code .eml} und PDF aus dem Text. */
    private Optional<UUID> korrespondenz(Nachricht nachricht, String hinweis) {
        Path datei = nachricht.eml().resolveSibling("text-" + UUID.randomUUID() + ".pdf");
        try {
            pdf.erzeugen(kopf(nachricht), nachricht.text(), datei);
            UploadErgebnis ergebnis = dokumente.korrespondenzErfassen(nachricht.eml(), datei, Optional.of(hinweis),
                    ERFASSER);
            return ergebnis instanceof UploadErgebnis.Angenommen a ? Optional.of(a.id()) : Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(datei);
            } catch (IOException e) {
                throw new UncheckedIOException("Text-PDF nicht gelöscht", e);
            }
        }
    }

    private static List<String> kopf(Nachricht nachricht) {
        List<String> kopf = new ArrayList<>();
        nachricht.absender().ifPresent(a -> kopf.add("Von: " + a));
        nachricht.datum().ifPresent(d -> kopf.add("Datum: " + DATUM.format(d)));
        nachricht.betreff().ifPresent(b -> kopf.add("Betreff: " + b));
        return kopf;
    }

    /** Ein ungültiger Name lehnt den Anhang nicht ab, er bleibt nur leer (wie beim Generator, Spec 01, Req 11.12). */
    private static String name(Anhang anhang) {
        return anhang.name().filter(n -> DateiName.aus(n).gueltig()).orElse(null);
    }

    private static Ziel ziel(MailStatus status) {
        return status == MailStatus.QUARANTAENE ? Ziel.QUARANTAENE : Ziel.ARCHIVIERT;
    }

    /** Kurzform der Message-ID fürs Log: die ersten 12 Zeichen ihrer SHA-256. */
    static String kurzform(String messageId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
