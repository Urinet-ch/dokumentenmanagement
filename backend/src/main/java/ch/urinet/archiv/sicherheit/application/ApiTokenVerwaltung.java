package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnisPflege;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API-Tokens anlegen, auflisten und widerrufen (Req 2.5, 2.20; REST nur mit A14, sonst das Admin-Kommando). Das
 * Geheimnis gibt nur {@link #erstellen} einmal zurück; gespeichert wird sein Argon2id-Hash. Der Hash entsteht über die
 * {@link HashSperre} wie jede Prüfung, damit auch das Anlegen im Speicherbudget bleibt. Geloggt werden nur ID,
 * Mandant und Scope.
 */
@ApplicationScoped
public class ApiTokenVerwaltung {

    static final int NAME_MAX = 100;
    private static final Logger LOG = Logger.getLogger(ApiTokenVerwaltung.class);

    private final ApiClientRegister register;
    private final GeheimnisHasher hasher;
    private final MandantVerzeichnis verzeichnis;
    private final HashSperre sperre;
    private final BenutzerVerzeichnisPflege benutzerVerzeichnis;
    private final SecureRandom zufall = new SecureRandom();

    public ApiTokenVerwaltung(ApiClientRegister register, GeheimnisHasher hasher, MandantVerzeichnis verzeichnis,
                              HashSperre sperre, BenutzerVerzeichnisPflege benutzerVerzeichnis) {
        this.register = register;
        this.hasher = hasher;
        this.verzeichnis = verzeichnis;
        this.sperre = sperre;
        this.benutzerVerzeichnis = benutzerVerzeichnis;
    }

    /** Das neue Token mit seinem Eintrag; {@code token} ist die einzige Stelle, an der das Geheimnis vorkommt. */
    public record Erstellt(ApiClient client, ApiToken token) {
    }

    /**
     * Legt ein Token im Mandanten an. Fehler: Mandant ungültig oder inaktiv → {@code MANDANT_FEHLT}; Name oder Scope
     * fehlt → {@code PFLICHTFELD_FEHLT}; Name zu lang oder mit Steuerzeichen, Scope unbekannt → {@code ANFRAGE_UNGUELTIG};
     * kein Platz für den Hash innerhalb der Wartezeit → {@code UEBERLASTET}.
     */
    public Erstellt erstellen(String mandant, String name, String scope) {
        MandantCode code = aktiverMandant(mandant);
        String bereinigt = name == null ? "" : name.strip();
        if (bereinigt.isEmpty() || scope == null || scope.isBlank()) {
            throw new FachFehler(Fehlercode.PFLICHTFELD_FEHLT);
        }
        if (bereinigt.length() > NAME_MAX || bereinigt.chars().anyMatch(Character::isISOControl)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ApiTokenScope gueltig = ApiTokenScope.ausCode(scope).orElseThrow(
                () -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
        ApiToken token = ApiToken.neu(zufall);
        ApiClient client = new ApiClient(token.id(), code.wert(), bereinigt, gueltig, true, Instant.now(),
                Optional.empty());
        String hash = sperre.ausfuehren(() -> hasher.hashen(token.geheimnis()));
        register.anlegen(client, hash);
        LOG.infof("API-Token angelegt: id=%s mandant=%s scope=%s", client.id(), client.mandant(), gueltig.code());
        return new Erstellt(client, token);
    }

    public List<ApiClient> liste(MandantCode mandant) {
        return register.liste(mandant.wert());
    }

    /**
     * Widerruf im eigenen Mandanten; eine fremde oder unbekannte ID ergibt {@code NICHT_GEFUNDEN}. In derselben
     * Transaktion beendet eine neue Zeile im Benutzerverzeichnis die Kennung {@code token:<id>} (Req 10.10); die Zeile
     * in {@code public.api_client} bleibt.
     */
    @Transactional
    public void widerrufen(MandantCode mandant, String id, Optional<String> erfasstVon) {
        if (!ApiToken.gueltigeId(id) || !register.widerrufen(mandant.wert(), id)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        ApiClient client = register.finden(id).map(ApiClientRegister.Zugang::client)
                .orElseThrow(() -> new IllegalStateException("API-Token nach dem Widerruf nicht lesbar"));
        benutzerVerzeichnis.tokenWiderrufen(mandant, ApiToken.principal(id), client.name(), client.erstellt(),
                erfasstVon);
        LOG.infof("API-Token widerrufen: id=%s mandant=%s", id, mandant.wert());
    }

    private MandantCode aktiverMandant(String mandant) {
        if (mandant == null) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        MandantCode code;
        try {
            code = new MandantCode(mandant);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        if (verzeichnis.aktivesSchema(code).isEmpty()) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        return code;
    }
}
