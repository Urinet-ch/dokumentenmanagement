package ch.urinet.archiv.integration.application.mail;

/** Port zum Lesen der gespeicherten Gmail-Quellenkonfiguration. */
@FunctionalInterface
public interface GmailLabelKonfigurationLeser {

    GmailLabelKonfiguration lesen(String json);
}
