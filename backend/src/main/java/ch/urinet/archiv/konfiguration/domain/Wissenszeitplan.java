package ch.urinet.archiv.konfiguration.domain;

import java.time.DayOfWeek;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Wissenszeitplan {
    private static final Pattern WOCHENPLAN =
            Pattern.compile("^0 0 (\\d{1,2}) \\? \\* (MON|TUE|WED|THU|FRI|SAT|SUN)$");

    private Wissenszeitplan() {
    }

    public static Optional<Konfiguration.LintZeitplan> lesen(String cron) {
        if (cron == null) {
            return Optional.empty();
        }
        Matcher treffer = WOCHENPLAN.matcher(cron.toUpperCase(Locale.ROOT));
        if (!treffer.matches()) {
            return Optional.empty();
        }
        int stunde = Integer.parseInt(treffer.group(1));
        if (!Einstellungskatalog.Zahl.WISSEN_LINT_STUNDE.gueltig(stunde)) {
            return Optional.empty();
        }
        DayOfWeek tag = switch (treffer.group(2)) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalStateException("Zeitplan ohne Wochentag");
        };
        return Optional.of(new Konfiguration.LintZeitplan(tag, stunde));
    }

    public static String cron(Konfiguration.LintZeitplan plan, String standard) {
        if (plan.wochentag() == null && plan.stunde() == null) {
            return null;
        }
        if (!Einstellungskatalog.Zahl.WISSEN_LINT_STUNDE.gueltig(plan.stunde())) {
            throw new IllegalArgumentException("Ungültige Stunde des Wissenszeitplans");
        }
        var fallback = lesen(standard).orElse(new Konfiguration.LintZeitplan(DayOfWeek.SUNDAY, 4));
        DayOfWeek wochentag = plan.wochentag() == null ? fallback.wochentag() : plan.wochentag();
        int stunde = plan.stunde() == null ? fallback.stunde() : plan.stunde();
        String tag = switch (wochentag) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
        return "0 0 " + stunde + " ? * " + tag;
    }
}
