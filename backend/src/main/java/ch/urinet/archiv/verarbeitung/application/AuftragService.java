package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reiht Aufträge ein (Req 1.6): Der Mandant stammt aus dem Mandanten-Kontext des Workers oder aus der verifizierten
 * Anmeldung, nie aus einem Parameter (tech.md Regel 1). Fehlt er oder widersprechen sich beide, bricht der Aufruf ab
 * (fail closed). In einer laufenden Transaktion ist der Auftrag Teil davon, z. B. mit dem neuen Dokument.
 */
@ApplicationScoped
public class AuftragService {

    private final AuftragSchlange schlange;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final MandantKontext kontext;
    private final MandantAusAnmeldung anmeldung;

    public AuftragService(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis, MandantKontext kontext,
                          MandantAusAnmeldung anmeldung) {
        this.schlange = schlange;
        this.verzeichnis = verzeichnis;
        this.kontext = kontext;
        this.anmeldung = anmeldung;
    }

    /** ID des neuen Auftrags; nur Typen mit Handler, sonst bliebe der Auftrag für immer offen. */
    public long einreihen(AuftragTyp typ, Nutzlast nutzlast) {
        if (!hatHandler(typ)) {
            throw new IllegalArgumentException("Kein Handler für den Auftragstyp " + typ);
        }
        return schlange.einfuegen(mandant(), typ, nutzlast);
    }

    /** Ob für den Typ ein Handler besteht, also {@link #einreihen} ihn annimmt. */
    public boolean hatHandler(AuftragTyp typ) {
        return verzeichnis.typen().contains(typ);
    }

    /**
     * Für Handler (ab 2.6, design.md «Verarbeitung»): in derselben Transaktion vor dem Schreiben der Ergebnisse
     * aufrufen. Sperrt die Zeile des Auftrags bis zum Ende der Transaktion, solange der Anspruch hält
     * ({@code id}, {@code versuche}, {@code LAEUFT}); sonst {@link AnspruchVerloren}, und die Transaktion rollt zurück.
     * So schreibt ein Handler, den der Aufräumer schon freigegeben hat, nichts mehr.
     */
    public void anspruchSichern(Auftrag auftrag) {
        if (!schlange.anspruchSperren(auftrag)) {
            throw new AnspruchVerloren();
        }
    }

    /**
     * Für Handler, die Folgeaufträge einreihen: in derselben Transaktion nach {@link #anspruchSichern} aufrufen. Ob
     * schon ein Auftrag eingereiht ist, dessen Nutzlast unter {@code schluessel} die ID von {@code vorgaenger} trägt;
     * dann hat ein früherer Lauf desselben Auftrags committet, und der Handler reiht nichts mehr ein (idempotent).
     */
    public boolean nachfolgerVorhanden(Auftrag vorgaenger, String schluessel) {
        return schlange.nachfolgerVorhanden(vorgaenger, schluessel);
    }

    /**
     * Kleinste ID eines Auftrags {@code typ} des Mandanten aus dem Kontext in {@code OFFEN} oder {@code LAEUFT} (mit
     * {@code schluessel} nur mit diesem Schlüssel in der Nutzlast). Mit {@code sperren} sperrt der Aufruf den Typ für
     * den Mandanten bis zum Commit, damit zwei Aufrufer nicht beide einreihen (nur in einer laufenden Transaktion).
     */
    public OptionalLong offen(AuftragTyp typ, Optional<String> schluessel, boolean sperren) {
        MandantCode mandant = mandant();
        if (sperren) {
            schlange.typSperren(mandant, typ);
        }
        return schlange.offen(mandant, typ, schluessel);
    }

    /** Der Auftrag gehört nicht mehr diesem Lauf (freigegeben oder neu geholt). */
    public static final class AnspruchVerloren extends RuntimeException {
        AnspruchVerloren() {
            super("Anspruch auf den Auftrag verloren", null, false, false);
        }
    }

    private MandantCode mandant() {
        Optional<MandantCode> ausKontext = kontext.aktuell();
        Optional<MandantCode> ausAnmeldung = anmeldung.ermitteln() instanceof MandantAusAnmeldung.Gueltig g
                ? Optional.of(g.code()) : Optional.empty();
        if (ausKontext.isPresent() && ausAnmeldung.isPresent() && !ausKontext.equals(ausAnmeldung)) {
            throw new IllegalStateException("Mandanten-Kontext widerspricht der Anmeldung");
        }
        return ausKontext.or(() -> ausAnmeldung)
                .orElseThrow(() -> new IllegalStateException("Auftrag ohne Mandant ist nicht erlaubt"));
    }
}
