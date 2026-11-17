package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnisPflege;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.adapter.in.kommando.WebseitenSchluesselKommando;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebseitenSchluesselVerwaltungTest {

    private final Register register = new Register();
    private final HashSperre sperre = new HashSperre(1, Duration.ZERO);
    private final WebseitenSchluesselVerwaltung verwaltung = new WebseitenSchluesselVerwaltung(register,
            new ApiTokenVerwaltung(register, new Hasher(), code -> Optional.of("m_" + code.wert()), sperre,
                    new BenutzerVerzeichnisPflege(new LeeresVerzeichnis())),
            new Hasher(), code -> Optional.of("m_" + code.wert()), sperre);

    @Test
    void erstelltWebseitenschluesselMitFestemScopeUndSpeichertNurDenHash() {
        WebseitenSchluesselVerwaltung.Erstellt erstellt = verwaltung.erstellen("urinet", "wordpress", "lesen",
                "mitglieder", true);

        assertThat(erstellt.client().scope()).isEqualTo(ApiTokenScope.WEBSEITE_LESEN);
        assertThat(erstellt.client().webseite()).contains("wordpress");
        assertThat(erstellt.client().hoechsteStufe()).contains("mitglieder");
        assertThat(erstellt.client().absenderPruefen()).isTrue();
        assertThat(register.eintraege.get(erstellt.client().id()).hash())
                .isEqualTo("hash:" + erstellt.token().geheimnis());
    }

    @Test
    void erfassungErhaeltFestDieVorstandsstufe() {
        WebseitenSchluesselVerwaltung.Erstellt erstellt = verwaltung.erstellen("urinet", "wordpress", "erfassung",
                null, false);

        assertThat(erstellt.client().scope()).isEqualTo(ApiTokenScope.WEBSEITE_ERFASSUNG);
        assertThat(erstellt.client().hoechsteStufe()).contains("vorstand");
        assertThatThrownBy(() -> verwaltung.erstellen("urinet", "wordpress", "erfassung", "vorstand", false))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    @Test
    void rotationUebernimmtDieAngabenUndSetztAblaufDesAltenSchluessels() {
        WebseitenSchluesselVerwaltung.Erstellt alt = verwaltung.erstellen("urinet", "wordpress", "lesen",
                "oeffentlich", true);
        Instant vorRotation = Instant.now();

        WebseitenSchluesselVerwaltung.Erstellt neu = verwaltung.rotieren(alt.client().id(), Duration.ofDays(7));

        ApiClient abgelaufen = register.eintraege.get(alt.client().id()).client();
        assertThat(abgelaufen.gueltigBis()).isPresent();
        assertThat(abgelaufen.gueltigBis().orElseThrow().compareTo(vorRotation.plus(Duration.ofDays(7))))
                .isGreaterThanOrEqualTo(0);
        assertThat(neu.client().id()).isNotEqualTo(alt.client().id());
        assertThat(neu.client().webseite()).isEqualTo(alt.client().webseite());
        assertThat(neu.client().scope()).isEqualTo(alt.client().scope());
        assertThat(neu.client().hoechsteStufe()).isEqualTo(alt.client().hoechsteStufe());
        assertThat(neu.client().absenderPruefen()).isTrue();
        assertThat(neu.client().gueltigBis()).isEmpty();
    }

    @Test
    void rotationLaesstKeineUeberlappungUeberDreissigTageZu() {
        WebseitenSchluesselVerwaltung.Erstellt token = verwaltung.erstellen("urinet", "wordpress", "lesen",
                "oeffentlich", false);

        assertThatThrownBy(() -> verwaltung.rotieren(token.client().id(), Duration.ofDays(31)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    @Test
    void abgelaufenerOderSchonRotierterSchluesselLaesstSichNichtRotieren() {
        WebseitenSchluesselVerwaltung.Erstellt erstellt = verwaltung.erstellen("urinet", "wordpress", "lesen",
                "oeffentlich", false);
        String id = erstellt.client().id();
        verwaltung.rotieren(id, Duration.ZERO);
        Optional<Instant> ablauf = register.eintraege.get(id).client().gueltigBis();
        int anzahl = register.eintraege.size();

        // zweite Rotation, auch mit längerer Überlappung: weder verlängert noch ein weiterer Nachfolger
        assertThatThrownBy(() -> verwaltung.rotieren(id, Duration.ofDays(30)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
        assertThat(register.eintraege.get(id).client().gueltigBis()).isEqualTo(ablauf);
        assertThat(register.eintraege).hasSize(anzahl);
    }

    @Test
    void widerrufIstIdempotent() {
        String id = verwaltung.erstellen("urinet", "wordpress", "lesen", "oeffentlich", false).client().id();

        assertThat(verwaltung.widerrufen(id).aktiv()).isTrue();
        assertThat(verwaltung.widerrufen(id).aktiv()).isFalse();
        assertThat(register.eintraege.get(id).client().aktiv()).isFalse();
    }

    @Test
    void kennungMitLeerraumOderPropertiesTrennzeichenWirdAbgelehnt() {
        for (String kennung : List.of("vhf gsk", "vhf=gsk", "vhf:gsk", "#vhf", "vhf\\gsk", "", "a".repeat(101))) {
            assertThatThrownBy(() -> verwaltung.erstellen("urinet", kennung, "lesen", "oeffentlich", false))
                    .as(kennung).isInstanceOfSatisfying(FachFehler.class,
                            fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
        assertThat(WebseitenSchluesselKommando.lesen(List.of("erstellen", "urinet", "vhf gsk", "erfassung")))
                .isEmpty();
        assertThat(WebseitenSchluesselKommando.lesen(List.of("erstellen", "urinet", "vhf-gsk.de", "erfassung")))
                .isPresent();
    }

    @Test
    void kommandoGibtGeheimnisNurEinmalAufStdoutAus() {
        WebseitenSchluesselKommando kommando = new WebseitenSchluesselKommando(verwaltung);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int code = kommando.ausfuehren(List.of("erstellen", "urinet", "wordpress", "lesen", "--stufe",
                "oeffentlich", "--absender-pruefen"), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        ApiToken token = ApiToken.lesen(stdout.toString(StandardCharsets.UTF_8).strip()).orElseThrow();
        assertThat(code).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains(token.id(), "urinet", "wordpress", "webseite-lesen")
                .doesNotContain(token.geheimnis());
    }

    @Test
    void falscheBefehleUndStufenWerdenAbgelehnt() {
        assertThat(WebseitenSchluesselKommando.lesen(List.of("erstellen", "urinet", "wordpress", "erfassung",
                "--stufe", "vorstand"))).isEmpty();
        assertThat(WebseitenSchluesselKommando.lesen(List.of("rotieren", "abcdefgh1234", "--ueberlappung",
                "P31D"))).isEmpty();
        assertThat(WebseitenSchluesselKommando.lesen(List.of("rotieren", "abcdefgh1234"))).isPresent();
    }

    private static final class LeeresVerzeichnis implements BenutzerVerzeichnis {
        @Override
        public Optional<VerzeichnisEintrag> letzter(Optional<MandantCode> mandant, String benutzer) {
            return Optional.empty();
        }

        @Override
        public void einfuegen(Optional<MandantCode> mandant, Neu neu) {
        }

        @Override
        public List<VerzeichnisEintrag> liste() {
            return List.of();
        }

        @Override
        public void sperren(Optional<MandantCode> mandant, String benutzer, Sperre sperre) {
        }
    }

    private static final class Register implements ApiClientRegister {
        private final Map<String, Zugang> eintraege = new HashMap<>();

        @Override
        public Optional<Zugang> finden(String id) {
            return Optional.ofNullable(eintraege.get(id));
        }

        @Override
        public void anlegen(ApiClient client, String hash) {
            eintraege.put(client.id(), new Zugang(client, hash));
        }

        @Override
        public List<ApiClient> liste(String mandant) {
            return eintraege.values().stream().map(Zugang::client).filter(c -> c.mandant().equals(mandant)).toList();
        }

        @Override
        public boolean widerrufen(String mandant, String id) {
            Zugang zugang = eintraege.get(id);
            if (zugang == null || !zugang.client().mandant().equals(mandant)) {
                return false;
            }
            ApiClient c = zugang.client();
            eintraege.put(id, new Zugang(new ApiClient(c.id(), c.mandant(), c.name(), c.scope(), false,
                    c.erstellt(), c.zuletzt(), c.webseite(), c.hoechsteStufe(), c.gueltigBis(),
                    c.absenderPruefen()), zugang.hash()));
            return true;
        }

        @Override
        public boolean gueltigBisSetzen(String mandant, String id, Instant zeit) {
            Zugang zugang = eintraege.get(id);
            if (zugang == null || !zugang.client().mandant().equals(mandant) || !zugang.client().aktiv()
                    || zugang.client().gueltigBis().isPresent()) {
                return false;
            }
            ApiClient c = zugang.client();
            eintraege.put(id, new Zugang(new ApiClient(c.id(), c.mandant(), c.name(), c.scope(), c.aktiv(),
                    c.erstellt(), c.zuletzt(), c.webseite(), c.hoechsteStufe(), Optional.of(zeit),
                    c.absenderPruefen()), zugang.hash()));
            return true;
        }

        @Override
        public boolean apiTokenGueltigkeitSetzen(String mandant, String id, Optional<Instant> zeit, Instant jetzt) {
            return false;
        }

        @Override
        public boolean apiTokenAbgelaufen(String mandant, String id, Instant jetzt) {
            return false;
        }

        @Override
        public void zuletztSetzen(String id, Instant zeit) {
        }
    }

    private static final class Hasher implements GeheimnisHasher {
        @Override
        public String hashen(String geheimnis) {
            return "hash:" + geheimnis;
        }

        @Override
        public boolean pruefen(String geheimnis, String hash) {
            return hash.equals("hash:" + geheimnis);
        }
    }
}
