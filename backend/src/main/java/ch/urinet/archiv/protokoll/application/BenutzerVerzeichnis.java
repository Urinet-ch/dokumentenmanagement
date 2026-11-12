package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Driven Port: Tabelle {@code benutzer_verzeichnis} eines Mandanten (Req 10.10), nur Lesen und Einfügen. Ohne
 * {@code mandant} gilt der Mandant der Anfrage (Anmeldung oder Mandanten-Kontext), sonst der angegebene.
 */
public interface BenutzerVerzeichnis {

    /** Die zuletzt eingefügte Zeile der Kennung. */
    Optional<VerzeichnisEintrag> letzter(Optional<MandantCode> mandant, String benutzer);

    /** Fügt eine Zeile ein; {@code zeit} und, wo nicht angegeben, {@code gueltig_von} setzt die Datenbank. */
    void einfuegen(Optional<MandantCode> mandant, Neu neu);

    /** Alle Zeilen des Mandanten der Anfrage, je Kennung in der Reihenfolge des Einfügens. */
    List<VerzeichnisEintrag> liste();

    /**
     * Eine neue Zeile. {@code gueltigVon} leer heisst «jetzt» (Zeit der Datenbank); {@code beenden} setzt
     * {@code gueltig_bis} auf «jetzt».
     */
    record Neu(String benutzer, Optional<String> name, Optional<String> rolle, Optional<Instant> gueltigVon,
               boolean beenden, VerzeichnisEintrag.Anlass anlass, Optional<String> erfasstVon) {

        public Neu {
            Objects.requireNonNull(benutzer, "benutzer");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(rolle, "rolle");
            Objects.requireNonNull(gueltigVon, "gueltigVon");
            Objects.requireNonNull(anlass, "anlass");
            Objects.requireNonNull(erfasstVon, "erfasstVon");
        }
    }
}
