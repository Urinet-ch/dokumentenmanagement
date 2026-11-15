package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void nummernOrdnenNurNummerierteTrefferDesGespraechsZu() {
        UUID zweite = UUID.fromString("c2d81843-f193-43f9-8675-cf16609e8a7e");
        assertThat(GeseheneIds.zuordnen("2", Set.of(zweite), List.of(UUID.fromString(ECHT), zweite)))
                .isEqualTo(zweite.toString());
        assertThat(GeseheneIds.zuordnen("3", Set.of(zweite), List.of(UUID.fromString(ECHT), zweite))).isEqualTo("3");
        assertThat(GeseheneIds.zuordnen("1", Set.of(), List.of())).isEqualTo("1");
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

    @Test
    void registerUebernimmtGesprächstrefferUndVergibtNummernFortlaufend() {
        WerkzeugKontextRegister register = new WerkzeugKontextRegister();
        WerkzeugKontext k = WerkzeugKontext.fuer(new MandantCode("urinet"), new Benutzer("a", Rolle.INHABER), null);
        UUID erste = UUID.fromString(ECHT);
        UUID zweite = UUID.fromString("c2d81843-f193-43f9-8675-cf16609e8a7e");
        UUID ausAnderemWerkzeug = UUID.fromString("a7b71aca-ddf0-477e-a5c9-840d3ac54444");
        register.festhalten("gespraech1", k, List.of(erste, ausAnderemWerkzeug), List.of(erste));
        register.festhalten("gespraech2", k, List.of(), List.of());

        assertThat(register.nummerieren("gespraech1", List.of(erste, zweite)))
                .containsEntry(erste, 1).containsEntry(zweite, 2);
        assertThat(register.nummerierte("gespraech1")).containsExactly(erste, zweite);
        assertThat(register.gesehene("gespraech1")).contains(erste, zweite, ausAnderemWerkzeug);
        assertThat(register.nummerierte("gespraech2")).isEmpty();
        assertThat(register.gesehene("gespraech2")).isEmpty();
    }

    @Test
    void nummerMitLeerzeichenAmRandWirdZugeordnet() {
        UUID zweite = UUID.fromString("c2d81843-f193-43f9-8675-cf16609e8a7e");
        List<UUID> treffer = List.of(UUID.fromString(ECHT), zweite);
        assertThat(GeseheneIds.zuordnen(" 2", Set.of(zweite), treffer)).isEqualTo(zweite.toString());
        assertThat(GeseheneIds.zuordnen("2 ", Set.of(zweite), treffer)).isEqualTo(zweite.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "02", "1.0", "1e1", "+1", "١", "99999999999999999999", "", "   ", "eins"})
    void keineGueltigeNummerBleibtUnveraendert(String eingabe) {
        List<UUID> treffer = List.of(UUID.fromString(ECHT));
        assertThat(GeseheneIds.zuordnen(eingabe, Set.of(UUID.fromString(ECHT)), treffer)).isEqualTo(eingabe);
    }

    @Test
    void nummerOhneNummerierteTrefferWirdNieAufEineGeseheneIdAbgebildet() {
        assertThat(GeseheneIds.zuordnen("1", gesehen, List.of())).isEqualTo("1");
        assertThat(GeseheneIds.zuordnen("1", gesehen)).isEqualTo("1");
    }

    @Test
    void zweiZeichenAbweichungWerdenZugeordnetDreiNicht() {
        // der Präfix aus acht Zeichen stimmt nicht mehr überein, es zählen nur die abweichenden Zeichen
        String zwei = "a5c8fc52-af7e-4e1e-9023-bc07333cd53x";
        String drei = "a5c8fc52-af7e-4e1x-9023-bc07333cd53x";
        assertThat(GeseheneIds.zuordnen(zwei, gesehen)).isEqualTo(ECHT);
        assertThat(GeseheneIds.zuordnen(drei, gesehen)).isEqualTo(drei);
    }

    @Test
    void falscheLaengeUndGrossschreibungVerhaltenSichWieErwartet() {
        assertThat(GeseheneIds.zuordnen(ECHT.substring(1), gesehen)).isEqualTo(ECHT.substring(1));
        assertThat(GeseheneIds.zuordnen(ECHT + "0", gesehen)).isEqualTo(ECHT + "0");
        assertThat(GeseheneIds.zuordnen(" " + ECHT.toUpperCase() + " ", gesehen)).isEqualTo(" " + ECHT.toUpperCase() + " ");
    }

    @Test
    void nummerUndGeseheneIdsKreuzenSichNichtZwischenGespraechen() {
        UUID fremd = UUID.fromString("c2d81843-f193-43f9-8675-cf16609e8a7e");
        // «1» ist die erste Nummer dieses Gesprächs, nicht die erste gesehene ID eines anderen
        assertThat(GeseheneIds.zuordnen("1", Set.of(fremd, UUID.fromString(ECHT)), List.of(UUID.fromString(ECHT))))
                .isEqualTo(ECHT);
    }

    @Test
    void parallelNummerierteTrefferBekommenEindeutigeLueckenloseNummern() throws Exception {
        WerkzeugKontextRegister register = new WerkzeugKontextRegister();
        register.festhalten("g", WerkzeugKontext.fuer(new MandantCode("urinet"), new Benutzer("a", Rolle.INHABER), null));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(new UUID(0xAB, i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> laeufe = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int start = t * 25;
                // jeder Thread nummeriert einen Ausschnitt und alle gemeinsam die Mitte doppelt
                laeufe.add(pool.submit(() -> register.nummerieren("g", ids.subList(start, Math.min(200, start + 60)))));
            }
            for (Future<?> lauf : laeufe) {
                lauf.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<UUID> nummeriert = register.nummerierte("g");
        assertThat(nummeriert).hasSize(200).doesNotHaveDuplicates();
        for (int i = 0; i < nummeriert.size(); i++) {
            UUID id = nummeriert.get(i);
            int erwartet = i + 1;
            assertThat(register.nummerieren("g", List.of(id))).containsEntry(id, erwartet);
        }
    }
}
