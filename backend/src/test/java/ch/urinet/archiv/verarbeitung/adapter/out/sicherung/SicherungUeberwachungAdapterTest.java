package ch.urinet.archiv.verarbeitung.adapter.out.sicherung;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SicherungUeberwachungAdapterTest {

    private static final Instant JETZT = Instant.parse("2026-11-15T12:00:00Z");
    private static final Duration FRIST = Duration.ofHours(36);

    @Test
    void leeresRegisterMeldetVierGlobaleUndZweiMandanteneintraege() {
        SicherungUeberwachungAdapter adapter = adapter(List.of(mandant("alpha", true), mandant("inaktiv", false)),
                new HashMap<>(), true);

        List<Befund> befunde = adapter.befunde().orElseThrow();

        assertThat(befunde).hasSize(6).allSatisfy(b -> assertThat(b.status()).isEqualTo("FEHLT"));
        assertThat(befunde).extracting(Befund::gegenstand).containsExactlyInAnyOrder(
                "db", "keycloak", "realm", "schluessel", "bucket-alpha", "inventar-alpha");
    }

    @Test
    void fristGrenztZwischen35Stunden59MinutenUnd36Stunden1Minute() {
        Map<Schluessel, Instant> eintraege = new HashMap<>();
        for (SicherungsArt art : SicherungsArt.values()) {
            Optional<MandantCode> code = art.mandantPflicht() ? Optional.of(new MandantCode("alpha"))
                    : Optional.empty();
            eintraege.put(new Schluessel(art, code.map(MandantCode::wert).orElse(null)),
                    JETZT.minus(Duration.ofHours(35).plusMinutes(59)));
        }
        SicherungUeberwachungAdapter adapter = adapter(List.of(mandant("alpha", true)), eintraege, true);

        assertThat(adapter.befunde()).hasValue(List.of());

        eintraege.put(new Schluessel(SicherungsArt.DB, null), JETZT.minus(FRIST));
        assertThat(adapter.befunde()).hasValue(List.of());

        eintraege.put(new Schluessel(SicherungsArt.DB, null), JETZT.minus(Duration.ofHours(36).plusMinutes(1)));
        assertThat(adapter.befunde().orElseThrow())
                .containsExactly(new Befund(Befund.Quelle.SICHERUNG, "db", "ZU_ALT"));
    }

    @Test
    void zaehltNurAktiveMandantenUndMeldetVierPlusJeZweiBefunde() {
        SicherungUeberwachungAdapter adapter = adapter(
                List.of(mandant("alpha", true), mandant("bravo", true), mandant("inaktiv", false)),
                new HashMap<>(), true);

        assertThat(adapter.befunde().orElseThrow()).extracting(Befund::gegenstand)
                .containsExactlyInAnyOrder("db", "keycloak", "realm", "schluessel",
                        "bucket-alpha", "inventar-alpha", "bucket-bravo", "inventar-bravo")
                .doesNotContain("bucket-inaktiv", "inventar-inaktiv");
    }

    @Test
    void deaktiviertLiefertKeinErgebnisDamitAlarmeUnveraendertBleiben() {
        SicherungUeberwachungAdapter adapter = adapter(List.of(mandant("alpha", true)), new HashMap<>(), false);

        assertThat(adapter.befunde()).isEmpty();
        assertThat(adapter.befunde()).isEmpty();
    }

    private static SicherungUeberwachungAdapter adapter(List<Mandant> mandanten,
                                                        Map<Schluessel, Instant> eintraege, boolean aktiv) {
        SicherungExportSpeicher speicher = new SicherungExportSpeicher() {
            @Override
            public void festhalten(SicherungsArt art, Optional<MandantCode> mandant, String sha256) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Instant> juengsterEintrag(SicherungsArt art, Optional<MandantCode> mandant) {
                return Optional.ofNullable(eintraege.get(new Schluessel(art, mandant.map(MandantCode::wert)
                        .orElse(null))));
            }
        };
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return mandanten.stream().anyMatch(m -> m.code().equals(code));
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return mandanten.stream().filter(Mandant::aktiv).toList();
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                return Optional.empty();
            }
        };
        return new SicherungUeberwachungAdapter(speicher, register, FRIST, aktiv,
                Clock.fixed(JETZT, ZoneOffset.UTC));
    }

    private static Mandant mandant(String code, boolean aktiv) {
        return new Mandant(new MandantCode(code), code, "m_" + code, "bucket-" + code,
                Geschaeftsjahresende.STANDARD, aktiv);
    }

    private record Schluessel(SicherungsArt art, String mandant) {
    }
}
