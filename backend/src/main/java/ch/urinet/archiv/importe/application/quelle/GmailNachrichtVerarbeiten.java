package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.integration.application.mail.MailErfassen;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Nachricht;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** Verarbeitet eine Gmail-Nachricht samt Dokumenten, Quellenzuordnung und Quelllaufzählern atomar. */
@ApplicationScoped
public class GmailNachrichtVerarbeiten {

    private final QuellLaufZuordnung quellLaeufe;
    private final MailErfassen mailErfassen;

    public GmailNachrichtVerarbeiten(QuellLaufZuordnung quellLaeufe, MailErfassen mailErfassen) {
        this.quellLaeufe = quellLaeufe;
        this.mailErfassen = mailErfassen;
    }

    @Transactional
    public int verarbeiten(MandantCode mandant, String quelle, UUID laufId, Nachricht nachricht, boolean ohneAnhang) {
        QuellLaufZuordnung.Ziel ziel = quellLaeufe.bestimmen(quelle, laufId.toString()).orElseThrow();
        MailErfassen.GmailErgebnis ergebnis = mailErfassen.erfassenGmail(mandant, nachricht, ohneAnhang);
        ArrayList<QuellLaufZuordnung.Eintrag> zaehlbar = new ArrayList<>();
        ergebnis.dokumente().forEach(id -> zaehlbar.add(new QuellLaufZuordnung.Eintrag(
                QuellLaufZuordnung.Ergebnis.NEU, Optional.of(id))));
        for (int i = 0; i < ergebnis.dublettePruefsumme(); i++) {
            zaehlbar.add(new QuellLaufZuordnung.Eintrag(QuellLaufZuordnung.Ergebnis.DUBLETTE,
                    Optional.empty()));
        }
        for (int i = 0; i < ergebnis.fehler(); i++) {
            zaehlbar.add(new QuellLaufZuordnung.Eintrag(QuellLaufZuordnung.Ergebnis.ABGELEHNT,
                    Optional.empty()));
        }
        quellLaeufe.verbuchen(ziel, zaehlbar);
        return ergebnis.uebersprungen();
    }
}
