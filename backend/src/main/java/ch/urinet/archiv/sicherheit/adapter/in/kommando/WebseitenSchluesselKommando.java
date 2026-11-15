package ch.urinet.archiv.sicherheit.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** Provisioniert und rotiert Webseiten-Schlüssel; das Geheimnis erscheint ausschliesslich auf stdout. */
@ApplicationScoped
public class WebseitenSchluesselKommando {

    public static final String AUFRUF = """
            Aufruf:
              archiv webseite schluessel erstellen <mandant> <webseite> lesen --stufe <oeffentlich|mitglieder|vorstand> [--absender-pruefen]
              archiv webseite schluessel erstellen <mandant> <webseite> erfassung [--absender-pruefen]
              archiv webseite schluessel rotieren <tokenId> [--ueberlappung <ISO-Dauer>]
              archiv webseite schluessel widerrufen <tokenId>""";

    private final WebseitenSchluesselVerwaltung verwaltung;

    public WebseitenSchluesselKommando(WebseitenSchluesselVerwaltung verwaltung) {
        this.verwaltung = verwaltung;
    }

    public record Aufruf(String aktion, String mandant, String webseite, String art, String stufe,
                         boolean absenderPruefen, String id, Duration ueberlappung) {
    }

    /** Prüft die Argumente nach {@code webseite schluessel}, ohne die Anwendung zu brauchen. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 1) {
            return Optional.empty();
        }
        return switch (argumente.getFirst()) {
            case "erstellen" -> erstellenLesen(argumente);
            case "rotieren" -> rotierenLesen(argumente);
            case "widerrufen" -> argumente.size() == 2
                    ? Optional.of(new Aufruf("widerrufen", null, null, null, null, false, argumente.get(1), null))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            return switch (aufruf.get().aktion()) {
                case "erstellen" -> erstellen(aufruf.get(), aus, fehler);
                case "rotieren" -> rotieren(aufruf.get(), aus, fehler);
                case "widerrufen" -> widerrufen(aufruf.get(), fehler);
                default -> throw new IllegalStateException("Aktion nicht unterstützt");
            };
        } catch (FachFehler e) {
            fehler.println(e.code() + ": " + e.code().titel());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }

    private int erstellen(Aufruf aufruf, PrintStream aus, PrintStream fehler) {
        WebseitenSchluesselVerwaltung.Erstellt erstellt = verwaltung.erstellen(aufruf.mandant(), aufruf.webseite(),
                aufruf.art(), aufruf.stufe(), aufruf.absenderPruefen());
        aus.println(erstellt.token().text());
        beschreiben(erstellt.client(), fehler);
        return 0;
    }

    private int rotieren(Aufruf aufruf, PrintStream aus, PrintStream fehler) {
        WebseitenSchluesselVerwaltung.Erstellt erstellt = verwaltung.rotieren(aufruf.id(), aufruf.ueberlappung());
        aus.println(erstellt.token().text());
        beschreiben(erstellt.client(), fehler);
        return 0;
    }

    private int widerrufen(Aufruf aufruf, PrintStream fehler) {
        ApiClient client = verwaltung.widerrufen(aufruf.id());
        fehler.printf("Webseiten-Schlüssel %s für Mandant %s, Webseite %s (Scope %s) widerrufen.%n",
                client.id(), client.mandant(), client.webseite().orElseThrow(), client.scope().code());
        return 0;
    }

    private static void beschreiben(ApiClient client, PrintStream fehler) {
        fehler.printf("Webseiten-Schlüssel %s für Mandant %s, Webseite %s (Scope %s, Stufe %s) erstellt; "
                        + "das Token wird nur jetzt angezeigt.%n",
                client.id(), client.mandant(), client.webseite().orElseThrow(), client.scope().code(),
                client.hoechsteStufe().orElseThrow());
    }

    private static Optional<Aufruf> erstellenLesen(List<String> argumente) {
        if (argumente.size() < 4) {
            return Optional.empty();
        }
        String mandant = argumente.get(1);
        String webseite = argumente.get(2);
        String art = argumente.get(3);
        String stufe = null;
        boolean absenderPruefen = false;
        boolean stufeGesetzt = false;
        for (int i = 4; i < argumente.size(); i++) {
            switch (argumente.get(i)) {
                case "--stufe" -> {
                    if (stufeGesetzt || i + 1 >= argumente.size()) {
                        return Optional.empty();
                    }
                    stufe = argumente.get(++i);
                    stufeGesetzt = true;
                }
                case "--absender-pruefen" -> {
                    if (absenderPruefen) {
                        return Optional.empty();
                    }
                    absenderPruefen = true;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (art.equals("lesen") != stufeGesetzt || !art.equals("lesen") && !art.equals("erfassung")) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf("erstellen", mandant, webseite, art, stufe, absenderPruefen, null, null));
    }

    private static Optional<Aufruf> rotierenLesen(List<String> argumente) {
        if (argumente.size() != 2 && argumente.size() != 4) {
            return Optional.empty();
        }
        Duration ueberlappung = WebseitenSchluesselVerwaltung.STANDARD_UEBERLAPPUNG;
        if (argumente.size() == 4) {
            if (!argumente.get(2).equals("--ueberlappung")) {
                return Optional.empty();
            }
            try {
                ueberlappung = Duration.parse(argumente.get(3));
            } catch (DateTimeParseException | ArithmeticException e) {
                return Optional.empty();
            }
        }
        if (ueberlappung.isNegative() || ueberlappung.compareTo(WebseitenSchluesselVerwaltung.MAX_UEBERLAPPUNG) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf("rotieren", null, null, null, null, false, argumente.get(1), ueberlappung));
    }
}
