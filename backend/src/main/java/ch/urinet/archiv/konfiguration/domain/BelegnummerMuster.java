package ch.urinet.archiv.konfiguration.domain;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Syntaxprüfung für Belegnummer-Muster nach der JDK-RegEx-Syntax. */
public final class BelegnummerMuster {

    public static final int MAX_ZEICHEN = 1_000;

    private BelegnummerMuster() {
    }

    public static boolean gueltig(String muster) {
        if (muster == null || muster.length() > MAX_ZEICHEN) {
            return false;
        }
        try {
            Pattern.compile(muster);
        } catch (PatternSyntaxException e) {
            return false;
        }
        return true;
    }
}
