package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Objects;
import java.util.Optional;

/** Driven Port zur Tabelle {@code integration_mail} eines Mandanten (Spec 02, design.md «Data Models»). */
public interface MailKonfigurationPort {

    Optional<Einstellung> laden(MandantCode mandant);

    /** Legt das Postfach an oder ersetzt es; danach aktiv und ohne gezählte Fehlschläge. */
    void speichern(MandantCode mandant, Postfach postfach, byte[] passwortVerschluesselt, Zulassungsliste zulassung);

    /** @return {@code true}, wenn es ein Postfach gab */
    boolean abschalten(MandantCode mandant);

    /** Zählt einen gescheiterten Abruf und liefert die Anzahl in Folge. */
    int fehlschlagZaehlen(MandantCode mandant);

    /** Ein Abruf ist gelungen: Zähler auf 0. */
    void erfolgreich(MandantCode mandant);

    /** Zeile von {@code integration_mail}; das Passwort nur verschlüsselt. */
    record Einstellung(Postfach postfach, byte[] passwortVerschluesselt, Zulassungsliste zulassung, boolean aktiv) {
        public Einstellung {
            Objects.requireNonNull(postfach, "postfach");
            Objects.requireNonNull(passwortVerschluesselt, "passwortVerschluesselt");
            Objects.requireNonNull(zulassung, "zulassung");
        }

        @Override
        public String toString() {
            return "Einstellung[postfach=" + postfach + ", aktiv=" + aktiv + "]";
        }
    }
}
