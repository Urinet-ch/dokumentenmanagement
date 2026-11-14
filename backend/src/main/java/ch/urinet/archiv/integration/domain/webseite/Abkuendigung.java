package ch.urinet.archiv.integration.domain.webseite;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Abkündigung einer Hauptversion (Spec 03, Req 11.5): ab dem Sunset-Datum darf sie abgeschaltet werden; bis dahin trägt
 * jede ihrer Antworten {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) und {@code Link} auf die Nachfolgerin.
 * <p>
 * Die Spec nennt nur das Sunset-Datum ({@code archiv.webseite.v<n>.sunset}). Als Zeitpunkt der Abkündigung gilt darum
 * das Sunset-Datum minus {@link #PARALLELBETRIEB_MONATE} Monate: Req 11.4 verlangt mindestens so lange Parallelbetrieb ab
 * Bereitstellung der Nachfolgerin, also war die Version spätestens dann abgekündigt. Der Wert hängt nur an der
 * Konfiguration, nicht an Uhrzeit oder Neustart, und liegt nie nach dem Sunset (RFC 9745, Abschnitt 4). Beide Zeitpunkte
 * sind Tagesbeginn in UTC.
 */
public record Abkuendigung(Hauptversion version, LocalDate sunset) {

    public static final int PARALLELBETRIEB_MONATE = 6;
    /** IMF-fixdate nach RFC 9110, Abschnitt 5.6.7 (Tag immer zweistellig, anders als RFC_1123_DATE_TIME). */
    private static final DateTimeFormatter HTTP_DATUM =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public Abkuendigung {
        Objects.requireNonNull(version);
        Objects.requireNonNull(sunset);
    }

    /** Wert für {@code Deprecation}: Structured-Field-Datum {@code @<Unix-Sekunden>} (RFC 9745, RFC 9651 3.3.7). */
    public String deprecation() {
        return "@" + sunset.minusMonths(PARALLELBETRIEB_MONATE).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** Wert für {@code Sunset}: HTTP-Datum (RFC 8594), z. B. {@code Thu, 01 Jul 2027 00:00:00 GMT}. */
    public String sunsetHttpDatum() {
        return HTTP_DATUM.format(sunset.atStartOfDay(ZoneOffset.UTC));
    }

    /** Wert für {@code Link}: {@code </public/v<n+1>>; rel="successor-version"}. */
    public String link() {
        return "<" + version.nachfolgerin().pfad() + ">; rel=\"successor-version\"";
    }
}
