package ch.urinet.archiv.sicherheit.application;

/**
 * Driven Port für Zugangsdaten in der Datenbank (Spec 02, design.md «Verschluesselung»): AES-256-GCM mit dem Schlüssel
 * aus der Secret-Datei {@code archiv_data_key}. Ohne Schlüssel ist der Port nicht {@link #verfuegbar()}; dann
 * scheitern {@link #verschluesseln} und {@link #entschluesseln} mit {@link IllegalStateException}.
 */
public interface Verschluesselung {

    boolean verfuegbar();

    /** @param bezug bindet das Chiffrat an seine Stelle (z. B. {@code integration_mail.passwort:urinet}) */
    byte[] verschluesseln(byte[] klartext, String bezug);

    /** @throws IllegalArgumentException wenn das Chiffrat nicht zu Schlüssel und Bezug passt */
    byte[] entschluesseln(byte[] chiffrat, String bezug);
}
