package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WissenExportTest {
    @Test
    void downloadIstNurBis24StundenNachAbschlussVerfuegbar() {
        Instant fertig = Instant.parse("2026-01-02T03:04:05Z");
        WissenExportRepository.Export export = new WissenExportRepository.Export(UUID.randomUUID(),
                WissenExportRepository.Status.FERTIG, "probe", "INHABER", fertig.minusSeconds(1), fertig,
                "exporte/probe.zip", "v1", "a".repeat(64), 100L, null);
        assertThat(export.herunterladbar(fertig.plus(Duration.ofHours(24)).minusNanos(1))).isTrue();
        assertThat(export.herunterladbar(fertig.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void fehlgeschlagenerExportIstNichtHerunterladbar() {
        Instant jetzt = Instant.parse("2026-01-02T03:04:05Z");
        WissenExportRepository.Export export = new WissenExportRepository.Export(UUID.randomUUID(),
                WissenExportRepository.Status.FEHLER, "probe", "INHABER", jetzt, null,
                null, null, null, null, UUID.randomUUID());
        assertThat(export.herunterladbar(jetzt)).isFalse();
    }

    @Test
    void abrufSigniertVersionUndProtokolliertPruefsummeNurInnerhalbDerFrist() {
        Instant fertig = Instant.parse("2026-01-02T03:04:05Z");
        Instant ende = fertig.plus(Duration.ofHours(24));
        UUID id = UUID.randomUUID();
        MandantCode mandant = new MandantCode("urinet");
        WissenExportRepository.Export export = new WissenExportRepository.Export(id,
                WissenExportRepository.Status.FERTIG, "probe", "INHABER", fertig.minusSeconds(1), fertig,
                "exporte/probe.zip", "v1", "a".repeat(64), 100L, null);
        WissenExportRepository repository = (WissenExportRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WissenExportRepository.class}, (proxy, methode, args) -> {
                    if ("laden".equals(methode.getName())) {
                        assertThat(args[0]).isEqualTo(id);
                        return Optional.of(export);
                    }
                    throw new AssertionError("Unerwarteter Repository-Aufruf: " + methode.getName());
                });
        AtomicInteger signaturen = new AtomicInteger();
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    assertThat(methode.getName()).isEqualTo("exportUrl");
                    assertThat(List.of(args)).containsExactly(mandant, export.schluessel(), export.version(), ende,
                            "wissensexport-urinet.zip");
                    signaturen.incrementAndGet();
                    return new VorsignierteUrl(URI.create("/probe"), ende);
                });
        AtomicReference<String> protokolliertePruefsumme = new AtomicReference<>();
        WissenExportProtokoll protokoll = new WissenExportProtokoll() {
            public void angefordert(UUID exportId) {
                throw new AssertionError("Keine Anforderung erwartet");
            }

            public void abgeschlossen(UUID exportId, String benutzer, String rolle, String sha256) {
                throw new AssertionError("Kein Abschluss erwartet");
            }

            public void heruntergeladen(UUID exportId, String sha256) {
                assertThat(exportId).isEqualTo(id);
                protokolliertePruefsumme.set(sha256);
            }
        };
        Berechtigung berechtigung = new Berechtigung((benutzer, dokument) -> true,
                (benutzer, rolle, aktion, dokument, grund) -> { });
        Benutzer inhaber = new Benutzer("probe", Rolle.INHABER);
        var vorAblauf = new WissenExportVerwaltung(repository, protokoll, null, ablage, berechtigung,
                Clock.fixed(ende.minusNanos(1), ZoneOffset.UTC));
        assertThat(vorAblauf.abrufen(inhaber, mandant, id).adresse())
                .contains(new VorsignierteUrl(URI.create("/probe"), ende));
        assertThat(protokolliertePruefsumme.get()).isEqualTo(export.sha256());
        var abgelaufen = new WissenExportVerwaltung(repository, protokoll, null, ablage, berechtigung,
                Clock.fixed(ende, ZoneOffset.UTC));
        protokolliertePruefsumme.set(null);
        assertThat(abgelaufen.abrufen(inhaber, mandant, id).adresse()).isEmpty();
        assertThat(protokolliertePruefsumme.get()).isNull();
        assertThat(signaturen).hasValue(1);
    }
}
