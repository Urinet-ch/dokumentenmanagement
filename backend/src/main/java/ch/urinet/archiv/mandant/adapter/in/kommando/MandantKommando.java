package ch.urinet.archiv.mandant.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantAbbau;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVollexport;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code archiv mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]} (ADR-002). Exit 0 = angelegt,
 * 1 = fachlicher Fehler ({@code MandantFehler}), 2 = falscher Aufruf, 3 = technischer Fehler (auch beim Start, siehe
 * {@code ArchivMain}). Die Ausgabe nennt nur Codes, Schema und Bucket.
 */
@ApplicationScoped
public class MandantKommando {

    public static final String AUFRUF = """
            Aufruf: archiv mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]
                   archiv mandant export <code>
                   archiv mandant export <code> --download <exportId>
                   archiv mandant abbauen <code> --nach-export <exportId> [--dry-run|--ausfuehren]
                       [--bestaetigt-urinet]""";

    private final MandantProvisionierung provisionierung;
    private final MandantKontext kontext;
    private final MandantVollexport vollexport;
    private final MandantAbbau abbau;

    public MandantKommando(MandantProvisionierung provisionierung, MandantKontext kontext,
                           MandantVollexport vollexport, MandantAbbau abbau) {
        this.provisionierung = provisionierung;
        this.kontext = kontext;
        this.vollexport = vollexport;
        this.abbau = abbau;
    }

    /** Gelesener Aufruf; {@code name} und {@code ende} sind optional. */
    public record Aufruf(String code, String name, String ende) {
    }

    public record ExportAufruf(String code, Optional<UUID> id) {
    }

    public record AbbauAufruf(String code, UUID exportId, boolean ausfuehren, boolean bestaetigtUrinet) {
    }

    public static boolean gueltig(List<String> argumente) {
        return lesen(argumente).isPresent() || exportLesen(argumente).isPresent()
                || abbauLesen(argumente).isPresent();
    }

