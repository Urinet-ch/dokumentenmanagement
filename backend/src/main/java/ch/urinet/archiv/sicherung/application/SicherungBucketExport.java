package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Stromweiser Export der versionierten Bucket-Inhalte und des dazugehörigen Sperrinventars. */
@ApplicationScoped
public class SicherungBucketExport {

    public static final String INVENTAR_FORMAT = "urinet-sicherungsinventar-v1";
    public static final String BUCKET_FORMAT = "URINET-BUCKET-EXPORT-V1\n";
    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;
    private static final int SEITENGROESSE = 1000;
    private static final List<String> PRAEFIXE = List.of("archiv/", "eingang/", "manifest/");

    private final AblagePort ablage;

    public SicherungBucketExport(AblagePort ablage) {
        this.ablage = ablage;
    }

    public record InventarZeile(String format, String schluessel, String versionId, boolean loeschmarke, long groesse,
                                String lastModified, String sha256, String sperrmodus, String retainUntil) {
    }

    public void inventar(MandantCode mandant, Consumer<InventarZeile> ausgabe) {
        for (String praefix : PRAEFIXE) {
            forEachVersion(mandant, praefix, version -> {
                Optional<SicherungsSperre> sperre = ablage.sicherungsSperre(mandant, version);
                ausgabe.accept(new InventarZeile(INVENTAR_FORMAT, version.schluessel(), version.versionId(),
                        version.loeschmarke(), version.groesse(), version.lastModified().toString(), "",
                        sperre.map(SicherungsSperre::modus).orElse(null),
                        sperre.map(s -> s.retainUntil().toString()).orElse(null)));
            });
        }
    }

    /**
     * Schreibt ein konkatenierbares Binärformat: Kennung, dann je Objekt ein längenpräfixierter Schlüssel und eine
     * Versions-ID, Byte-Grösse, lastModified als Epoch-Millis, die Objektbytes und ein SHA256-Feld.
     */
    public void zuwachs(MandantCode mandant, Predicate<Objektversion> neu, OutputStream ziel) {
        DataOutputStream aus = new DataOutputStream(ziel);
        try {
            aus.write(BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            for (String praefix : PRAEFIXE) {
                forEachVersion(mandant, praefix, version -> {
                    if (!neu.test(version) || version.loeschmarke()) {
                        return;
                    }
                    try {
                        aus.writeInt(OBJEKT);
                        text(aus, version.schluessel());
                        text(aus, version.versionId());
                        aus.writeLong(version.groesse());
                        aus.writeLong(version.lastModified().toEpochMilli());
                        Kopie kopie = ablage.sicherungsKopie(mandant, version.schluessel(), version.versionId(), aus);
                        if (kopie.groesse() != version.groesse()) {
                            throw new IllegalStateException("Objektgrösse weicht von der Versionsliste ab");
                        }
                        aus.writeInt(SHA256);
                        aus.write(HexFormat.of().parseHex(kopie.sha256()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            aus.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ordnet nach Schlüssel, dann lastModified absteigend. Die Reihenfolge gleicher Zeitstempel liefert der Object
     * Storage und ist über Seitengrenzen nicht gesichert; Versionen mit gleichem Schlüssel und gleicher Zeit gelten
     * als eine Gruppe und werden über die Versions-ID zugeordnet.
     */
    public static int vergleichen(Objektversion aktuell, InventarZeile vorher) {
        int schluessel = aktuell.schluessel().compareTo(vorher.schluessel());
        if (schluessel != 0) {
            return schluessel;
        }
        return Instant.parse(vorher.lastModified()).compareTo(aktuell.lastModified());
    }

    public static int vergleichen(InventarZeile vorher, InventarZeile nachher) {
        int schluessel = vorher.schluessel().compareTo(nachher.schluessel());
        if (schluessel != 0) {
            return schluessel;
        }
        return Instant.parse(nachher.lastModified()).compareTo(Instant.parse(vorher.lastModified()));
    }

    private void forEachVersion(MandantCode mandant, String praefix, Consumer<Objektversion> aktion) {
        Optional<Versionsseite.Marke> marke = Optional.empty();
        do {
            Versionsseite seite = ablage.versionen(mandant, praefix, marke, SEITENGROESSE);
            seite.versionen().forEach(aktion);
            marke = seite.weiter();
        } while (marke.isPresent());
    }

    private static void text(DataOutputStream aus, String text) throws IOException {
        byte[] daten = text.getBytes(StandardCharsets.UTF_8);
        aus.writeInt(daten.length);
        aus.write(daten);
    }
}
