package ch.urinet.archiv.gemeinsam.adapter.in.start;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Arbeitsverzeichnis beim Start (design.md «Betrieb auf der VPS», «Arbeitsverzeichnis und Plattenplatz»): legt
 * {@code archiv.arbeit.verzeichnis} an und bricht den Start ab, wenn es nicht anlegbar oder nicht beschreibbar ist.
 * Danach löscht es Reste abgebrochener Läufe (Uploads, OCR, Zwischenkopien der Ablage), die älter als
 * {@code archiv.arbeit.rest-alter} sind. Jüngere Einträge bleiben, weil ein anderer Prozess (z. B. der Vorgänger beim
 * Deploy) sie noch nutzen kann. Das Log nennt nur Anzahlen, nie Namen.
 */
@ApplicationScoped
public class ArbeitsverzeichnisAufraeumen {

    private static final Logger LOG = Logger.getLogger(ArbeitsverzeichnisAufraeumen.class);

    /** Ergebnis eines Laufs: gelöschte Einträge und solche, die nicht löschbar waren. */
    record Ergebnis(int geloescht, int fehler) {
    }

    void beimStart(@Observes StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.arbeit.verzeichnis") Path verzeichnis,
                   @ConfigProperty(name = "archiv.arbeit.rest-alter") Duration restAlter) {
        if (restAlter.isNegative() || restAlter.isZero()) {
            throw new IllegalStateException("archiv.arbeit.rest-alter muss positiv sein");
        }
        bereitstellen(verzeichnis);
        Ergebnis ergebnis = aufraeumen(verzeichnis, Clock.systemUTC().instant().minus(restAlter));
        if (ergebnis.geloescht() > 0 || ergebnis.fehler() > 0) {
            LOG.infof("Arbeitsverzeichnis aufgeräumt: %d Reste gelöscht, %d nicht löschbar", ergebnis.geloescht(),
                    ergebnis.fehler());
        }
    }

    /** Legt das Verzeichnis an und prüft, dass es beschreibbar ist; sonst bricht der Start ab. */
    static void bereitstellen(Path verzeichnis) {
        try {
            Files.createDirectories(verzeichnis);
            Files.delete(Files.createTempFile(verzeichnis, ".probe-", ".tmp"));
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("archiv.arbeit.verzeichnis ist nicht anlegbar oder nicht beschreibbar ("
                    + e.getClass().getSimpleName() + "). Volume und Rechte prüfen (uid 10001).", e);
        }
    }

    /**
     * Löscht unter {@code wurzel} jede Datei, die vor {@code grenze} zuletzt geändert wurde, und jedes danach leere
     * Unterverzeichnis ab der zweiten Ebene mit Änderung vor {@code grenze}. Die Wurzel und ihre direkten
     * Unterverzeichnisse ({@code upload}, {@code ocr}, {@code ablage}) bleiben. Symbolische Links werden nicht verfolgt,
     * sondern selbst als Eintrag behandelt.
     */
    static Ergebnis aufraeumen(Path wurzel, Instant grenze) {
        int[] zaehler = new int[2];
        // Änderungszeit vor dem Besuch: Das Löschen der Einträge setzt sie auf jetzt
        Deque<Boolean> alt = new ArrayDeque<>();
        try {
            Files.walkFileTree(wurzel, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path ordner, BasicFileAttributes attribute) {
                    alt.push(attribute.lastModifiedTime().toInstant().isBefore(grenze));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) {
                    if (attribute.lastModifiedTime().toInstant().isBefore(grenze)) {
                        loeschen(datei, zaehler);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path datei, IOException fehler) {
                    zaehler[1]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path ordner, IOException fehler) {
                    boolean warAlt = alt.pop();
                    if (fehler != null) {
                        zaehler[1]++;
                    } else if (warAlt && wurzel.relativize(ordner).getNameCount() >= 2 && leer(ordner)) {
                        loeschen(ordner, zaehler);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Arbeitsverzeichnis nicht lesbar", e);
        }
        return new Ergebnis(zaehler[0], zaehler[1]);
    }

    private static boolean leer(Path ordner) {
        try (var eintraege = Files.list(ordner)) {
            return eintraege.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static void loeschen(Path eintrag, int[] zaehler) {
        try {
            Files.deleteIfExists(eintrag);
            zaehler[0]++;
        } catch (IOException e) {
            zaehler[1]++;
        }
    }
}
