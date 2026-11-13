package ch.urinet.archiv.integration.domain.mail;

/** Ergebnis einer Nachricht in {@code mail_eingang} (Spec 02, Req 1.4, 1.5). */
public enum MailStatus {
    /** von einem zugelassenen Absender, Dokumente erfasst (oder alle schon als Dublette vorhanden) */
    ERFASST,
    /** Absender nicht auf der Zulassungsliste: nichts erfasst, Nachricht im Ordner {@code Quarantaene} */
    QUARANTAENE
}
