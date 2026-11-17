package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.MandantAblageAbbau;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deaktiviert einen Mandanten nach bestätigtem Vollexport und baut ihn nach 30 Tagen kontrolliert ab. */
@ApplicationScoped
public class MandantAbbau {

    static final Duration FRIST = Duration.ofDays(30);
    static final int SICHERUNGSJAHRE = 10;
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    public static final String DATEI_SICHERUNG_AUFBEWAHRUNG =
            "unbefristet, bis die Spec zur Löschfunktion (Spec 01, 9.9) einen Weg regelt";

    public enum Zustand {
        TROCKENLAUF, DEAKTIVIERT, WARTEFRIST, ABBAU_UNVOLLSTAENDIG, ABGESCHLOSSEN
    }

    public record Bericht(Zustand zustand, Instant abbauAb, LocalDate sicherungsstaendeBis, int geloescht,
                          boolean schemaEntfernt, boolean bucketEntfernt,
                          MandantAblageAbbau ablage) {
    }

    private final MandantAbbauRegister register;
    private final MandantVollexportRepository exporte;
    private final MandantKontext kontext;
    private final MandantDatenbank datenbank;
    private final MandantBucket bucket;
    private final AblagePort ablage;
    private final Clock uhr;

    @Inject
    public MandantAbbau(MandantAbbauRegister register, MandantVollexportRepository exporte, MandantKontext kontext,
                        MandantDatenbank datenbank, MandantBucket bucket, AblagePort ablage) {
        this(register, exporte, kontext, datenbank, bucket, ablage, Clock.systemUTC());
    }

    MandantAbbau(MandantAbbauRegister register, MandantVollexportRepository exporte, MandantKontext kontext,
                 MandantDatenbank datenbank, MandantBucket bucket, AblagePort ablage, Clock uhr) {
        this.register = Objects.requireNonNull(register, "register");
        this.exporte = Objects.requireNonNull(exporte, "exporte");
        this.kontext = Objects.requireNonNull(kontext, "kontext");
        this.datenbank = Objects.requireNonNull(datenbank, "datenbank");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.ablage = Objects.requireNonNull(ablage, "ablage");
        this.uhr = Objects.requireNonNull(uhr, "uhr");
    }

    public Bericht ausfuehren(MandantCode code, UUID bestaetigterExport, boolean ausfuehren,
                              boolean zweiteBestaetigungUrinet) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(bestaetigterExport, "bestaetigterExport");
        if (code.wert().equals("urinet") && !zweiteBestaetigungUrinet) {
            throw fehler("Für den Mandanten urinet ist --bestaetigt-urinet erforderlich");
        }

        Instant jetzt = uhr.instant();
        MandantAbbauRegister.Status status = register.status(code)
                .orElseThrow(() -> fehler("Mandant nicht gefunden: " + code.wert()));
        bestaetigtenExportPruefen(code, bestaetigterExport, status);
        Instant deaktiviertAm = status.deaktiviertAm().orElse(jetzt);
        Instant abbauAb = deaktiviertAm.plus(FRIST);
        LocalDate sicherungsstaendeBis = sicherungsstaendeBis(abbauAb);

        if (status.abgeschlossen()) {
            return new Bericht(Zustand.ABGESCHLOSSEN, abbauAb, sicherungsstaendeBis, 0,
                    true, true, new MandantAblageAbbau(0, List.of()));
        }
        if (!ausfuehren) {
            MandantAblageAbbau vorschau = ablage.mandantAbbauPlanen(code, jetzt);
            return new Bericht(Zustand.TROCKENLAUF, abbauAb, sicherungsstaendeBis, 0,
                    !datenbank.schemaVorhanden(status.mandant().schema()), false,
                    vorschau);
        }
        if (status.deaktiviertAm().isEmpty()) {
            if (!register.deaktivieren(code, bestaetigterExport, jetzt)) {
                throw fehler("Mandant konnte nicht deaktiviert werden: " + code.wert());
            }
            return new Bericht(Zustand.DEAKTIVIERT, abbauAb, sicherungsstaendeBis, 0,
                    false, false, new MandantAblageAbbau(0, List.of()));
        }
        if (jetzt.isBefore(abbauAb)) {
            return new Bericht(Zustand.WARTEFRIST, abbauAb, sicherungsstaendeBis, 0,
                    !datenbank.schemaVorhanden(status.mandant().schema()), false,
                    new MandantAblageAbbau(0, List.of()));
        }

