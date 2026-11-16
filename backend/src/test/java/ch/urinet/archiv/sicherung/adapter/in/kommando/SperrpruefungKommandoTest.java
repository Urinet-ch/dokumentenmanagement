package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.sicherung.application.Sperrpruefung;
import ch.urinet.archiv.sicherung.application.SperrpruefungDaten;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SperrpruefungKommandoTest {

    @Test
    void parstMandantUndStichprobenbereich() {
        assertThat(SperrpruefungKommando.lesen(List.of("sperren-pruefen", "--mandant", "probe")))
                .contains(new SperrpruefungKommando.Aufruf(new MandantCode("probe"), 5));
        assertThat(SperrpruefungKommando.lesen(List.of("sperren-pruefen", "--mandant", "probe",
                "--stichprobe", "0"))).contains(new SperrpruefungKommando.Aufruf(new MandantCode("probe"), 0));
        assertThat(SperrpruefungKommando.lesen(List.of("sperren-pruefen", "--mandant", "probe",
                "--stichprobe", "50"))).contains(new SperrpruefungKommando.Aufruf(new MandantCode("probe"), 50));
        for (String wert : List.of("-1", "51", "text")) {
            assertThat(SperrpruefungKommando.lesen(List.of("sperren-pruefen", "--mandant", "probe",
                    "--stichprobe", wert))).isEmpty();
        }
    }

    @Test
    void falschesPraefixVerweigertVorMandantenUndBucketzugriff() {
        for (String praefix : List.of("urinet-archiv-", "urinet-archiv-a-")) {
            int[] bucketAufrufe = {0};
            int[] registerAufrufe = {0};
            var kommando = kommando(praefix, register(registerAufrufe, List.of(mandant())),
                    ablage(bucketAufrufe, List.of()));
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            ByteArrayOutputStream fehler = new ByteArrayOutputStream();

            int exit = kommando.ausfuehren(List.of("sperren-pruefen", "--mandant", "probe"),
                    aus(aus), aus(fehler));

            assertThat(exit).isEqualTo(2);
            assertThat(bucketAufrufe[0]).isZero();
            assertThat(registerAufrufe[0]).isZero();
            assertThat(aus.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }

    @Test
    void inaktiverMandantErgibtExitZweiOhneBucketzugriff() {
        int[] bucketAufrufe = {0};
        int[] registerAufrufe = {0};
        var kommando = kommando(Stufenregel.PRAEFIX_STUFE_P, register(registerAufrufe, List.of()),
                ablage(bucketAufrufe, List.of()));
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();

        int exit = kommando.ausfuehren(List.of("sperren-pruefen", "--mandant", "probe"),
                aus(aus), aus(fehler));

        assertThat(exit).isEqualTo(2);
        assertThat(registerAufrufe[0]).isEqualTo(1);
        assertThat(bucketAufrufe[0]).isZero();
    }

    @Test
    void jsonEnthaeltNurZaehlerUndIdsUndLiefertExitEinsBeiAbweichung() {
        int[] bucketAufrufe = {0};
        var kommando = kommando(Stufenregel.PRAEFIX_STUFE_P, register(new int[1], List.of(mandant())),
                ablage(bucketAufrufe, List.of(new Objektversion("archiv/synthetischer-schluessel", "v1", false))));
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();

        int exit = kommando.ausfuehren(List.of("sperren-pruefen", "--mandant", "probe", "--stichprobe", "0"),
                aus(aus), aus(fehler));

        String json = aus.toString(StandardCharsets.UTF_8);
        assertThat(exit).isEqualTo(1);
        assertThat(json).contains("\"OBJEKT_UEBERZAEHLIG\":1");
        assertThat(json).doesNotContain("synthetischer-schluessel", "v1");
        assertThat(bucketAufrufe[0]).isEqualTo(2);
    }

    private static SperrpruefungKommando kommando(String praefix, MandantRegister register, AblagePort ablage) {
        SperrpruefungDaten daten = new SperrpruefungDaten() {
            @Override
            public List<DokumentPrueflaufPort.Pruefdokument> dokumente(MandantCode code,
                                                                       Optional<java.util.UUID> nach, int anzahl) {
                return List.of();
            }

            @Override
            public List<Pruefmanifest> manifeste(MandantCode code, Optional<java.time.LocalDate> nach, int anzahl) {
                return List.of();
            }

            @Override
            public Set<DokumentPrueflaufPort.Bekannt> bekannteDokumente(MandantCode code,
                                                                        Collection<String> schluessel) {
                return Set.of();
            }

            @Override
            public Set<Version> bekannteManifeste(MandantCode code, Collection<Objektversion> versionen) {
                return Set.of();
            }
        };
        return new SperrpruefungKommando(new Sperrpruefung(ablage, daten), register, new ObjectMapper(), praefix,
                Path.of("target", "arbeit", "sperrpruefung-test"));
    }

    private static AblagePort ablage(int[] aufrufe, List<Objektversion> inventar) {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    if (methode.getName().equals("versionen")) {
                        aufrufe[0]++;
                        String praefix = (String) args[1];
                        return new Versionsseite(inventar.stream()
                                .filter(version -> version.schluessel().startsWith(praefix)).toList(), Optional.empty());
                    }
                    throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static MandantRegister register(int[] aufrufe, List<Mandant> aktive) {
        return new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                aufrufe[0]++;
                return aktive;
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Mandant mandant() {
        return new Mandant(new MandantCode("probe"), "Probe", "m_probe", "bucket",
                Geschaeftsjahresende.STANDARD, true);
    }

    private static PrintStream aus(ByteArrayOutputStream ziel) {
        return new PrintStream(ziel, true, StandardCharsets.UTF_8);
    }
}
