package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.Typerkennung;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.Dateityp;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Anhang;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Nachricht;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Ziel;
import ch.urinet.archiv.integration.domain.mail.MailHinweis;
import ch.urinet.archiv.integration.domain.mail.MailKopf;
import ch.urinet.archiv.integration.domain.mail.MailHerkunft;
import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Erfasst eine Nachricht in einer Transaktion (Spec 02, Req 1.2–1.6; design.md «E-Mail-Eingang», «Umsetzung 6.1»),
 * im Mandanten-Kontext des Aufrufers. Message-ID, Absender und Betreff zuerst bereinigt ({@link MailKopf}), dann die
 * Zeile in {@code mail_eingang}: Ist die Message-ID schon eingetragen, entsteht nichts (Req 1.5), ausser die Mail lag in
 * der Quarantäne und ihr Absender ist inzwischen zugelassen. Ein nicht zugelassener Absender wird nur eingetragen
 * ({@code QUARANTAENE}, Req 1.4). Sonst
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
        String id = MailKopf.messageId(nachricht.messageId())
                .orElseThrow(() -> new IllegalArgumentException("Nachricht ohne Message-ID"));
        Optional<String> absender = MailKopf.wert(nachricht.absender(), MailKopf.MAX_ABSENDER);
        Optional<String> betreff = MailKopf.wert(nachricht.betreff(), MailKopf.MAX_BETREFF);
        String kurz = kurzform(id);
        boolean zugelassen = zulassung.zugelassen(nachricht.absender());
        Optional<MailStatus> vorhanden = register.status(mandant, id);
        if (vorhanden.isPresent()) {
            // eine Mail aus der Quarantäne, deren Absender inzwischen zugelassen ist, prüft das Archiv neu; erfasst wurde
            // zu ihr noch nichts, Req 1.5 bleibt gewahrt
            if (vorhanden.get() != MailStatus.QUARANTAENE || !zugelassen || !register.ausQuarantaene(mandant, id)) {
                LOG.infof("Mail schon verarbeitet: mandant=%s nachricht=%s status=%s", mandant.wert(), kurz,
                        vorhanden.get());
                return ziel(vorhanden.get());
            }
            LOG.infof("Mail aus der Quarantäne neu geprüft: mandant=%s nachricht=%s", mandant.wert(), kurz);
        } else {
            MailStatus status = zugelassen ? MailStatus.ERFASST : MailStatus.QUARANTAENE;
            if (!register.eintragen(mandant, id, absender, betreff, status, MailHerkunft.POSTFACH)) {
                // gleichzeitig von einem anderen Lauf eingetragen; der Status steht erst nach dessen Commit fest
                throw new IllegalStateException("Nachricht wird gleichzeitig verarbeitet");
            }
            if (status == MailStatus.QUARANTAENE) {
                LOG.infof("Mail in Quarantäne: mandant=%s nachricht=%s grund=ABSENDER", mandant.wert(), kurz);
                return Ziel.QUARANTAENE;
            }
        }
        String hinweis = MailHinweis.text(nachricht.absender(), nachricht.betreff(), nachricht.datum());
        List<UUID> neu = new ArrayList<>();
        int dubletten = 0;
        List<UploadAnfrage.Seite> seiten = nachricht.anhaenge().stream()
                .filter(a -> typerkennung.erkennen(a.datei()).isPresent())
                .map(a -> new UploadAnfrage.Seite(a.datei(), name(a), Optional.empty()))
                .toList();
        if (!seiten.isEmpty()) {
            for (UploadErgebnis ergebnis : dokumente.imKontextErfassen(new UploadAnfrage(seiten, false,
                    Optional.of(hinweis), EMAIL))) {
                switch (ergebnis) {
                    case UploadErgebnis.Angenommen a -> neu.add(a.id());
                    case UploadErgebnis.Dublette d -> dubletten++;
                    case UploadErgebnis.Abgelehnt a -> {
                        // Grund steht schon im Log des Uploads (mit Prüfsumme)
                    }
                }
            }
        }
        // Ein Anhang, der schon im Archiv liegt, ist erfasst (Spec 01, 3.4); die Nachricht wird darum nur dann
        // Korrespondenz, wenn kein Anhang angenommen wurde und keiner Dublette ist
        if (neu.isEmpty() && dubletten == 0) {
            korrespondenz(nachricht, hinweis).ifPresent(neu::add);
        }
        if (neu.isEmpty()) {
            LOG.warnf("Mail ohne neues Dokument: mandant=%s nachricht=%s dubletten=%d", mandant.wert(), kurz,
                    dubletten);
        }
        register.dokumenteSetzen(mandant, id, neu);
        LOG.infof("Mail erfasst: mandant=%s nachricht=%s anhaenge=%d unterstuetzt=%d dubletten=%d dokumente=%s",
                mandant.wert(), kurz, nachricht.anhaenge().size(), seiten.size(), dubletten, neu);
        return Ziel.ARCHIVIERT;
    }

    /**
     * Erfasst eine Gmail-Nachricht über denselben Dokumentpfad wie den E-Mail-Eingang. Die Message-ID ist mandantenweit
     * eindeutig; eine bereits bekannte Nachricht erzeugt nichts. Pro Anhang wird genau ein Zähler verbucht.
     */
    @Transactional
    public GmailErgebnis erfassenGmail(MandantCode mandant, Nachricht nachricht, boolean ohneAnhang) {
        String id = MailKopf.messageId(nachricht.messageId())
                .orElseThrow(() -> new IllegalArgumentException("Nachricht ohne Message-ID"));
        int gefunden = Math.max(1, nachricht.anhaenge().size());
        Optional<String> absender = MailKopf.wert(nachricht.absender(), MailKopf.MAX_ABSENDER);
        Optional<String> betreff = MailKopf.wert(nachricht.betreff(), MailKopf.MAX_BETREFF);
        String kurz = kurzform(id);
        if (register.status(mandant, id).isPresent()) {
            LOG.infof("Gmail-Import: Nachricht schon verarbeitet, mandant=%s nachricht=%s", mandant.wert(), kurz);
            return new GmailErgebnis(gefunden, 0, 0, gefunden, 0, List.of());
        }
        if (!register.eintragen(mandant, id, absender, betreff, MailStatus.ERFASST, MailHerkunft.GMAIL_LABEL)) {
            throw new IllegalStateException("Nachricht wird gleichzeitig verarbeitet");
        }

        String hinweis = MailHinweis.text(nachricht.absender(), nachricht.betreff(), nachricht.datum());
        List<UploadAnfrage.Seite> seiten = new ArrayList<>();
        int uebersprungen = 0;
        int fehler = 0;
        if (nachricht.anhaenge().isEmpty()) {
            if (ohneAnhang) {
                UploadErgebnis korrespondenz = korrespondenz(nachricht, hinweis,
                        DokumentErfassen.QUELLE_GMAIL);
                if (korrespondenz instanceof UploadErgebnis.Angenommen angenommen) {
                    register.dokumenteSetzen(mandant, id, List.of(angenommen.id()));
                    return new GmailErgebnis(1, 1, 0, 0, 0, List.of(angenommen.id()));
                }
                if (korrespondenz instanceof UploadErgebnis.Dublette) {
                    register.dokumenteSetzen(mandant, id, List.of());
                    return new GmailErgebnis(1, 0, 1, 0, 0, List.of());
                }
                register.dokumenteSetzen(mandant, id, List.of());
                return new GmailErgebnis(1, 0, 0, 0, 1, List.of());
            }
            uebersprungen = 1;
        } else {
            for (Anhang anhang : nachricht.anhaenge()) {
                Optional<Dateityp> typ = typerkennung.erkennen(anhang.datei());
                if (typ.isEmpty()) {
                    uebersprungen++;
                    continue;
                }
                if (typ.get() != Dateityp.PDF) {
                    try {
                        if (Files.size(anhang.datei()) < 20 * 1024) {
                            uebersprungen++;
                            continue;
                        }
                    } catch (IOException e) {
                        fehler++;
                        continue;
                    }
                }
                seiten.add(new UploadAnfrage.Seite(anhang.datei(), name(anhang), Optional.empty()));
            }
        }

        List<UUID> neu = new ArrayList<>();
        int dubletten = 0;
        if (!seiten.isEmpty()) {
            UploadAnfrage.Erfasser gmail = new UploadAnfrage.Erfasser("gmail-import", Optional.empty(),
                    DokumentErfassen.QUELLE_GMAIL);
            for (UploadErgebnis ergebnis : dokumente.imKontextErfassen(new UploadAnfrage(seiten, false,
                    Optional.of(hinweis), gmail))) {
                switch (ergebnis) {
                    case UploadErgebnis.Angenommen angenommen -> neu.add(angenommen.id());
                    case UploadErgebnis.Dublette ignored -> dubletten++;
                    case UploadErgebnis.Abgelehnt ignored -> fehler++;
                }
            }
        }
        register.dokumenteSetzen(mandant, id, neu);
        return new GmailErgebnis(gefunden, neu.size(), dubletten, uebersprungen, fehler, List.copyOf(neu));
    }

    public record GmailErgebnis(int gefunden, int neu, int dublettePruefsumme, int uebersprungen, int fehler,
                                List<UUID> dokumente) {
    }

    /** Req 1.3: die Nachricht als «Korrespondenz», {@code .eml} und PDF aus dem Text. */
    private Optional<UUID> korrespondenz(Nachricht nachricht, String hinweis) {
        UploadErgebnis ergebnis = korrespondenz(nachricht, hinweis, DokumentErfassen.QUELLE_EMAIL);
        return ergebnis instanceof UploadErgebnis.Angenommen angenommen ? Optional.of(angenommen.id())
                : Optional.empty();
    }

    private UploadErgebnis korrespondenz(Nachricht nachricht, String hinweis, String quelle) {
        Path datei = nachricht.eml().resolveSibling("text-" + UUID.randomUUID() + ".pdf");
        try {
            pdf.erzeugen(kopf(nachricht), nachricht.text(), datei);
            UploadErgebnis ergebnis = dokumente.korrespondenzErfassen(nachricht.eml(), datei, Optional.of(hinweis),
                    ERFASSER, quelle);
            return ergebnis;
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
        return MailKopf.sha256(messageId).substring(0, 12);
    }
}
