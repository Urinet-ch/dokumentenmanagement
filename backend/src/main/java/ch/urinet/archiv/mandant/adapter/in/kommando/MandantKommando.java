package ch.urinet.archiv.mandant.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.Mandant;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;

/**
 * {@code archiv mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]} (ADR-002). Exit 0 = angelegt,
 * 1 = fachlicher Fehler, 2 = falscher Aufruf. Die Ausgabe nennt nur Codes, Schema und Bucket.
 */
@ApplicationScoped
public class MandantKommando {

    public static final String AUFRUF = "Aufruf: archiv mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]";

    private final MandantProvisionierung provisionierung;

    public MandantKommando(MandantProvisionierung provisionierung) {
        this.provisionierung = provisionierung;
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        if (argumente.size() < 2 || !argumente.getFirst().equals("anlegen")) {
            fehler.println(AUFRUF);
            return 2;
        }
        String code = argumente.get(1);
        String name = null;
        String ende = null;
        for (int i = 2; i < argumente.size(); i += 2) {
            if (i + 1 >= argumente.size()) {
                fehler.println(AUFRUF);
                return 2;
            }
            switch (argumente.get(i)) {
                case "--name" -> name = argumente.get(i + 1);
                case "--geschaeftsjahr-ende" -> ende = argumente.get(i + 1);
                default -> {
                    fehler.println(AUFRUF);
                    return 2;
                }
            }
        }
        try {
            Mandant mandant = provisionierung.anlegen(code, name, ende);
            aus.printf("Mandant %s angelegt: Schema %s, Bucket %s, Geschäftsjahresende %s%n", mandant.code().wert(),
                    mandant.schema(), mandant.bucket(), mandant.geschaeftsjahresende().wert());
            return 0;
        } catch (MandantFehler e) {
            fehler.println(e.grund() + ": " + e.getMessage());
            return 1;
        }
    }
}
