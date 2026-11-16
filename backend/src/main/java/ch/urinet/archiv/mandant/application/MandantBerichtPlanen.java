package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.OptionalLong;

/** Plant für jeden aktiven Mandanten den Bericht des Vormonats. */
@ApplicationScoped
public class MandantBerichtPlanen {

    private static final Logger LOG = Logger.getLogger(MandantBerichtPlanen.class);

    private final MandantRegister register;
    private final MandantKontext kontext;
    private final MandantBerichtEinreihen einreihen;

    public MandantBerichtPlanen(MandantRegister register, MandantKontext kontext,
                                MandantBerichtEinreihen einreihen) {
        this.register = register;
        this.kontext = kontext;
        this.einreihen = einreihen;
    }

    public void planen(Instant jetzt) {
        MandantBerichtFenster fenster = MandantBerichtFenster.vormonat(jetzt);
        for (Mandant mandant : register.aktive()) {
            try {
                OptionalLong id = kontext.mit(mandant.code(), () -> einreihen.einreihen(mandant.code(), fenster));
                id.ifPresent(auftrag -> LOG.infof("Monatsbericht %s für Mandant %s eingereiht (Auftrag %d)",
                        fenster.monat(), mandant.code().wert(), auftrag));
            } catch (RuntimeException e) {
                LOG.errorf("Monatsbericht %s für Mandant %s nicht eingereiht, ursache=%s", fenster.monat(),
                        mandant.code().wert(), Ursachen.kette(e));
            }
        }
    }
}
