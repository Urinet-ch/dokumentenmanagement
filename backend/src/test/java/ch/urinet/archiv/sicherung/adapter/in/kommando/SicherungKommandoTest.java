package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.application.SicherungExportVerwaltung;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SicherungKommandoTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void festhaltenLehntUngueltigeAufrufeMitExitZweiOhneSchreibvorgangAb() {
        int[] festgehalten = {0};
        SicherungExportSpeicher speicher = (art, mandant, sha256) -> festgehalten[0]++;
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(ch.urinet.archiv.mandant.domain.MandantCode code) {
                return false;
            }

            @Override
            public void eintragen(ch.urinet.archiv.mandant.domain.Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ch.urinet.archiv.mandant.domain.Mandant> aktive() {
                return List.of();
            }

            @Override
            public java.util.Optional<java.time.Instant> erstellt(
                    ch.urinet.archiv.mandant.domain.MandantCode code) {
                return java.util.Optional.empty();
            }
        };
        SicherungKommando kommando = new SicherungKommando(new SicherungBucketExport(ablage()),
                new SicherungExportVerwaltung(speicher, register), new ObjectMapper());

        assertThat(aufruf(kommando, "festhalten", "--art", "unbekannt", "--sha256", SHA)).isEqualTo(2);
        assertThat(aufruf(kommando, "festhalten", "--art", "db", "--sha256", "z".repeat(64))).isEqualTo(2);
        assertThat(aufruf(kommando, "festhalten", "--art", "db", "--mandant", "probe", "--sha256", SHA))
                .isEqualTo(2);
        assertThat(aufruf(kommando, "festhalten", "--art", "bucket", "--sha256", SHA)).isEqualTo(2);
        assertThat(aufruf(kommando, "festhalten", "--art", "bucket", "--mandant", "inaktiv", "--sha256", SHA))
                .isEqualTo(2);
        assertThat(festgehalten[0]).isZero();
    }

    @Test
    void sha256WirdKanonisiertUndEinGueltigerGlobalerExportFestgehalten() {
        int[] festgehalten = {0};
        String[] gespeicherteSha = {null};
        SicherungExportSpeicher speicher = (art, mandant, sha256) -> {
            festgehalten[0]++;
            gespeicherteSha[0] = sha256;
        };
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(ch.urinet.archiv.mandant.domain.MandantCode code) {
                return false;
            }

            @Override
            public void eintragen(ch.urinet.archiv.mandant.domain.Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ch.urinet.archiv.mandant.domain.Mandant> aktive() {
                return List.of();
            }

            @Override
            public java.util.Optional<java.time.Instant> erstellt(
                    ch.urinet.archiv.mandant.domain.MandantCode code) {
                return java.util.Optional.empty();
            }
        };
        SicherungKommando kommando = new SicherungKommando(new SicherungBucketExport(ablage()),
                new SicherungExportVerwaltung(speicher, register), new ObjectMapper());

        assertThat(aufruf(kommando, "festhalten", "--art", "db", "--sha256", "A".repeat(64))).isZero();

        assertThat(festgehalten[0]).isEqualTo(1);
        assertThat(gespeicherteSha[0]).isEqualTo(SHA);
    }

    private static int aufruf(SicherungKommando kommando, String... argumente) {
        return kommando.ausfuehren(List.of(argumente),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static AblagePort ablage() {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    throw new UnsupportedOperationException(methode.getName());
                });
    }
}
