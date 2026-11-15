package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Vom Modell verändert abgeschriebene IDs: nur Treffer des eigenen Laufs, nur bei eindeutiger Übereinstimmung. */
class GeseheneIdsTest {

    private static final String ECHT = "f5c8fc52-af7e-4e1e-9023-bc07333cd533";
    private final Set<UUID> gesehen = Set.of(UUID.fromString(ECHT));

    @Test
    void einZeichenAbweichungWirdDemTrefferZugeordnet() {
        assertThat(GeseheneIds.zuordnen("f5c8fc52-af7e-4e10-9023-bc07333cd533", gesehen)).isEqualTo(ECHT);
    }

    @Test
    void gleichesPraefixVonAchtZeichenWirdZugeordnet() {
        assertThat(GeseheneIds.zuordnen("f5c8fc52-0000-0000-0000-000000000000", gesehen)).isEqualTo(ECHT);
    }

    @Test
    void idAusKeinemTrefferDesLaufsBleibtUnveraendert() {
        String fremd = "11111111-af7e-4e1e-9023-bc07333cd999";
        assertThat(GeseheneIds.zuordnen(fremd, gesehen)).isEqualTo(fremd);
        assertThat(GeseheneIds.zuordnen("Quittung", gesehen)).isEqualTo("Quittung");
        assertThat(GeseheneIds.zuordnen(ECHT.replace("f5c8fc52", "a5c8fc5a"), Set.of())).isEqualTo(
                ECHT.replace("f5c8fc52", "a5c8fc5a"));
    }

    @Test
    void mehrereKandidatenSindNichtEindeutig() {
        UUID zweite = UUID.fromString("f5c8fc52-af7e-4e1f-9023-bc07333cd533");
        String eingabe = "f5c8fc5x-af7e-4e10-9023-bc07333cd533";
        assertThat(GeseheneIds.zuordnen(eingabe, Set.of(UUID.fromString(ECHT), zweite))).isEqualTo(eingabe);
    }

    @Test
    void exakteGueltigeIdBleibt() {
        assertThat(GeseheneIds.zuordnen(ECHT, gesehen)).isEqualTo(ECHT);
        assertThat(GeseheneIds.zuordnen(null, gesehen)).isNull();
    }

    @Test
    void registerHaeltGeseheneNurFuerDenLaufUndVergisstSieAmEnde() {
        WerkzeugKontextRegister register = new WerkzeugKontextRegister();
        WerkzeugKontext k = WerkzeugKontext.fuer(new MandantCode("urinet"), new Benutzer("a", Rolle.INHABER), null);
        register.festhalten("lauf1", k);
        register.festhalten("lauf2", k);
        register.gesehen("lauf1", gesehen);
        assertThat(register.gesehene("lauf1")).isEqualTo(gesehen);
        assertThat(register.gesehene("lauf2")).isEmpty();
        register.gesehen("unbekannt", gesehen);
        assertThat(register.gesehene("unbekannt")).isEmpty();
        register.entfernen("lauf1");
        assertThat(register.gesehene("lauf1")).isEmpty();
    }
}
