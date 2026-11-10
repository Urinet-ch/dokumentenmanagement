package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragAufraeumen;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 1.6, 5.4, 5.6 und 5.7 (Aufgabe 2.5); Aufträge werden in der Reihenfolge des Einreihens nummeriert. */
public class AuftragsschlangeSchritte {

    @Inject
    AuftragTestHilfe hilfe;

    @Inject
    AuftragAufraeumen aufraeumen;

    private final List<Long> auftraege = new ArrayList<>();
    private CompletableFuture<Void> lauf;

    @Angenommen("die Auftragsschlange ist leer")
    public void leer() throws Exception {
        hilfe.vorbereiten();
        auftraege.clear();
    }

    @Angenommen("im Mandanten «{word}» ist ein Auftrag «{word}» eingereiht")
    public void eingereiht(String mandant, String typ) throws Exception {
        auftraege.add(hilfe.einreihen(mandant, new AuftragTyp(typ)));
    }

    @Wenn("der Worker alle fälligen Aufträge bearbeitet")
    public void bearbeiten() throws Exception {
        hilfe.abarbeiten();
    }

    @Wenn("der Auftrag {int} wieder fällig ist")
    public void faellig(int nr) throws Exception {
        hilfe.aendern(id(nr), "nicht_vor = now()");
    }

    @Wenn("der Worker den Auftrag startet und {int} Millisekunden wartet")
    public void startenUndWarten(int ms) throws Exception {
        lauf = hilfe.einenStarten();
        Thread.sleep(ms);
    }

    @Angenommen("der Auftrag {int} läuft seit einer Stunde ohne Lebenszeichen")
    public void verwaist(int nr) throws Exception {
        hilfe.aendern(id(nr), "status = 'LAEUFT', gestartet = now() - interval '1 hour', "
                + "lebenszeichen = now() - interval '1 hour'");
    }

    @Angenommen("der Auftrag {int} läuft seit einer Stunde mit frischem Lebenszeichen")
    public void frisch(int nr) throws Exception {
        hilfe.aendern(id(nr), "status = 'LAEUFT', gestartet = now() - interval '1 hour', lebenszeichen = now()");
    }

    @Wenn("der Aufräumer läuft")
    public void aufraeumen() {
        aufraeumen.aufraeumen();
    }

    @Dann("ist der Auftrag {int} «{word}» und lief im Schema «{word}»")
    public void liefImSchema(int nr, String status, String schema) throws Exception {
        assertThat(hilfe.zeile(id(nr)).status()).isEqualTo(status);
        assertThat(TestHandler.SCHEMAS).containsEntry(id(nr), schema);
    }

    @Dann("ist der Auftrag {int} «{word}» nach {int} Versuch(en) mit Fehlertext «{word}» und wartet {int} Minuten")
    public void wartet(int nr, String status, int versuche, String art, int minuten) throws Exception {
        AuftragTestHilfe.Zeile zeile = fehlversuch(nr, status, versuche, art);
        assertThat(zeile.wartezeitSekunden()).isBetween(minuten * 60L - 20, minuten * 60L);
    }

    @Dann("ist der Auftrag {int} «{word}» nach {int} Versuch(en) mit Fehlertext «{word}»")
    public void endgueltig(int nr, String status, int versuche, String art) throws Exception {
        assertThat(fehlversuch(nr, status, versuche, art).beendet()).isTrue();
    }

    @Dann("der Handler hat nach dem letzten Fehlversuch im Mandanten «{word}» aufgeräumt")
    public void nachLetztem(String mandant) {
        assertThat(TestHandler.NACH_LETZTEM).containsEntry(id(1), new MandantCode(mandant));
    }

    @Dann("wurde der Auftrag {int} abgebrochen")
    public void abgebrochen(int nr) {
        assertThat(TestHandler.UNTERBROCHEN).contains(id(nr));
    }

    @Dann("ist der Auftrag {int} «{word}» mit einem Lebenszeichen nach dem Start")
    public void lebenszeichen(int nr, String status) throws Exception {
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id(nr));
        assertThat(zeile.status()).isEqualTo(status);
        assertThat(zeile.lebenszeichenNachStart()).isTrue();
        if (lauf != null) {
            // Zeitgrenze 1 s: der Lauf endet, bevor das nächste Szenario beginnt
            lauf.get(10, TimeUnit.SECONDS);
            lauf = null;
        }
    }

    private AuftragTestHilfe.Zeile fehlversuch(int nr, String status, int versuche, String art) throws Exception {
        AuftragTestHilfe.Zeile zeile = hilfe.zeile(id(nr));
        assertThat(zeile.status()).isEqualTo(status);
        assertThat(zeile.versuche()).isEqualTo(versuche);
        assertThat(zeile.fehler()).matches(art + " fehler-id=[0-9a-f-]{36}");
        return zeile;
    }

    private long id(int nr) {
        return auftraege.get(nr - 1);
    }
}
