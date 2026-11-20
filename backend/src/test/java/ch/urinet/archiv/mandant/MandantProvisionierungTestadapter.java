package ch.urinet.archiv.mandant;

import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.application.MandantSchemaPruefung;
import ch.urinet.archiv.mandant.application.SeedPruefung;
import ch.urinet.archiv.mandant.domain.Mandant;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Provisionierung ist ein Admin-Kommando ohne Request-Kontext. Tests rufen den Dienst oft aus einem aktiven
 * Quarkus-Testkontext auf; der Adapter führt ihn deshalb wie das Kommando auf einem frischen Thread aus, und zwar wie
 * das Kommando ({@code ArchivMain}) auf einem Plattform-Thread: Die Seed-Prüfung misst ihre Frist
 * ({@code archiv.dokumentart.seed-zeitgrenze}) dort als CPU-Zeit, in einem virtuellen Thread nur mit der Wanduhr, die
 * auch Warten auf CPU, GC-Pausen und Sperren zählt (instabile CI, «Seed ungültig für Mandant urinet», 20.11.2026).
 */
@Alternative
@Priority(1)
@Dependent
public class MandantProvisionierungTestadapter extends MandantProvisionierung {

    @Inject
    public MandantProvisionierungTestadapter(MandantRegister register, MandantDatenbank datenbank,
                                             MandantBucket bucket, KonfigurationSeed seed,
                                             KonfigurationsdateiVerwaltung konfiguration, MandantKontext kontext,
                                             MandantSchemaPruefung schemaPruefung, SeedPruefung seedPruefung,
                                             @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix) {
        super(register, datenbank, bucket, seed, konfiguration, kontext, schemaPruefung, seedPruefung, bucketPraefix);
    }

    @Override
    public Mandant anlegen(String code, String name, String geschaeftsjahresende) {
        return aufFrischemThread(() -> super.anlegen(code, name, geschaeftsjahresende));
    }

    /** Führt {@code aufgabe} auf einem frischen Plattform-Thread aus und gibt Fehler unverändert weiter. */
    public static <T> T aufFrischemThread(Callable<T> aufgabe) {
        FutureTask<T> lauf = new FutureTask<>(aufgabe);
        Thread.ofPlatform().name("mandant-provisionierung-test").start(lauf);
        try {
            return lauf.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mandanten-Provisionierung wurde unterbrochen", e);
        } catch (ExecutionException e) {
            Throwable ursache = e.getCause();
            if (ursache instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ursache instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Mandanten-Provisionierung fehlgeschlagen", ursache);
        }
    }
}
