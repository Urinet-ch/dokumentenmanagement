package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.WissenImportSchritte;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class WissenPaketImportTest {
    @Inject WissenImportSchritte schritte;

    @BeforeEach
    void vorbereiten() throws Exception {
        schritte.vorbereiten();
    }

    @AfterEach
    void aufraeumen() throws Exception {
        schritte.aufraeumen();
    }

    @Test
    void importUndFreigabe() throws Exception {
        schritte.importieren();
        schritte.nurEntwurf();
        schritte.annehmen();
        schritte.freigegeben();
    }

    @Test
    void fehlendeQuelleUndNeupruefung() throws Exception {
        schritte.abweichung("unbekannt");
        schritte.spaetereQuelle();
    }

    @ParameterizedTest
    @CsvSource({"fremd,QUELLE_UNBEKANNT", "nicht markiert,QUELLE_UNBEKANNT", "PERSONAL,QUELLE_PERSONAL",
            "Auszug fehlt,AUSZUG_FEHLT", "Auszug falsch,AUSZUG_NICHT_GEFUNDEN",
            "Seitentext fehlt,AUSZUG_NICHT_PRUEFBAR"})
    void quellenabweichung(String fall, String code) throws Exception {
        schritte.abweichung(fall);
        schritte.blockiert(code);
    }

    @Test
    void wiederholungUndQuote() throws Exception {
        schritte.wiederholungUndQuote();
    }

    @Test
    void stufen() throws Exception {
        schritte.stufen();
    }

    @Test
    void abgleich() throws Exception {
        schritte.abgleich();
    }

    @Test
    void quelleVorAnnahme() throws Exception {
        schritte.quelleVorAnnahme();
    }

    @Test
    void rechte() throws Exception {
        schritte.rechte();
    }

    @Test
    void rollback() throws Exception {
        schritte.rollback();
    }

    @Test
    void zeitgrenze() throws Exception {
        schritte.zeitgrenze();
    }

    @Test
    void zeitgrenzeBeimSchreiben() throws Exception {
        schritte.zeitgrenzeBeimSchreiben();
    }

    @Test
    void eingangsformatUndLogs() throws Exception {
        schritte.eingangsformatUndLogs();
    }

    @Test
    void rundlaufLokalerExport() throws Exception {
        schritte.rundlaufLokalerExport();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1})
    void ungueltigeNeueBasis(long version) throws Exception {
        schritte.ungueltigeNeueBasis(version);
    }

    @Test
    void neueBasisNull() throws Exception {
        schritte.neueBasisNull();
        schritte.nurEntwurf();
    }

    @Test
    void negativeFremdeBasis() throws Exception {
        schritte.ungueltigeNeueBasis(-1, "fremde-quelle");
    }

    @Test
    void positiveBasisOhneHerkunft() throws Exception {
        schritte.ungueltigeNeueBasis(1, null);
    }
}
