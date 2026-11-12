package ch.urinet.archiv.ki.adapter.out;

import io.quarkiverse.langchain4j.openai.runtime.config.LangChain4jOpenAiConfig;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Startprüfung der KI-Endpunkte (Req 16.21, 16.22; design.md «KI», Profil {@code lokal}; ADR-012): zulässig ist nur
 * HTTPS auf {@code api.infomaniak.com}, im Profil {@code lokal} zusätzlich HTTP auf {@code localhost}/{@code 127.0.0.1},
 * im Profil {@code test} nur mit dem Port der WireMock-Testressource (nie 1234). Die Meldung nennt nur Host und Profil.
 * Geprüft werden die eigenen Schlüssel, der Standard-Client von Quarkus LangChain4j und jeder benannte Client
 * ({@code quarkus.langchain4j.openai.<name>.*}); ein benannter Client ohne {@code base-url} fiele auf den Standard von
 * LangChain4j ({@code api.openai.com}) zurück und bricht darum ab, auch mit Stub.
 * Zusätzlich prüft die Klasse die aktiven Profile: {@code lokal} zusammen mit {@code prod} bricht ab (Req 16.22), ebenso
 * das reine Testprofil {@code ceph} der Ablage zusammen mit {@code prod} (design.md «Startprüfung
 * {@code KiBasisUrlPruefung}» und «Teststrategie», Ceph RGW).
 */
@ApplicationScoped
public class KiBasisUrlPruefung {

    static final String INFOMANIAK = "api.infomaniak.com";
    static final Set<String> LOKALE_HOSTS = Set.of("localhost", "127.0.0.1");
    static final int LM_STUDIO_PORT = 1234;
    static final String WIREMOCK_PORT = "archiv.ki.wiremock-port";

    /** URL-Schlüssel und der Stub-Schalter, bei dessen Wert {@code true} eine leere URL zulässig ist. */
    static final List<Schluessel> SCHLUESSEL = List.of(
            new Schluessel("archiv.ki.basis-url", "archiv.ki.stub"),
            new Schluessel("quarkus.langchain4j.openai.base-url", "archiv.ki.stub"),
            new Schluessel("archiv.ki.reranker-url", "archiv.ki.reranker-stub"));

    record Schluessel(String url, String stub) {
    }

    void beimStart(@Observes StartupEvent ereignis, LangChain4jOpenAiConfig openai) {
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        List<String> profile = wirksameProfile(config.getProfiles(), LaunchMode.current());
        pruefe(profile, config);
        pruefeClients(profile, config, openai.defaultConfig().baseUrl(), clients(openai));
    }

    private static Map<String, String> clients(LangChain4jOpenAiConfig openai) {
        Map<String, String> clients = new java.util.TreeMap<>();
        openai.namedConfig().forEach((name, client) -> clients.put(name, client.baseUrl()));
        return clients;
    }

    /**
     * Prüft die wirksame Basis-URL des Standard-Clients (bei aktivem Stub nur, wenn sie gesetzt ist) und jedes benannten
     * Clients (immer, Stub entschuldigt ihn nicht).
     *
     * @param standardUrl wirksame Basis-URL des Standard-Clients (mit dem Standardwert von LangChain4j)
     * @param benannte    Name → wirksame Basis-URL je benanntem Client
     */
    public static void pruefeClients(List<String> profile, Config config, String standardUrl,
                                     Map<String, String> benannte) {
        OptionalInt wiremockPort = wiremockPort(config);
        boolean stub = config.getOptionalValue("archiv.ki.stub", Boolean.class).orElse(false);
        if (!stub || config.getOptionalValue("quarkus.langchain4j.openai.base-url", String.class).isPresent()) {
            pruefe(profile, standardUrl, false, wiremockPort);
        }
        benannte.values().forEach(url -> pruefe(profile, url, false, wiremockPort));
    }

    private static OptionalInt wiremockPort(Config config) {
        return config.getOptionalValue(WIREMOCK_PORT, Integer.class)
                .map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    /** Ein gepacktes Artefakt (LaunchMode NORMAL) gilt immer als {@code prod}, auch mit {@code -Dquarkus.profile=lokal}. */
    static List<String> wirksameProfile(List<String> profile, LaunchMode modus) {
        List<String> wirksam = new ArrayList<>(profile);
        if (modus == LaunchMode.NORMAL && !wirksam.contains("prod")) {
            wirksam.add("prod");
        }
        return List.copyOf(wirksam);
    }

    /** Prüft alle KI-URL-Schlüssel der Konfiguration; bricht beim ersten abgelehnten Wert ab. */
    public static void pruefe(List<String> profile, Config config) {
        pruefeProfile(profile);
        OptionalInt wiremockPort = wiremockPort(config);
        for (Schluessel schluessel : SCHLUESSEL) {
            String url = config.getOptionalValue(schluessel.url(), String.class).orElse(null);
            boolean stub = config.getOptionalValue(schluessel.stub(), Boolean.class).orElse(false);
            pruefe(profile, url, stub, wiremockPort);
        }
    }

    /**
     * Req 16.22: {@code lokal} zusammen mit {@code prod} bricht ab. Zusätzlich (design.md «Teststrategie», Ceph RGW):
     * das reine Testprofil {@code ceph} zusammen mit {@code prod}.
     */
    public static void pruefeProfile(List<String> profile) {
        if (profile.contains("lokal") && profile.contains("prod")) {
            throw new IllegalStateException("Profil «lokal» ist zusammen mit «prod» nicht zulässig (Req 16.22); "
                    + "aktive Profile: " + String.join(",", profile));
        }
        if (profile.contains("ceph") && profile.contains("prod")) {
            throw new IllegalStateException("Profil «ceph» (nur Ablage-Tests gegen Ceph RGW) ist zusammen mit «prod» "
                    + "nicht zulässig; aktive Profile: " + String.join(",", profile));
        }
    }

    /**
     * Reine Prüflogik für eine URL. Bei aktivem Stub ist eine leere URL zulässig; eine gesetzte URL wird auch dann
     * geprüft.
     */
    public static void pruefe(List<String> profile, String url, boolean stub, OptionalInt wiremockPort) {
        pruefeProfile(profile);
        if (url == null || url.isBlank()) {
            if (stub) {
                return;
            }
            throw abgelehnt("(leer)", profile);
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw abgelehnt("(ungültige URL)", profile);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null) {
            throw abgelehnt("(kein Host)", profile);
        }
        String schema = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() == null && zulaessig(profile, schema, host, uri.getPort(), wiremockPort)) {
            return;
        }
        throw abgelehnt(host, profile);
    }

    private static boolean zulaessig(List<String> profile, String schema, String host, int port,
                                     OptionalInt wiremockPort) {
        if ("https".equals(schema) && INFOMANIAK.equals(host)) {
            return true;
        }
        if (!"http".equals(schema) || !LOKALE_HOSTS.contains(host)) {
            return false;
        }
        if (profile.contains("test")) {
            return port != LM_STUDIO_PORT && wiremockPort.isPresent() && wiremockPort.getAsInt() == port;
        }
        return profile.contains("lokal");
    }

    private static IllegalStateException abgelehnt(String host, List<String> profile) {
        return new IllegalStateException("KI-Endpunkt abgelehnt: Host " + host + ", Profil "
                + String.join(",", profile) + ". Zulässig ist nur https://" + INFOMANIAK
                + ", im Profil lokal auch localhost (Req 16.21, ADR-012).");
    }
}
