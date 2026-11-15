package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Provisioniert Webseiten-Schlüssel, rotiert sie mit Überlappung und widerruft sie. */
@ApplicationScoped
public class WebseitenSchluesselVerwaltung {

    public static final Duration MAX_UEBERLAPPUNG = Duration.ofDays(30);
    public static final Duration STANDARD_UEBERLAPPUNG = Duration.ofDays(7);
    private static final Logger LOG = Logger.getLogger(WebseitenSchluesselVerwaltung.class);

    private final ApiClientRegister register;
    private final ApiTokenVerwaltung tokenVerwaltung;
    private final GeheimnisHasher hasher;
    private final MandantVerzeichnis mandanten;
    private final HashSperre sperre;
    private final SecureRandom zufall = new SecureRandom();

    public WebseitenSchluesselVerwaltung(ApiClientRegister register, ApiTokenVerwaltung tokenVerwaltung,
            GeheimnisHasher hasher, MandantVerzeichnis mandanten, HashSperre sperre) {
        this.register = register;
        this.tokenVerwaltung = tokenVerwaltung;
        this.hasher = hasher;
        this.mandanten = mandanten;
        this.sperre = sperre;
    }

    public record Erstellt(ApiClient client, ApiToken token) {
    }

    public Erstellt erstellen(String mandant, String webseite, String art, String stufe, boolean absenderPruefen) {
        MandantCode code = aktiverMandant(mandant);
        String kennung = webseite == null ? "" : webseite.strip();
        if (kennung.isEmpty() || kennung.length() > 100 || kennung.chars().anyMatch(Character::isISOControl)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ApiTokenScope scope = switch (art == null ? "" : art) {
            case "lesen" -> ApiTokenScope.WEBSEITE_LESEN;
            case "erfassung" -> ApiTokenScope.WEBSEITE_ERFASSUNG;
            default -> throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        };
        String maxStufe;
        if (scope.istErfassung()) {
            if (stufe != null) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            maxStufe = "vorstand";
        } else if (stufe != null && List.of("oeffentlich", "mitglieder", "vorstand").contains(stufe)) {
            maxStufe = stufe;
        } else {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Erstellt erstellt = anlegen(code.wert(), kennung, scope, maxStufe, absenderPruefen);
        ApiClient client = erstellt.client();
        LOG.infof("Webseiten-Schlüssel angelegt: id=%s mandant=%s webseite=%s scope=%s", client.id(),
                client.mandant(), kennung, scope.code());
        return erstellt;
    }

    @Transactional
    public Erstellt rotieren(String id, Duration ueberlappung) {
        if (ueberlappung == null || ueberlappung.isNegative() || ueberlappung.compareTo(MAX_UEBERLAPPUNG) > 0) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ApiClient alt = webseitenSchluessel(id);
        Instant gueltigBis = Instant.now().plus(ueberlappung);
        if (!register.gueltigBisSetzen(alt.mandant(), alt.id(), gueltigBis)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Erstellt erstellt = anlegen(alt.mandant(), alt.webseite().orElseThrow(), alt.scope(),
                alt.hoechsteStufe().orElseThrow(), alt.absenderPruefen());
        ApiClient neu = erstellt.client();
        LOG.infof("Webseiten-Schlüssel rotiert: id=%s mandant=%s webseite=%s scope=%s", neu.id(), neu.mandant(),
                neu.webseite().orElseThrow(), neu.scope().code());
        return erstellt;
    }

    @Transactional
    public ApiClient widerrufen(String id) {
        ApiClient client = webseitenSchluessel(id);
        tokenVerwaltung.widerrufen(new MandantCode(client.mandant()), id, Optional.empty());
        LOG.infof("Webseiten-Schlüssel widerrufen: id=%s mandant=%s webseite=%s scope=%s", client.id(),
                client.mandant(), client.webseite().orElseThrow(), client.scope().code());
        return client;
    }

    private Erstellt anlegen(String mandant, String webseite, ApiTokenScope scope, String stufe,
                             boolean absenderPruefen) {
        ApiToken token = ApiToken.neu(zufall);
        ApiClient client = new ApiClient(token.id(), mandant, webseite, scope, true, Instant.now(),
                Optional.empty(), Optional.of(webseite), Optional.of(stufe), Optional.empty(), absenderPruefen);
        String hash = sperre.ausfuehren(() -> hasher.hashen(token.geheimnis()));
        register.anlegen(client, hash);
        return new Erstellt(client, token);
    }

    private ApiClient webseitenSchluessel(String id) {
        if (!ApiToken.gueltigeId(id)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        ApiClient client = register.finden(id).map(ApiClientRegister.Zugang::client)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!client.scope().webseitenSchluessel() || !client.aktiv()) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return client;
    }

    private MandantCode aktiverMandant(String mandant) {
        MandantCode code;
        try {
            code = new MandantCode(mandant);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        if (mandanten.aktivesSchema(code).isEmpty()) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        return code;
    }
}
