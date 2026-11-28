package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.integration.application.mail.PostfachNichtErreichbar;
import ch.urinet.archiv.integration.application.mail.PostfachPort;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Führt einen Gmail-Quelllauf ausschliesslich lesend über IMAPS aus (Spec 09, Requirement 7). */
@ApplicationScoped
public class GmailImportHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(GmailImportHandler.class);
    private static final AuftragTyp TYP = new AuftragTyp("QUELLLAUF_GMAIL");

    private final QuellenVerwaltung quellen;
    private final GmailNachrichtVerarbeiten nachrichten;
    private final PostfachPort postfach;

    public GmailImportHandler(QuellenVerwaltung quellen, GmailNachrichtVerarbeiten nachrichten,
                              PostfachPort postfach) {
        this.quellen = quellen;
        this.nachrichten = nachrichten;
        this.postfach = postfach;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) {
        UUID laufId = auftrag.nutzlast().uuid("laufId")
                .orElseThrow(() -> new IllegalArgumentException("Gmail-Auftrag ohne Quelllauf"));
        boolean trocken = auftrag.nutzlast().schalter("trocken").orElse(false);
        QuellenVerwaltung.GmailVerbindung verbindung;
        try {
            verbindung = quellen.gmailVerbindung(laufId);
        } catch (FachFehler e) {
            abschliessen(laufId, trocken, 0, "QUELLE_KONFIGURATION: Gmail-Quelle oder App-Passwort ist ungültig.",
                    true);
            return;
        }

        int[] uebersprungen = {0};
        PostfachPort.Abruf abruf;
        try {
            abruf = postfach.abrufenNurLesend(verbindung.konfiguration().postfach(),
                    verbindung.appPasswort(), verbindung.konfiguration().label(),
                    verbindung.konfiguration().seit(), verbindung.konfiguration().maxJeLauf(), nachricht -> {
                        if (verbindung.art() == LaufArt.TROCKEN) {
                            uebersprungen[0]++;
                            return PostfachPort.Ziel.ARCHIVIERT;
                        }
                        uebersprungen[0] += nachrichten.verarbeiten(auftrag.mandant(), verbindung.code(), laufId,
                                nachricht, verbindung.konfiguration().ohneAnhang());
                        return PostfachPort.Ziel.ARCHIVIERT;
                    });
        } catch (PostfachNichtErreichbar e) {
            boolean anmeldung = "Anmeldung".equals(e.stelle());
            String meldung = anmeldung
                    ? "QUELLE_ANMELDUNG: Anmeldung bei Gmail fehlgeschlagen: App-Passwort ungültig oder "
                    + "Zwei-Faktor nicht aktiv."
                    : "QUELLE_IMAP_FEHLER: Gmail-Postfach nicht erreichbar. Verbindung und Label prüfen.";
            abschliessen(laufId, trocken, uebersprungen[0], meldung, true);
            LOG.warnf("Gmail-Import: Quelllauf abgebrochen, lauf=%s grund=%s", laufId,
                    anmeldung ? "QUELLE_ANMELDUNG" : "QUELLE_IMAP_FEHLER");
            return;
        } catch (UnsupportedOperationException e) {
            abschliessen(laufId, trocken, uebersprungen[0],
                    "QUELLE_IMAP_FEHLER: Der konfigurierte Postfach-Adapter unterstützt den Gmail-Import nicht.",
                    true);
            return;
        }

        if (verbindung.art() == LaufArt.TROCKEN) {
            int gefunden = abruf.verarbeitet() + abruf.gescheitert();
            Zaehler zaehler = new Zaehler(gefunden, 0, 0, 0, abruf.verarbeitet(), abruf.gescheitert());
            List<String> meldungen = abruf.gescheitert() == 0 ? List.of()
                    : List.of("GMAIL_NACHRICHT_FEHLER: Mindestens eine Nachricht konnte nicht gelesen werden.");
            quellen.laufAbschliessen(laufId, Optional.of(zaehler), 0, meldungen, abruf.gescheitert() > 0);
            return;
        }
        quellen.gmailFehlerVerbuchen(laufId, abruf.gescheitert());
        List<String> meldungen = abruf.gescheitert() == 0 ? List.of()
                : List.of("GMAIL_NACHRICHT_FEHLER: Mindestens eine Nachricht konnte nicht verarbeitet werden; "
                + "Lauf erneut starten.");
        abschliessen(laufId, false, uebersprungen[0], meldungen, abruf.gescheitert() > 0);
    }

    void abschliessen(UUID laufId, boolean trocken, int uebersprungen, String meldung, boolean abgebrochen) {
        abschliessen(laufId, trocken, uebersprungen, List.of(meldung), abgebrochen);
    }

    void abschliessen(UUID laufId, boolean trocken, int uebersprungen, List<String> meldungen,
                      boolean abgebrochen) {
        Optional<Zaehler> zaehler = trocken
                ? Optional.of(new Zaehler(uebersprungen, 0, 0, 0, uebersprungen, 0))
                : Optional.empty();
        QuellLauf lauf = quellen.laufAbschliessen(laufId, zaehler, trocken ? 0 : uebersprungen, meldungen,
                abgebrochen);
        LOG.infof("Gmail-Import: Quelllauf abgeschlossen, lauf=%s status=%s gefunden=%d neu=%d fehler=%d "
                        + "uebersprungen=%d", laufId, lauf.status(), lauf.zaehler().gefunden(), lauf.zaehler().neu(),
                lauf.zaehler().fehler(), lauf.zaehler().uebersprungen());
    }
}
