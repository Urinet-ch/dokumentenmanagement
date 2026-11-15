package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prüft ein API-Token bei jeder Anfrage: Lookup über die ID, dann der Argon2id-Vergleich. Für Webseiten-Schlüssel
 * darf ein pro Prozess zufällig geschlüsselter HMAC den Argon2id-Vergleich für eine begrenzte Zeit ersetzen (Req 12.8).
 * <p>
 * Invariante gegen Timing: Auch für eine unbekannte ID läuft genau ein Vergleich, gegen einen festen Attrappen-Hash.
 * Sonst verriete die Antwortzeit, welche IDs es gibt. Der Vergleich läuft immer vor der Prüfung von {@code aktiv}.
 * Die Attrappe entsteht beim Start ({@link #attrappeBerechnen()}), nicht bei der ersten Anfrage, und zwar über die
 * {@link HashSperre}: So entsteht auch die Sperre beim Start, und ungültige Einstellungen brechen ihn ab.
 * <p>
 * Jeder Vergleich kostet rund 19 MiB Heap. Darum läuft er über die {@link HashSperre}, auch der gegen die Attrappe
 * (gleicher Weg, gleiche Laufzeit); so erschöpfen Anfragen ohne Anmeldung den Heap nicht.
 * <p>
 * {@code zuletzt} wird höchstens einmal pro Minute und Token nachgeführt; ein Fehler dabei bricht die Anfrage nicht ab
 * und wird nur mit ID und Ursachen-Kette (Klassennamen, SQLState) geloggt, nie mit Meldungen.
 */
@ApplicationScoped
public class ApiTokenPruefung {

    static final Duration DROSSEL = Duration.ofMinutes(1);
    private static final SecureRandom ZUFALL = new SecureRandom();
    private static final Logger LOG = Logger.getLogger(ApiTokenPruefung.class);
    private static final String HMAC_ALGORITHMUS = "HmacSHA256";

    private final ApiClientRegister register;
    private final GeheimnisHasher hasher;
    private final Clock uhr;
    private final HashSperre sperre;
    private final Duration cacheDauer;
    private final byte[] cacheSchluessel = new byte[32];
    private final Map<String, CacheEintrag> pruefCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> zuletztGemeldet = new ConcurrentHashMap<>();
    private volatile String attrappe;

    @Inject
    public ApiTokenPruefung(ApiClientRegister register, GeheimnisHasher hasher, HashSperre sperre,
                           @ConfigProperty(name = "archiv.webseite.pruef-cache", defaultValue = "PT5M")
                           Duration cacheDauer) {
        this(register, hasher, Clock.systemUTC(), sperre, cacheDauer);
    }

    ApiTokenPruefung(ApiClientRegister register, GeheimnisHasher hasher, Clock uhr, HashSperre sperre) {
        this(register, hasher, uhr, sperre, Duration.ofMinutes(5));
    }

    ApiTokenPruefung(ApiClientRegister register, GeheimnisHasher hasher, Clock uhr, HashSperre sperre,
                     Duration cacheDauer) {
        this.register = register;
        this.hasher = hasher;
        this.uhr = uhr;
        this.sperre = sperre;
        if (cacheDauer == null || cacheDauer.isNegative() || cacheDauer.isZero()) {
            throw new IllegalArgumentException("archiv.webseite.pruef-cache muss grösser als null sein");
        }
        this.cacheDauer = cacheDauer;
        ZUFALL.nextBytes(cacheSchluessel);
    }

    /**
     * Aktiver Eintrag, wenn ID und Geheimnis passen; sonst leer (unbekannt, falsches Geheimnis, widerrufen).
     *
     * @throws FachFehler {@code UEBERLASTET}, wenn innerhalb der Wartezeit kein Platz für den Vergleich frei wird
     */
    public Optional<ApiClient> pruefen(ApiToken token) {
        Optional<ApiClientRegister.Zugang> zugang = register.finden(token.id());
        if (zugang.isEmpty()) {
            String attrappeHash = attrappeBerechnen();
            sperre.ausfuehren(() -> hasher.pruefen(token.geheimnis(), attrappeHash));
            return Optional.empty();
        }
        ApiClient client = zugang.get().client();
        boolean passt = cacheTreffer(client, token.geheimnis());
        if (!passt) {
            passt = sperre.ausfuehren(() -> hasher.pruefen(token.geheimnis(), zugang.get().hash()));
        }
        Instant jetzt = uhr.instant();
        if (!passt || !client.aktiv() || client.gueltigBis().filter(bis -> !bis.isAfter(jetzt)).isPresent()) {
            return Optional.empty();
        }
        if (client.scope().webseitenSchluessel()) {
            pruefCache.put(client.id(), new CacheEintrag(hmac(token.geheimnis()), jetzt.plus(cacheDauer)));
        }
        zuletztNachfuehren(client.id());
        return Optional.of(client);
    }

    private boolean cacheTreffer(ApiClient client, String geheimnis) {
        if (!client.scope().webseitenSchluessel()) {
            return false;
        }
        CacheEintrag eintrag = pruefCache.get(client.id());
        if (eintrag == null) {
            return false;
        }
        if (!eintrag.ablauf().isAfter(uhr.instant())) {
            pruefCache.remove(client.id(), eintrag);
            return false;
        }
        return MessageDigest.isEqual(eintrag.geheimnisHmac(), hmac(geheimnis));
    }

    private byte[] hmac(String geheimnis) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHMUS);
            mac.init(new SecretKeySpec(cacheSchluessel, HMAC_ALGORITHMUS));
            return mac.doFinal(geheimnis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC für Webseiten-Prüf-Cache nicht verfügbar", e);
        }
    }

    private void zuletztNachfuehren(String id) {
        Instant jetzt = uhr.instant();
        AtomicBoolean faellig = new AtomicBoolean();
        zuletztGemeldet.compute(id, (schluessel, letzte) -> {
            if (letzte != null && letzte.plus(DROSSEL).isAfter(jetzt)) {
                return letzte;
            }
            faellig.set(true);
            return jetzt;
        });
        if (!faellig.get()) {
            return;
        }
        try {
            register.zuletztSetzen(id, jetzt);
        } catch (RuntimeException e) {
            LOG.warnf("Letzte Verwendung des API-Tokens nicht nachgeführt: id=%s ursache=%s", id,
                    Ursachen.kette(e));
        }
    }

    /**
     * Berechnet beim Start den Hash eines zufälligen Geheimnisses, das niemand kennt, mit den Parametern des Hashers.
     * Ohne Start (Unit-Tests) holt {@link #pruefen} das einmal nach.
     */
    public String attrappeBerechnen() {
        String wert = attrappe;
        if (wert == null) {
            synchronized (this) {
                wert = attrappe;
                if (wert == null) {
                    String geheimnis = ApiToken.neu(ZUFALL).geheimnis();
                    wert = sperre.ausfuehren(() -> hasher.hashen(geheimnis));
                    attrappe = wert;
                }
            }
        }
        return wert;
    }

    private record CacheEintrag(byte[] geheimnisHmac, Instant ablauf) {
    }
}
