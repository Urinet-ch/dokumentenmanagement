package ch.urinet.archiv.verarbeitung.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.EmbeddingNachberechnung;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * {@code archiv embedding nachberechnen <code> [--nur-fehlende]} (Spec 02, Req 2.4): reiht die Nachberechnung der
 * Embeddings eines aktiven Mandanten als Auftrag {@code EMBEDDING_ALLE} ein; der Worker arbeitet sie ab. Ohne Option
 * alle archivierten Dokumente, mit {@code --nur-fehlende} nur solche ohne Abschnitte oder mit einem Abschnitt ohne
 * Vektor. Exit 0 = eingereiht, 1 = Mandant unbekannt oder nicht aktiv, 2 = falscher Aufruf, 3 = technischer Fehler.
 * Die Ausgabe nennt nur Mandant und Auftrags-ID.
 */
@ApplicationScoped
public class EmbeddingKommando {

    public static final String AUFRUF = "Aufruf: archiv embedding nachberechnen <code> [--nur-fehlende]";
    static final String NUR_FEHLENDE = "--nur-fehlende";

    private final EmbeddingNachberechnung nachberechnung;
    private final MandantRegister register;

    public EmbeddingKommando(EmbeddingNachberechnung nachberechnung, MandantRegister register) {
        this.nachberechnung = nachberechnung;
        this.register = register;
    }

    public record Aufruf(MandantCode mandant, boolean nurFehlende) {
    }

    /** Prüft die Argumente nach {@code embedding}, ohne die Anwendung zu brauchen; leer bei falschem Aufruf. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2 || argumente.size() > 3 || !argumente.getFirst().equals("nachberechnen")) {
            return Optional.empty();
        }
        if (argumente.size() == 3 && !argumente.get(2).equals(NUR_FEHLENDE)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Aufruf(new MandantCode(argumente.get(1)), argumente.size() == 3));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        MandantCode mandant = aufruf.get().mandant();
        try {
            if (register.aktive().stream().map(Mandant::code).noneMatch(mandant::equals)) {
                fehler.println("Mandant «" + mandant.wert() + "» ist nicht angelegt oder nicht aktiv.");
                return 1;
            }
            long id = nachberechnung.starten(mandant, aufruf.get().nurFehlende());
            aus.printf("Nachberechnung der Embeddings für Mandant %s eingereiht (Auftrag %d, %s)%n", mandant.wert(), id,
                    aufruf.get().nurFehlende() ? "nur fehlende" : "alle archivierten Dokumente");
            return 0;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
