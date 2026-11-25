package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static ch.urinet.archiv.benutzer.application.IdentitaetsDienst.Benutzer;
import static ch.urinet.archiv.benutzer.application.IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP;
import static ch.urinet.archiv.benutzer.application.IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL;
import static org.assertj.core.api.Assertions.assertThat;

class BenutzerEtagTest {

    @Test
    void tagAendertSichBeiJederRelevantenBenutzeraenderung() {
        Benutzer basis = benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                Set.of(VERIFY_EMAIL), false, false);
        String etag = BenutzerEtag.berechnen(basis, BenutzerStatus.EINGELADEN);
        assertThat(etag).matches("[0-9a-f]{64}");
        assertThat(BenutzerEtag.berechnen(basis, BenutzerStatus.AKTIV)).isNotEqualTo(etag);
        for (Benutzer geaendert : Set.of(
                benutzer("anderer", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "c@d", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "a@b", "Neu", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "a@b", "Vor", "Neu", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "a@b", "Vor", "Nach", false, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.INHABER),
                        Set.of(VERIFY_EMAIL), false, false),
                benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(CONFIGURE_TOTP), false, false),
                benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), true, false),
                benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                        Set.of(VERIFY_EMAIL), false, true))) {
            assertThat(BenutzerEtag.berechnen(geaendert, BenutzerStatus.EINGELADEN)).isNotEqualTo(etag);
        }
    }

    @Test
    void reihenfolgeUndFeldgrenzenSindEindeutig() {
        Benutzer erster = benutzer("user", null, "ab", "c", true,
                Set.of(Rolle.INHABER, Rolle.MITARBEITER), Set.of(VERIFY_EMAIL, CONFIGURE_TOTP), false, false);
        Benutzer gleicher = benutzer("user", null, "ab", "c", true,
                Set.of(Rolle.MITARBEITER, Rolle.INHABER), Set.of(CONFIGURE_TOTP, VERIFY_EMAIL), false, false);
        Benutzer andereGrenzen = benutzer("user", null, "a", "bc", true,
                Set.of(Rolle.INHABER, Rolle.MITARBEITER), Set.of(VERIFY_EMAIL, CONFIGURE_TOTP), false, false);
        Benutzer leerStattNull = benutzer("user", "", "ab", "c", true,
                Set.of(Rolle.INHABER, Rolle.MITARBEITER), Set.of(VERIFY_EMAIL, CONFIGURE_TOTP), false, false);
        String etag = BenutzerEtag.berechnen(erster, BenutzerStatus.AKTIV);
        assertThat(BenutzerEtag.berechnen(gleicher, BenutzerStatus.AKTIV)).isEqualTo(etag);
        assertThat(BenutzerEtag.berechnen(andereGrenzen, BenutzerStatus.AKTIV)).isNotEqualTo(etag);
        assertThat(BenutzerEtag.berechnen(leerStattNull, BenutzerStatus.AKTIV)).isNotEqualTo(etag);
    }

    @Test
    void passkeyAnzahlBeeinflusstDenEtag() {
        var ohne = new Benutzer("id", "user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                Set.of(), false, false, 0);
        var mit = new Benutzer("id", "user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                Set.of(), false, false, 1);
        assertThat(BenutzerEtag.berechnen(mit, BenutzerStatus.AKTIV))
                .isNotEqualTo(BenutzerEtag.berechnen(ohne, BenutzerStatus.AKTIV));
    }

    @Test
    void einladungsablaufBeeinflusstDenEtag() {
        Benutzer basis = benutzer("user", "a@b", "Vor", "Nach", true, Set.of(Rolle.MITARBEITER),
                Set.of(VERIFY_EMAIL), false, false);
        Instant ablauf = Instant.parse("2026-11-26T20:00:00Z");
        assertThat(BenutzerEtag.berechnen(basis, BenutzerStatus.EINGELADEN, ablauf))
                .isNotEqualTo(BenutzerEtag.berechnen(basis, BenutzerStatus.EINGELADEN,
                        ablauf.plusSeconds(1)));
    }

    private static Benutzer benutzer(String name, String email, String vorname, String nachname, boolean aktiv,
                                     Set<Rolle> rollen, Set<IdentitaetsDienst.ErforderlicheAktion> aktionen,
                                     boolean totp, boolean google) {
        return new Benutzer("synthetische-id", name, email, vorname, nachname, aktiv, rollen, aktionen, totp, google);
    }
}
