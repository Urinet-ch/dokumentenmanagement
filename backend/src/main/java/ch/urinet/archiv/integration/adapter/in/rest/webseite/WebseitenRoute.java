package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAntwort;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.Abkuendigung;
import ch.urinet.archiv.integration.domain.webseite.Hauptversion;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Webseiten-Schnittstelle unter {@code /public/} (Spec 03, Aufgabe 11.6, design.md «Vertrag und Versionierung»).
 * <p>
 * Quarkus REST hängt jede Resource unter {@code quarkus.rest.path} ({@code /api/v1}) und registriert am Router nur diesen
 * Pfad. Darum leitet dieser Handler jede Anfrage unter {@code /public/} vor der Anmeldung per Vert.x-Reroute auf den
 * internen Pfad {@code /api/v1/public/…} um; die Resources tragen {@code @Path("/public/v1/…")}. Nach dem Reroute
 * gelten dort Anmeldung, HTTP-Rechte ({@code /api/*}), {@code ProblemAnmeldung}, {@code ProblemFehlerRoute} und
 * {@code ProblemAbbildung} wie für die App. Der interne Pfad ist von aussen gesperrt: ohne vorherigen Reroute 404
 * {@code NICHT_GEFUNDEN}, vor der Anmeldung, damit er sich nicht durch 401 verrät.
 * <p>
 * Jede Antwort unter {@code /public/} trägt {@code Cache-Control: no-store} (Req 15.4), auch Fehler vor der Resource;
 * ist die Hauptversion abgekündigt ({@code archiv.webseite.v<n>.sunset}), zusätzlich {@code Deprecation}, {@code Sunset}
 * und {@code Link} (Req 11.5). Gesetzt werden sie am Router, kurz bevor die Header geschrieben werden.
 */
@ApplicationScoped
public class WebseitenRoute {

    /** Nach CORS (-300), vor der Anmeldung (-200) von Quarkus (Filter mit Order {@code -1 × Priorität}). */
    static final int REIHENFOLGE = -250;
    static final String SUNSET = "archiv.webseite.v%d.sunset";
    /** Kontext-Datum mit dem äusseren Pfad; ist es gesetzt, kam die Anfrage über {@code /public/}. */
    static final String AEUSSERER_PFAD = WebseitenRoute.class.getName() + ".pfad";

    private static final Pattern SCHLUESSEL = Pattern.compile("^archiv\\.webseite\\.v([1-9][0-9]{0,3})\\.sunset$");
    private static final Pattern UMGEBUNG = Pattern.compile("^ARCHIV_WEBSEITE_V([1-9][0-9]{0,3})_SUNSET$");

    private final String intern;
    private final Map<Integer, Abkuendigung> abkuendigungen;

    @Inject
    public WebseitenRoute(Config konfiguration) {
        this(konfiguration.getValue("quarkus.rest.path", String.class),
                abkuendigungen(konfiguration.getPropertyNames(),
                        schluessel -> konfiguration.getOptionalValue(schluessel, String.class)));
    }

    WebseitenRoute(String restPfad, Map<Integer, Abkuendigung> abkuendigungen) {
        this.intern = restPfad.endsWith("/") ? restPfad.substring(0, restPfad.length() - 1) : restPfad;
        this.abkuendigungen = Map.copyOf(abkuendigungen);
    }

    /**
     * Liest {@code archiv.webseite.v<n>.sunset} (ISO-Datum) für Version 1 und jede Version, die als Schlüssel oder
     * Umgebungsvariable vorkommt. Ein ungültiger Wert bricht den Start ab; die Meldung nennt nur den Schlüssel.
     */
    static Map<Integer, Abkuendigung> abkuendigungen(Iterable<String> namen, Function<String, Optional<String>> wert) {
        TreeSet<Integer> versionen = new TreeSet<>();
        versionen.add(1);
        for (String name : namen) {
            Matcher treffer = SCHLUESSEL.matcher(name);
            Matcher umgebung = UMGEBUNG.matcher(name);
            if (treffer.matches()) {
                versionen.add(Integer.parseInt(treffer.group(1)));
            } else if (umgebung.matches()) {
                versionen.add(Integer.parseInt(umgebung.group(1)));
            }
        }
        Map<Integer, Abkuendigung> ergebnis = new TreeMap<>();
        for (int version : versionen) {
            String schluessel = SUNSET.formatted(version);
            Optional<String> datum = wert.apply(schluessel).map(String::strip).filter(w -> !w.isEmpty());
            if (datum.isEmpty()) {
                continue;
            }
            try {
                ergebnis.put(version, new Abkuendigung(new Hauptversion(version), LocalDate.parse(datum.get())));
            } catch (DateTimeParseException e) {
                throw new IllegalStateException(schluessel + " ist kein ISO-Datum (JJJJ-MM-TT); Wert korrigieren und neu "
                        + "starten.");
            }
        }
        return ergebnis;
    }

    void einrichten(@Observes Router router) {
        router.route().order(REIHENFOLGE).handler(this::behandeln);
    }

    void behandeln(RoutingContext kontext) {
        String pfad = ohneDoppelteSchraegstriche(kontext.normalizedPath());
        String aeusserer = kontext.get(AEUSSERER_PFAD);
        if (aeusserer != null) {
            // zweiter Durchlauf nach dem Reroute: der Reroute verwirft Header und Handler des ersten
            kontext.addHeadersEndHandler(ende -> kopfzeilen(kontext.response(), aeusserer));
            kontext.next();
        } else if (unter(pfad, Hauptversion.PRAEFIX)) {
            kontext.put(AEUSSERER_PFAD, pfad);
            String abfrage = kontext.request().query();
            kontext.reroute(intern + pfad + (abfrage == null ? "" : "?" + abfrage));
        } else if (unter(pfad, intern + Hauptversion.PRAEFIX)) {
            ProblemAntwort problem = ProblemAntwort.fuer(Fehlercode.NICHT_GEFUNDEN);
            kontext.response().setStatusCode(problem.status())
                    .putHeader(HttpHeaders.CONTENT_TYPE, ProblemAntwort.MEDIENTYP).end(problem.json());
        } else {
            kontext.next();
        }
    }

    /** Header jeder Antwort unter {@code /public/}: {@code no-store}, bei abgekündigter Version die drei Header. */
    void kopfzeilen(HttpServerResponse antwort, String aeussererPfad) {
        antwort.putHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Hauptversion.ausPfad(aeussererPfad).map(v -> abkuendigungen.get(v.nummer())).ifPresent(a -> {
            antwort.putHeader("Deprecation", a.deprecation());
            antwort.putHeader("Sunset", a.sunsetHttpDatum());
            antwort.headers().add("Link", a.link());
        });
    }

    private static boolean unter(String pfad, String praefix) {
        return pfad.equals(praefix) || pfad.startsWith(praefix + "/");
    }

    private static String ohneDoppelteSchraegstriche(String pfad) {
        return pfad.indexOf("//") < 0 ? pfad : pfad.replaceAll("/{2,}", "/");
    }
}
