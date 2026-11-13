package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port zur Tabelle {@code mail_eingang} (Spec 02, Req 1.5): je Message-ID eine Zeile. In derselben Transaktion
 * wie die Erfassung, damit eine Nachricht nie zweimal erfasst wird.
 */
public interface MailEingangRegister {

    Optional<MailStatus> status(MandantCode mandant, String messageId);

    /** @return {@code false}, wenn die Message-ID schon eingetragen ist (dann bleibt alles unverändert) */
    boolean eintragen(MandantCode mandant, String messageId, Optional<String> absender, Optional<String> betreff,
                      MailStatus status);

    /**
     * Setzt eine Mail aus der Quarantäne auf {@code ERFASST}, wenn ihr Absender inzwischen zugelassen ist.
     *
     * @return {@code false}, wenn sie nicht (mehr) in der Quarantäne steht
     */
    boolean ausQuarantaene(MandantCode mandant, String messageId);

    void dokumenteSetzen(MandantCode mandant, String messageId, List<UUID> dokumente);
}
