package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import java.util.List;
import java.util.Objects;

/**
 * Ein Werkzeugaufruf als feste Argumentliste, ohne Shell: Jedes Element geht unverändert als ein Argument an
 * {@code execve}; Leerzeichen, Anführungszeichen oder {@code ;} haben keine Wirkung.
 */
record Befehl(String programm, List<String> argumente) {

    Befehl {
        Objects.requireNonNull(programm, "programm");
        argumente = List.copyOf(argumente);
        if (programm.isBlank() || programm.startsWith("-")) {
            throw new IllegalArgumentException("Programm ungültig");
        }
    }

    static Befehl von(String programm, String... argumente) {
        return new Befehl(programm, List.of(argumente));
    }
}
