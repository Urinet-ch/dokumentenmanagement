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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SicherungKommandoTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void festhaltenLehntUngueltigeAufrufeMitExitZweiOhneSchreibvorgangAb() {
        int[] festgehalten = {0};
        SicherungExportSpeicher speicher = speicher((art, mandant, sha256) -> festgehalten[0]++);
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
        SicherungExportSpeicher speicher = speicher((art, mandant, sha256) -> {
            festgehalten[0]++;
            gespeicherteSha[0] = sha256;
        });
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

    /** Bricht der Abnehmer ab, endet der Zuwachs beim ersten Schreibfehler und nicht erst nach dem ganzen Bucket. */
    @Test
    void zuwachsBrichtBeimErstenSchreibfehlerAb() {
        int[] kopien = {0};
        var seite = new ch.urinet.archiv.ablage.domain.Versionsseite(List.of(
                new ch.urinet.archiv.ablage.domain.Objektversion("archiv/a", "v1", false,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), 1),
                new ch.urinet.archiv.ablage.domain.Objektversion("archiv/b", "v1", false,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), 1)), java.util.Optional.empty());
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "versionen" -> args[1].equals("archiv/") ? seite
                            : new ch.urinet.archiv.ablage.domain.Versionsseite(List.of(), java.util.Optional.empty());
                    case "sicherungsKopie" -> {
                        kopien[0]++;
                        ((java.io.OutputStream) args[3]).write(1);
                        yield new ch.urinet.archiv.ablage.domain.Kopie("a".repeat(64), 1);
                    }
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(ch.urinet.archiv.mandant.domain.MandantCode code) {
                return true;
            }

            @Override
            public void eintragen(ch.urinet.archiv.mandant.domain.Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ch.urinet.archiv.mandant.domain.Mandant> aktive() {
                return List.of(new ch.urinet.archiv.mandant.domain.Mandant(
                        new ch.urinet.archiv.mandant.domain.MandantCode("probe"), "Probe", "m_probe", "b",
                        ch.urinet.archiv.mandant.domain.Geschaeftsjahresende.STANDARD, true));
            }

            @Override
            public java.util.Optional<java.time.Instant> erstellt(ch.urinet.archiv.mandant.domain.MandantCode code) {
                return java.util.Optional.empty();
            }
        };
        SicherungKommando kommando = new SicherungKommando(new SicherungBucketExport(ablage),
                new SicherungExportVerwaltung(speicher((art, mandant, sha256) -> { }), register), new ObjectMapper());
        PrintStream kaputt = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("Pipe geschlossen");
            }
        });

        int exit = kommando.ausfuehren(List.of("zuwachs", "--mandant", "probe"), kaputt,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(3);
        assertThat(kopien[0]).isLessThanOrEqualTo(1);
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

    private static SicherungExportSpeicher speicher(Festhalten festhalten) {
        return new SicherungExportSpeicher() {
            @Override
            public void festhalten(ch.urinet.archiv.sicherung.domain.SicherungsArt art,
                                   java.util.Optional<ch.urinet.archiv.mandant.domain.MandantCode> mandant,
                                   String sha256) {
                festhalten.ausfuehren(art, mandant, sha256);
            }

            @Override
            public Optional<Instant> juengsterEintrag(ch.urinet.archiv.sicherung.domain.SicherungsArt art,
                                                      java.util.Optional<ch.urinet.archiv.mandant.domain.MandantCode> mandant) {
                return Optional.empty();
            }
        };
    }

    @FunctionalInterface
    private interface Festhalten {
        void ausfuehren(ch.urinet.archiv.sicherung.domain.SicherungsArt art,
                        java.util.Optional<ch.urinet.archiv.mandant.domain.MandantCode> mandant, String sha256);
    }
}
