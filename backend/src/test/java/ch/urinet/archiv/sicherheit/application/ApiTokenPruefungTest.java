package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prüfung eines API-Tokens ohne Datenbank und ohne Argon2 (Req 2.5). */
class ApiTokenPruefungTest {

    private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");

    private final SecureRandom zufall = new SecureRandom();
    private final ZaehlenderHasher hasher = new ZaehlenderHasher();
    private final FakeRegister register = new FakeRegister();
    private final StellbareUhr uhr = new StellbareUhr();
    private final ApiTokenPruefung pruefung = new ApiTokenPruefung(register, hasher, uhr, 2, Duration.ofMillis(200));

    @Test
    void unbekannteIdVergleichtTrotzdemEinmalGegenDieAttrappe() {
        ApiToken unbekannt = ApiToken.neu(zufall);

        assertThat(pruefung.pruefen(unbekannt)).isEmpty();
        assertThat(pruefung.pruefen(ApiToken.neu(zufall))).isEmpty();

        // gleich viel Argon2-Arbeit wie bei einer bekannten ID: Die Antwortzeit verrät nicht, ob die ID existiert
        assertThat(hasher.vergleiche).isEqualTo(2);
        assertThat(hasher.gepruefteHashes).allMatch(h -> h.startsWith("hash:"));
    }

    @Test
    void attrappeEntstehtEinmalUndVorDerErstenAnfrage() {
        String attrappe = pruefung.attrappeBerechnen();

        pruefung.pruefen(ApiToken.neu(zufall));
        pruefung.pruefen(ApiToken.neu(zufall));

        assertThat(hasher.hashes).isEqualTo(1);
        assertThat(hasher.gepruefteHashes).containsOnly(attrappe);
    }

    @Test
    void ohnePlatzInDerSperreUeberlastetOhneArgon2() throws Exception {
        CountDownLatch drin = new CountDownLatch(1);
        CountDownLatch los = new CountDownLatch(1);
        hasher.bremse = () -> {
            drin.countDown();
            warten(los);
        };
        ApiTokenPruefung eng = new ApiTokenPruefung(register, hasher, uhr, 1, Duration.ofMillis(50));
        ApiToken bekannt = register.anlegen(ApiToken.neu(zufall), true);
        CompletableFuture<Optional<ApiClient>> erste = CompletableFuture.supplyAsync(() -> eng.pruefen(bekannt));
        assertThat(drin.await(5, TimeUnit.SECONDS)).isTrue();

        // auch die Attrappe für eine unbekannte ID nimmt den Weg durch die Sperre
        assertThatThrownBy(() -> eng.pruefen(ApiToken.neu(zufall))).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.UEBERLASTET));
        assertThat(hasher.vergleiche).isEqualTo(1);

        los.countDown();
        assertThat(erste.get(5, TimeUnit.SECONDS)).isPresent();
        hasher.bremse = () -> { };
        assertThat(eng.pruefen(bekannt)).as("Platz wieder frei").isPresent();
    }

    @Test
    void mindestensEinePruefung() {
        assertThatThrownBy(() -> new ApiTokenPruefung(register, hasher, uhr, 0, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passendesTokenErgibtDenEintrag() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), true);

        assertThat(pruefung.pruefen(token)).map(ApiClient::id).contains(token.id());
        assertThat(hasher.vergleiche).isEqualTo(1);
    }

    @Test
    void falschesGeheimnisOderWiderrufenErgibtLeer() {
        ApiToken echt = register.anlegen(ApiToken.neu(zufall), true);
        ApiToken falsch = ApiToken.lesen("ua_" + echt.id() + "_" + ApiToken.neu(zufall).geheimnis()).orElseThrow();
        ApiToken widerrufen = register.anlegen(ApiToken.neu(zufall), false);

        assertThat(pruefung.pruefen(falsch)).isEmpty();
        assertThat(pruefung.pruefen(widerrufen)).isEmpty();
        assertThat(hasher.vergleiche).isEqualTo(2);
        assertThat(register.zuletzt).isEmpty();
    }

    @Test
    void zuletztHoechstensEinmalProMinute() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), true);

        pruefung.pruefen(token);
        uhr.weiter(Duration.ofSeconds(59));
        pruefung.pruefen(token);
        uhr.weiter(Duration.ofSeconds(1));
        pruefung.pruefen(token);

        assertThat(register.zuletzt).containsExactly(START, START.plusSeconds(60));
    }

    @Test
    void fehlerBeimNachfuehrenBrichtDieAnmeldungNichtAb() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), true);
        register.zuletztFehler = true;

        assertThat(pruefung.pruefen(token)).isPresent();
    }

    private static void warten(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Hash = "hash:" + Geheimnis; zählt Hashes und Vergleiche, die Bremse hält einen Vergleich an. */
    private static final class ZaehlenderHasher implements GeheimnisHasher {
        volatile int hashes;
        volatile int vergleiche;
        volatile Runnable bremse = () -> { };
        final List<String> gepruefteHashes = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public String hashen(String geheimnis) {
            hashes++;
            return "hash:" + geheimnis;
        }

        @Override
        public boolean pruefen(String geheimnis, String hash) {
            vergleiche++;
            gepruefteHashes.add(hash);
            bremse.run();
            return hash.equals("hash:" + geheimnis);
        }
    }

    private static final class FakeRegister implements ApiClientRegister {
        final Map<String, Zugang> eintraege = new HashMap<>();
        final List<Instant> zuletzt = new ArrayList<>();
        boolean zuletztFehler;

        ApiToken anlegen(ApiToken token, boolean aktiv) {
            ApiClient client = new ApiClient(token.id(), "urinet", "Probe", ApiTokenScope.IMPORT, aktiv, START,
                    Optional.empty());
            eintraege.put(token.id(), new Zugang(client, "hash:" + token.geheimnis()));
            return token;
        }

        @Override
        public Optional<Zugang> finden(String id) {
            return Optional.ofNullable(eintraege.get(id));
        }

        @Override
        public void anlegen(ApiClient client, String hash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ApiClient> liste(String mandant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean widerrufen(String mandant, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void zuletztSetzen(String id, Instant zeit) {
            if (zuletztFehler) {
                throw new IllegalStateException("Datenbank weg");
            }
            zuletzt.add(zeit);
        }
    }

    private static final class StellbareUhr extends Clock {
        private Instant jetzt = START;

        void weiter(Duration dauer) {
            jetzt = jetzt.plus(dauer);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }
}