    /** Prüft die Argumente nach {@code mandant}, ohne die Anwendung zu brauchen; leer bei falschem Aufruf. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2 || !argumente.getFirst().equals("anlegen")) {
            return Optional.empty();
        }
        String name = null;
        String ende = null;
        for (int i = 2; i < argumente.size(); i += 2) {
            if (i + 1 >= argumente.size()) {
                return Optional.empty();
            }
            switch (argumente.get(i)) {
                case "--name" -> name = argumente.get(i + 1);
                case "--geschaeftsjahr-ende" -> ende = argumente.get(i + 1);
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Aufruf(argumente.get(1), name, ende));
    }

    /** Liest den Exportauftrag oder die Abfrage eines fertigen Exports, ohne Datenbank- oder S3-Zugriff. */
    public static Optional<ExportAufruf> exportLesen(List<String> argumente) {
        if (argumente.size() == 2 && argumente.getFirst().equals("export")
                && !argumente.get(1).isBlank()) {
            return Optional.of(new ExportAufruf(argumente.get(1), Optional.empty()));
        }
        if (argumente.size() == 4 && argumente.getFirst().equals("export")
                && argumente.get(2).equals("--download") && !argumente.get(1).isBlank()
                && !argumente.get(3).isBlank()) {
            try {
                return Optional.of(new ExportAufruf(argumente.get(1), Optional.of(UUID.fromString(argumente.get(3)))));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Liest den Abbau-Aufruf; ohne --ausfuehren bleibt er ein Trockenlauf. */
    public static Optional<AbbauAufruf> abbauLesen(List<String> argumente) {
        if (argumente.size() < 4 || !argumente.getFirst().equals("abbauen")
                || !argumente.get(2).equals("--nach-export") || argumente.get(1).isBlank()
                || argumente.get(3).isBlank()) {
            return Optional.empty();
        }
        UUID exportId;
        try {
            new MandantCode(argumente.get(1));
            exportId = UUID.fromString(argumente.get(3));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean ausfuehren = false;
        boolean dryRun = false;
        boolean bestaetigtUrinet = false;
        for (String option : argumente.subList(4, argumente.size())) {
            switch (option) {
                case "--ausfuehren" -> {
                    if (ausfuehren) {
                        return Optional.empty();
                    }
                    ausfuehren = true;
                }
                case "--dry-run" -> {
                    if (dryRun) {
                        return Optional.empty();
                    }
                    dryRun = true;
                }
                case "--bestaetigt-urinet" -> {
                    if (bestaetigtUrinet) {
                        return Optional.empty();
                    }
                    bestaetigtUrinet = true;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (ausfuehren && dryRun || bestaetigtUrinet && !argumente.get(1).equals("urinet")) {
            return Optional.empty();
        }
        return Optional.of(new AbbauAufruf(argumente.get(1), exportId, ausfuehren, bestaetigtUrinet));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<AbbauAufruf> abbauAufruf = abbauLesen(argumente);
        if (abbauAufruf.isPresent()) {
            try {
                AbbauAufruf aufruf = abbauAufruf.get();
                MandantAbbau.Bericht bericht = abbau.ausfuehren(new MandantCode(aufruf.code()), aufruf.exportId(),
                        aufruf.ausfuehren(), aufruf.bestaetigtUrinet());
                aus.printf("Mandanten-Abbau: %s%nAbbau frühestens ab: %s%n"
                                + "Datenbank-Sicherungsstände spätestens bis: %s%n"
                                + "Dateien in der Zweitkopie: %s%n"
                                + "Löschbare Objekte: %d%nGelöschte Objekte: %d%n"
                                + "Schema entfernt: %s%nBucket entfernt: %s%n",
                        bericht.zustand(), bericht.abbauAb(), bericht.sicherungsstaendeBis(),
                        MandantAbbau.DATEI_SICHERUNG_AUFBEWAHRUNG, bericht.ablage().loeschbar(),
                        bericht.geloescht(), bericht.schemaEntfernt(), bericht.bucketEntfernt());
                bericht.ablage().gesperrt().forEach(objekt -> aus.printf(
                        "Gesperrt: %s (Version %s) bis %s%s%n", objekt.schluessel(), objekt.versionId(),
                        objekt.gesperrtBis().map(Object::toString).orElse("unbefristet"),
                        objekt.legalHold() ? " (Legal Hold)" : ""));
                return 0;
            } catch (MandantFehler e) {
                fehler.println(e.grund() + ": " + e.getMessage());
                return 1;
            } catch (RuntimeException e) {
                fehler.println("Technischer Fehler: " + e.getMessage());
                return 3;
            }
        }
        Optional<ExportAufruf> exportAufruf = exportLesen(argumente);
        if (exportAufruf.isPresent()) {
            try {
                ExportAufruf aufruf = exportAufruf.get();
                MandantCode code = new MandantCode(aufruf.code());
                if (aufruf.id().isEmpty()) {
                    UUID id = kontext.mit(code, vollexport::anfordern);
                    aus.printf("Vollexport eingereiht: %s%n", id);
                } else {
                    String url = kontext.mit(code,
                            () -> vollexport.herunterladen(code, aufruf.id().orElseThrow()).url().toString());
                    aus.println(url);
                }
                return 0;
            } catch (MandantFehler e) {
                fehler.println(e.grund() + ": " + e.getMessage());
                return 1;
            } catch (RuntimeException e) {
                fehler.println("Technischer Fehler: " + e.getMessage());
                return 3;
            }
        }
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Mandant mandant = provisionierung.anlegen(aufruf.get().code(), aufruf.get().name(), aufruf.get().ende());
            aus.printf("Mandant %s angelegt: Schema %s, Bucket %s, Geschäftsjahresende %s%n", mandant.code().wert(),
                    mandant.schema(), mandant.bucket(), mandant.geschaeftsjahresende().wert());
            return 0;
        } catch (MandantFehler e) {
            fehler.println(e.grund() + ": " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getMessage());
            return 3;
        }
    }
}
