package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnisPflege;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prüfung eines API-Tokens ohne Datenbank und ohne Argon2 (Req 2.5). */
class ApiTokenPruefungTest {

    private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
    private static final PruefCacheSignierer SIGNIERER =
            geheimnis -> geheimnis.getBytes(StandardCharsets.UTF_8);

    private final SecureRandom zufall = new SecureRandom();
    private final ZaehlenderHasher hasher = new ZaehlenderHasher();
    private final FakeRegister register = new FakeRegister();
    private final StellbareUhr uhr = new StellbareUhr();
    private final ApiTokenPruefung pruefung = new ApiTokenPruefung(register, hasher, uhr,
            new HashSperre(2, Duration.ofMillis(200)), SIGNIERER);

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
        ApiTokenPruefung eng = new ApiTokenPruefung(register, hasher, uhr,
                new HashSperre(1, Duration.ofMillis(50)), SIGNIERER);
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
    void anlegenTeiltDiePlaetzeMitDerPruefung() throws Exception {
        CountDownLatch drin = new CountDownLatch(1);
        CountDownLatch los = new CountDownLatch(1);
        hasher.bremse = () -> {
            drin.countDown();
            warten(los);
        };
        HashSperre sperre = new HashSperre(1, Duration.ofMillis(50));
        ApiTokenPruefung eng = new ApiTokenPruefung(register, hasher, uhr, sperre, SIGNIERER);
        ApiTokenVerwaltung verwaltung = new ApiTokenVerwaltung(register, hasher,
                code -> Optional.of("m_" + code.wert()), sperre, new BenutzerVerzeichnisPflege(null));
        ApiToken bekannt = register.anlegen(ApiToken.neu(zufall), true);
        CompletableFuture<Optional<ApiClient>> erste = CompletableFuture.supplyAsync(() -> eng.pruefen(bekannt));
        assertThat(drin.await(5, TimeUnit.SECONDS)).isTrue();

        // 19 MiB je Hash: Auch das Anlegen wartet auf einen Platz, statt das Budget zu überschreiten
        assertThatThrownBy(() -> verwaltung.erstellen("urinet", "Probe", "import"))
                .isInstanceOfSatisfying(FachFehler.class, f -> assertThat(f.code()).isEqualTo(Fehlercode.UEBERLASTET));
        assertThat(hasher.hashes).isZero();

        los.countDown();
        assertThat(erste.get(5, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void ungueltigeEinstellungenBrechenDenStartAb() {
        assertThatThrownBy(() -> new HashSperre(0, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parallele-pruefungen");
        assertThatThrownBy(() -> new HashSperre(2, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wartezeit");
        // Review PR #40 Nacht, K2: mehr als 16 × 19 MiB Argon2 gleichzeitig sprengt jedes Speicherbudget
        assertThatThrownBy(() -> new HashSperre(HashSperre.MAX_PLAETZE + 1, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parallele-pruefungen");
        assertThatCode(() -> new HashSperre(HashSperre.MAX_PLAETZE, Duration.ofMillis(200))).doesNotThrowAnyException();
    }

    @Test
    void wartezeitNullHeisstNichtWarten() throws Exception {
        HashSperre sperre = new HashSperre(1, Duration.ZERO);
        CountDownLatch drin = new CountDownLatch(1);
        CountDownLatch los = new CountDownLatch(1);
        CompletableFuture<Boolean> erste = CompletableFuture.supplyAsync(() -> sperre.ausfuehren(() -> {
            drin.countDown();
            warten(los);
            return true;
        }));
        assertThat(drin.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> sperre.ausfuehren(() -> true)).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.UEBERLASTET));

        los.countDown();
        assertThat(erste.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sperre.ausfuehren(() -> true)).as("Platz wieder frei").isTrue();
    }

    @Test
    void passendesTokenErgibtDenEintrag() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), true);

        assertThat(pruefung.pruefen(token)).map(ApiClient::id).contains(token.id());
        assertThat(hasher.vergleiche).isEqualTo(1);
    }

    @Test
    void importTokenIstBisZumAblaufzeitpunktGueltigAberNichtDanach() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), ApiTokenScope.IMPORT,
                Optional.of(START.plusSeconds(30)));

        assertThat(pruefung.pruefen(token)).isPresent();
        uhr.weiter(Duration.ofSeconds(30));
        assertThat(pruefung.pruefen(token)).isEmpty();
    }

    @Test
    void generatorTokenIstAmAblaufzeitpunktUngueltig() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), ApiTokenScope.GENERATOR, Optional.of(START));

        assertThat(pruefung.pruefen(token)).isEmpty();
    }

    @Test
    void erfolgreicherWebseitenSchluesselNutztDenPruefCacheAberFalschesGeheimnisNicht() {
        ApiToken token = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());

        assertThat(pruefung.pruefen(token)).isPresent();
        assertThat(pruefung.pruefen(token)).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(1);

        ApiToken falsch = ApiToken.lesen("ua_" + token.id() + "_" + ApiToken.neu(zufall).geheimnis()).orElseThrow();
        assertThat(pruefung.pruefen(falsch)).isEmpty();
        assertThat(pruefung.pruefen(token)).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(2);
    }

    @Test
    void widerrufUndAblaufWirkenTrotzPruefCacheSofort() {
        ApiToken widerruf = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());
        ApiToken ablauf = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.of(START.plusSeconds(30)));
        assertThat(pruefung.pruefen(widerruf)).isPresent();
        assertThat(pruefung.pruefen(ablauf)).isPresent();

        register.aktivSetzen(widerruf.id(), false);
        uhr.weiter(Duration.ofSeconds(30));

        assertThat(pruefung.pruefen(widerruf)).isEmpty();
        assertThat(pruefung.pruefen(ablauf)).isEmpty();
        assertThat(hasher.vergleiche).isEqualTo(2);
    }

    @Test
    void abgelaufenerPruefCacheRechnetArgon2Erneut() {
        ApiToken token = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());
        ApiTokenPruefung kurzerCache = new ApiTokenPruefung(register, hasher, uhr,
                new HashSperre(2, Duration.ofMillis(200)), Duration.ofSeconds(10), SIGNIERER);

        assertThat(kurzerCache.pruefen(token)).isPresent();
        uhr.weiter(Duration.ofSeconds(11));
        assertThat(kurzerCache.pruefen(token)).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(2);
    }

    @Test
    void pruefCacheLaeuftAbsolutAbAuchBeiStetigemVerkehr() {
        ApiToken token = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());

        assertThat(pruefung.pruefen(token)).isPresent();
        uhr.weiter(Duration.ofMinutes(4));
        assertThat(pruefung.pruefen(token)).isPresent();
        uhr.weiter(Duration.ofMinutes(4));
        assertThat(pruefung.pruefen(token)).isPresent();

        // Standard 5 Minuten: der Treffer nach 4 Minuten verlängert den Eintrag nicht, der Re-Verify folgt nach 8
        assertThat(hasher.vergleiche).isEqualTo(2);
    }

    @Test
    void pruefCacheHatEineObergrenzeUndCachtDarueberHinausNicht() {
        List<ApiToken> tokens = new ArrayList<>();
        for (int i = 0; i <= ApiTokenPruefung.CACHE_MAX; i++) {
            ApiToken token = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());
            tokens.add(token);
            assertThat(pruefung.pruefen(token)).isPresent();
        }
        int vergleiche = hasher.vergleiche;

        assertThat(pruefung.pruefen(tokens.getFirst())).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(vergleiche);
        assertThat(pruefung.pruefen(tokens.getLast())).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(vergleiche + 1);

        uhr.weiter(Duration.ofMinutes(6));
        assertThat(pruefung.pruefen(tokens.getLast())).isPresent();
        assertThat(pruefung.pruefen(tokens.getLast())).isPresent();
        assertThat(hasher.vergleiche).isEqualTo(vergleiche + 2);
    }

    @Test
    void gueltigerWebseitenSchluesselWartetBeiAusgelasteterHashSperreNicht() throws Exception {
        CountDownLatch imHash = new CountDownLatch(2);
        CountDownLatch loslassen = new CountDownLatch(1);
        HashSperre sperre = new HashSperre(2, Duration.ofMillis(100));
        ApiTokenPruefung unterLast = new ApiTokenPruefung(register, hasher, uhr, sperre, SIGNIERER);
        ApiToken website = register.anlegenWebseite(ApiToken.neu(zufall), true, Optional.empty());
        assertThat(unterLast.pruefen(website)).isPresent();
        hasher.bremse = () -> {
            imHash.countDown();
            warten(loslassen);
        };
        List<CompletableFuture<Optional<ApiClient>>> angriffe = List.of(
                CompletableFuture.supplyAsync(() -> unterLast.pruefen(ApiToken.neu(zufall))),
                CompletableFuture.supplyAsync(() -> unterLast.pruefen(ApiToken.neu(zufall))));
        assertThat(imHash.await(5, TimeUnit.SECONDS)).isTrue();

        long wartezeitMillis;
        List<Long> cacheDauern = new ArrayList<>();
        try {
            long startWartezeit = System.nanoTime();
            assertThatThrownBy(() -> unterLast.pruefen(ApiToken.neu(zufall)))
                    .isInstanceOfSatisfying(FachFehler.class,
                            f -> assertThat(f.code()).isEqualTo(Fehlercode.UEBERLASTET));
            wartezeitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startWartezeit);
            assertThat(wartezeitMillis).as("Wartezeit bei belegten Hash-Plätzen").isLessThan(1_000);

            for (int i = 0; i < 100; i++) {
                long start = System.nanoTime();
                assertThat(unterLast.pruefen(website)).isPresent();
                cacheDauern.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        } finally {
            loslassen.countDown();
        }
        cacheDauern.sort(Long::compareTo);
        assertThat(hasher.vergleiche).as("Trefferquote des warmen Schlüssels: 100/100").isEqualTo(3);
        System.out.printf("Prüf-Cache unter Last: Treffer=100/100, p95=%d ms, max=%d ms; "
                        + "HashSperre: 2 Plätze belegt, Wartezeit bis UEBERLASTET=%d ms%n",
                cacheDauern.get(94), cacheDauern.getLast(), wartezeitMillis);

        assertThat(angriffe).allSatisfy(angriff -> {
            try {
                assertThat(angriff.get(5, TimeUnit.SECONDS)).isEmpty();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
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
    void fehlerBeimNachfuehrenBrichtDieAnmeldungNichtAbUndLoggtNurDieKette() {
        ApiToken token = register.anlegen(ApiToken.neu(zufall), true);
        register.zuletztFehler = true;

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThat(pruefung.pruefen(token)).isPresent();

            assertThat(log.volltext()).contains(token.id(), "java.lang.IllegalStateException <- "
                    + "java.sql.SQLException[sqlstate=08006]").doesNotContain("GEHEIM-4711");
        }
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
            synchronized (this) {
                hashes++;
            }
            return "hash:" + geheimnis;
        }

        @Override
        public boolean pruefen(String geheimnis, String hash) {
            synchronized (this) {
                vergleiche++; // nicht atomar auf volatile: parallele Prüfungen verlieren sonst Zähler
            }
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
            return anlegen(token, ApiTokenScope.IMPORT, aktiv, Optional.empty());
        }

        ApiToken anlegen(ApiToken token, ApiTokenScope scope, Optional<Instant> gueltigBis) {
            return anlegen(token, scope, true, gueltigBis);
        }

        private ApiToken anlegen(ApiToken token, ApiTokenScope scope, boolean aktiv, Optional<Instant> gueltigBis) {
            ApiClient client = new ApiClient(token.id(), "urinet", "Probe", scope, aktiv, START, Optional.empty(),
                    Optional.empty(), Optional.empty(), gueltigBis, false);
            eintraege.put(token.id(), new Zugang(client, "hash:" + token.geheimnis()));
            return token;
        }

        ApiToken anlegenWebseite(ApiToken token, boolean aktiv, Optional<Instant> gueltigBis) {
            ApiClient client = new ApiClient(token.id(), "urinet", "Probe", ApiTokenScope.WEBSEITE_LESEN, aktiv,
                    START, Optional.empty(), Optional.of("probe"), Optional.of("oeffentlich"), gueltigBis, false);
            eintraege.put(token.id(), new Zugang(client, "hash:" + token.geheimnis()));
            return token;
        }

        void aktivSetzen(String id, boolean aktiv) {
            Zugang zugang = eintraege.get(id);
            ApiClient client = zugang.client();
            eintraege.put(id, new Zugang(new ApiClient(client.id(), client.mandant(), client.name(), client.scope(),
                    aktiv, client.erstellt(), client.zuletzt(), client.webseite(), client.hoechsteStufe(),
                    client.gueltigBis(), client.absenderPruefen()), zugang.hash()));
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
        public boolean gueltigBisSetzen(String mandant, String id, Instant zeit) {
            Zugang zugang = eintraege.get(id);
            if (zugang == null || !zugang.client().mandant().equals(mandant)) {
                return false;
            }
            ApiClient client = zugang.client();
            eintraege.put(id, new Zugang(new ApiClient(client.id(), client.mandant(), client.name(), client.scope(),
                    client.aktiv(), client.erstellt(), client.zuletzt(), client.webseite(), client.hoechsteStufe(),
                    Optional.of(zeit), client.absenderPruefen()), zugang.hash()));
            return true;
        }

        @Override
        public boolean apiTokenGueltigkeitSetzen(String mandant, String id, Optional<Instant> zeit) {
            Zugang zugang = eintraege.get(id);
            if (zugang == null || !zugang.client().mandant().equals(mandant)
                    || zugang.client().scope().webseitenSchluessel()) {
                return false;
            }
            ApiClient client = zugang.client();
            eintraege.put(id, new Zugang(new ApiClient(client.id(), client.mandant(), client.name(), client.scope(),
                    client.aktiv(), client.erstellt(), client.zuletzt(), client.webseite(), client.hoechsteStufe(),
                    zeit, client.absenderPruefen()), zugang.hash()));
            return true;
        }

        @Override
        public void zuletztSetzen(String id, Instant zeit) {
            if (zuletztFehler) {
                throw new IllegalStateException("Datenbank weg: GEHEIM-4711",
                        new java.sql.SQLException("Verbindung zu GEHEIM-4711 verloren", "08006"));
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
