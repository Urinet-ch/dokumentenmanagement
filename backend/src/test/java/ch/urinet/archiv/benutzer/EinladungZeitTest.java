package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.BenutzerAktivitaet;
import ch.urinet.archiv.benutzer.application.BenutzerEinladen;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadKonfigurationSeed;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class EinladungZeitTest {
    @Inject MandantProvisionierung provisionierung;
    @Inject KonfigurationSeed seed;
    @Inject KonfigurationsdateiCodec codec;
    @Inject MandantKontext kontext;
    @Inject IdentitaetsDienst identitaet;
    @Inject BenutzerEinladen einladen;
    @Inject BenutzerAktivitaet aktivitaet;

    @Test
    void erneuterErfolgreicherVersandAktualisiertZeitFehlgeschlagenerNicht() throws Exception {
        var mandant = new MandantCode("probe-einladung-" + UUID.randomUUID().toString().substring(0, 8));
        KonfigurationSeed originalSeed = ClientProxy.unwrap(seed);
        QuarkusMock.installMockForType(new KlassenpfadKonfigurationSeed(codec) {
            @Override
            public Konfiguration lesen(MandantCode ignoriert) {
                return originalSeed.lesen(new MandantCode("urinet"));
            }
        }, KonfigurationSeed.class);
        try {
            provisionierung.anlegen(mandant.wert(), null, null);
        } finally {
            QuarkusMock.installMockForType(originalSeed, KonfigurationSeed.class);
        }
        var gesendet = new AtomicInteger();
        var stub = new IdentitaetsDienstStub() {
            @Override
            public synchronized void einladen(String code, String id, Set<ErforderlicheAktion> aktionen,
                                               Duration gueltigkeit) {
                if (gesendet.incrementAndGet() == 3) {
                    throw new IllegalStateException("synthetischer Versandfehler");
                }
                super.einladen(code, id, aktionen, gueltigkeit);
            }
        };
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        QuarkusMock.installMockForType(stub, IdentitaetsDienst.class);
        try {
            String id = stub.anlegen(mandant.wert(), new IdentitaetsDienst.Neuanlage(
                    "probe-einladung", "einladung@example.invalid", "Test", "Person"), Rolle.MITARBEITER);
            Benutzer akteur = new Benutzer(UUID.randomUUID().toString(), Rolle.INHABER);
            try (var thread = Executors.newSingleThreadExecutor()) {
                thread.submit(() -> kontext.mit(mandant, () -> einladen.erneut(mandant, akteur, id, Optional.empty())))
                        .get(30, TimeUnit.SECONDS);
                var erste = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(id).einladungSeit()))
                        .get(30, TimeUnit.SECONDS);
                thread.submit(() -> kontext.mit(mandant, () -> einladen.erneut(mandant, akteur, id, Optional.empty())))
                        .get(30, TimeUnit.SECONDS);
                var zweite = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(id).einladungSeit()))
                        .get(30, TimeUnit.SECONDS);
                assertThat(erste).isNotNull();
                assertThat(zweite).isAfter(erste);
                assertThatThrownBy(() -> thread.submit(
                        () -> kontext.mit(mandant, () -> einladen.erneut(mandant, akteur, id, Optional.empty())))
                        .get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                var nachFehler = thread.submit(() -> kontext.mit(mandant, () -> aktivitaet.lesen(id).einladungSeit()))
                        .get(30, TimeUnit.SECONDS);
                assertThat(nachFehler).isEqualTo(zweite);
            }
        } finally {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }
}