        MandantAblageAbbau bereinigung = ablage.mandantAbbauen(code, jetzt);
        if (datenbank.schemaVorhanden(status.mandant().schema())) {
            datenbank.schemaEntfernen(status.mandant().schema());
        }
        boolean bucketEntfernt = bucket.entfernenWennLeer(status.mandant().bucket());
        boolean schemaEntfernt = !datenbank.schemaVorhanden(status.mandant().schema());
        boolean abgeschlossen = bereinigung.gesperrt().isEmpty() && schemaEntfernt && bucketEntfernt;
        if (abgeschlossen && !register.abschliessen(code)) {
            throw new IllegalStateException("Mandanten-Abbau konnte nicht abgeschlossen werden");
        }
        Zustand zustand = abgeschlossen ? Zustand.ABGESCHLOSSEN : Zustand.ABBAU_UNVOLLSTAENDIG;
        return new Bericht(zustand, abbauAb, sicherungsstaendeBis, bereinigung.geloescht(),
                schemaEntfernt, bucketEntfernt, bereinigung);
    }

    /**
     * Spätestens bis wann Sicherungsstände Daten des Mandanten enthalten: der letzte Stand entsteht am Löschtag
     * ({@code abbauAb}); Jahresstände laufen 10 Jahre (Spec 01, 16.2), Tages- und Monatsstände kürzer.
     */
    public static LocalDate sicherungsstaendeBis(Instant abbauAb) {
        return abbauAb.atZone(ZUERICH).toLocalDate().plusYears(SICHERUNGSJAHRE);
    }

    private void bestaetigtenExportPruefen(MandantCode code, UUID exportId, MandantAbbauRegister.Status status) {
        Optional<UUID> bereitsBestaetigt = status.vollexportBestaetigt();
        if (bereitsBestaetigt.isPresent()) {
            if (!bereitsBestaetigt.get().equals(exportId)) {
                throw fehler("Bestätigte Vollexport-ID stimmt nicht mit dem Mandantenregister überein");
            }
            return;
        }
        if (!status.mandant().aktiv()) {
            throw fehler("Inaktiver Mandant ohne gespeicherte Vollexport-Bestätigung");
        }
        Optional<MandantVollexportRepository.Vollexport> export = kontext.mit(code, () -> exporte.laden(exportId));
        if (export.filter(e -> e.status() == MandantVollexportRepository.Status.FERTIG).isEmpty()) {
            throw fehler("Vollexport fehlt oder ist nicht abgeschlossen");
        }
        exportAktuellPruefen(code, export.get());
    }

    /**
     * Nach der Deaktivierung ist der Mandant für die Datenbank nicht mehr auflösbar und schreibgesperrt; darum
     * muss der Export jetzt dem Ist-Stand entsprechen und das ZIP noch vorhanden sein, sonst wären später
     * Dokumente ohne Kopie weg.
     */
    private void exportAktuellPruefen(MandantCode code, MandantVollexportRepository.Vollexport export) {
        MandantVollexportRepository.Bestand bestand = kontext.mit(code, () -> exporte.bestand());
        if (export.anzahl().isEmpty() || export.anzahl().get() != bestand.dokumente()) {
            throw fehler("Vollexport veraltet: Dokumentanzahl weicht vom Ist-Stand ab, neuen Vollexport erstellen");
        }
        if (bestand.letzteAenderung().filter(z -> z.isAfter(export.erstellt())).isPresent()) {
            throw fehler("Vollexport veraltet: Dokumente wurden danach geändert, neuen Vollexport erstellen");
        }
        boolean zipDa = export.schluessel().isPresent() && export.versionId().isPresent()
                && export.groesse().isPresent()
                && ablage.exportVorhanden(code, export.schluessel().get(), export.versionId().get(),
                export.groesse().get());
        if (!zipDa) {
            throw fehler("Vollexport-ZIP fehlt in der Ablage (abgelaufen?), neuen Vollexport erstellen");
        }
    }

    private static MandantFehler fehler(String meldung) {
        return new MandantFehler(MandantFehler.Grund.ABBAU_UNGUELTIG, meldung);
    }
}
