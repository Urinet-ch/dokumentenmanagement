package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.dokument.adapter.out.persistence.PanacheDokumentAenderung;
import ch.urinet.archiv.dokument.application.DokumentKlassifikationenPort;
import ch.urinet.archiv.konfiguration.application.KonfigurationVersionsPort;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Haken für {@link SperrkonfliktAbbildungTest} (nur dort aktiv): ruft den echten {@link PanacheDokumentAenderung} auf
 * und legt nur die Reihenfolge fest. Das Zusammenführen hält seine Kontaktsperren, bis das Ändern das Dokument
 * gesperrt hat; erst dann sperrt es die Dokumente. Ohne scharf gestellte Riegel (alle anderen Tests) ändert der Haken
 * nichts.
 */
@Alternative
@Dependent
public class SperrReihenfolgeHaken extends PanacheDokumentAenderung {

    private static volatile CountDownLatch kontakteGesperrt;
    private static volatile CountDownLatch dokumentGesperrt;

    @Inject
    public SperrReihenfolgeHaken(KonfigurationVersionsPort konfiguration,
                                 DokumentKlassifikationenPort klassifikationen) {
        super(konfiguration, klassifikationen);
    }

    /** Stellt die Riegel für einen Lauf scharf. */
    static void scharfStellen() {
        kontakteGesperrt = new CountDownLatch(1);
        dokumentGesperrt = new CountDownLatch(1);
    }

    static void entschaerfen() {
        kontakteGesperrt = null;
        dokumentGesperrt = null;
    }

    /** Zusammenführen: Kontakte sind gesperrt, das Dokument noch nicht. */
    @Override
    public List<UUID> partnerUmhaengen(UUID quelle, UUID ziel, String benutzer, String grund) {
        CountDownLatch eigen = kontakteGesperrt;
        CountDownLatch fremd = dokumentGesperrt;
        if (eigen != null && fremd != null) {
            eigen.countDown();
            warten(fremd);
        }
        return super.partnerUmhaengen(quelle, ziel, benutzer, grund);
    }

    /** Ändern: erst nach den Kontaktsperren des Zusammenführens, dann das Dokument sperren und weiter zum Kontakt. */
    @Override
    public Optional<Metadaten> ladenUndSperren(UUID id) {
        CountDownLatch fremd = kontakteGesperrt;
        CountDownLatch eigen = dokumentGesperrt;
        if (fremd != null && eigen != null) {
            warten(fremd);
        }
        Optional<Metadaten> gesperrt = super.ladenUndSperren(id);
        if (eigen != null) {
            eigen.countDown();
        }
        return gesperrt;
    }

    private static void warten(CountDownLatch riegel) {
        try {
            if (!riegel.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Die parallele Anfrage hat ihre Sperre nicht erreicht");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }
}
