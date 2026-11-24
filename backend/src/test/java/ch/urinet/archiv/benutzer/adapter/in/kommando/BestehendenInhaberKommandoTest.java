package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BestehendenInhaberDokumentieren;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class BestehendenInhaberKommandoTest {
    private static final String ZIEL = "11111111-1111-4111-8111-111111111111";
    private static final String BETREIBER = "22222222-2222-4222-8222-222222222222";
    private static final List<String> AUFRUF = List.of(
            "inhaber-uebernehmen", "probe", "--benutzer", ZIEL, "--wer", BETREIBER);

    @Test
    void pruefungIstStandardUndAkzeptiertNurKanonischeKennungen() {
        assertThat(BestehendenInhaberKommando.lesen(AUFRUF)).get()
                .extracting(BestehendenInhaberKommando.Aufruf::schreiben).isEqualTo(false);
        assertThat(BestehendenInhaberKommando.lesen(
                List.of("inhaber-uebernehmen", "probe", "--benutzer", "1-1-1-1-1", "--wer", BETREIBER))).isEmpty();
        assertThat(BestehendenInhaberKommando.lesen(
                List.of("inhaber-uebernehmen", "probe", "--benutzer", ZIEL, "--wer", BETREIBER, "--ausfuehren")))
                .get().extracting(BestehendenInhaberKommando.Aufruf::schreiben).isEqualTo(true);
    }

    @Test
    void dienstfehlerErgebenDreiUndFachfehlerEins() {
        for (var code : List.of(Fehlercode.IDENTITAETSDIENST_FEHLER, Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR,
                Fehlercode.STATUS_KONFLIKT)) {
            var dienst = new BestehendenInhaberDokumentieren(null, null, null) {
                @Override
                public Ergebnis ausfuehren(MandantCode mandant, UUID ziel, UUID betreiber, boolean schreiben) {
                    throw new FachFehler(code);
                }
            };
            var kontext = new MandantKontext(null, null, null) {
                @Override
                public <T> T mit(MandantCode mandant, Supplier<T> arbeit) {
                    return arbeit.get();
                }
            };
            var aus = new ByteArrayOutputStream();
            var fehler = new ByteArrayOutputStream();
            int ergebnis = new BestehendenInhaberKommando(dienst, kontext)
                    .ausfuehren(AUFRUF, new PrintStream(aus), new PrintStream(fehler));
            assertThat(ergebnis).isEqualTo(code == Fehlercode.STATUS_KONFLIKT ? 1 : 3);
            assertThat(aus.toString()).isEmpty();
            assertThat(fehler.toString()).contains(code.name()).doesNotContain(ZIEL, BETREIBER);
        }
    }
}
