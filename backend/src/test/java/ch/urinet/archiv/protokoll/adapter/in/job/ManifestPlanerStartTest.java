package ch.urinet.archiv.protokoll.adapter.in.job;

import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestEinreihen;
import ch.urinet.archiv.protokoll.application.ManifestPlanen;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestPlanerStartTest {

    @Test
    void startPlantJeMandantImTaktThreadUndFaengtMandantenfehlerAb() {
        List<Mandant> mandanten = List.of(mandant("eins"), mandant("zwei"));
        ThreadLocal<MandantCode> aktuell = new ThreadLocal<>();
        List<MandantCode> imKontext = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        List<Boolean> startFlags = new CopyOnWriteArrayList<>();
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return mandanten.stream().anyMatch(m -> m.code().equals(code));
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return mandanten;
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                return Optional.empty();
            }
        };
        MandantKontext kontext = new MandantKontext(null, null, null) {
            @Override
            public <T> T mit(MandantCode code, Supplier<T> arbeit) {
                aktuell.set(code);
                try {
                    return arbeit.get();
                } finally {
                    aktuell.remove();
                }
            }
        };
        ManifestEinreihen einreihen = new ManifestEinreihen(null, null, null) {
            @Override
            public Optional<Long> einreihen(MandantCode code, LocalDate gestern, Instant jetzt, boolean beimStart) {
                imKontext.add(aktuell.get());
                threads.add(Thread.currentThread().getName());
                startFlags.add(beimStart);
                if (code.equals(new MandantCode("eins"))) {
                    throw new IllegalStateException("Mandant konnte nicht eingeplant werden");
                }
                return Optional.empty();
            }
        };
        ManifestPlaner planer = new ManifestPlaner(
                new ManifestPlanen(register, kontext, einreihen), "api,worker", true);

        planer.start(new SchemasBereit());

        assertThat(imKontext).containsExactly(new MandantCode("eins"), new MandantCode("zwei"));
        assertThat(threads).containsExactly("manifest-planer", "manifest-planer");
        assertThat(startFlags).containsExactly(true, true);
    }

    private static Mandant mandant(String code) {
        MandantCode mandant = new MandantCode(code);
        return new Mandant(mandant, code, mandant.schema(), mandant.bucket("urinet-archiv-"), null, true);
    }
}
