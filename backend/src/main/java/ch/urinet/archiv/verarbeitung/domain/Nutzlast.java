package ch.urinet.archiv.verarbeitung.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nutzlast eines Auftrags: nur IDs und Schalter (Req 16.7), keine Inhalte, Beträge oder Personendaten. Erlaubt sind
 * darum nur UUIDs, Ganzzahlen ({@code Integer} wird zu {@code Long}) und Wahrheitswerte; freier Text ist nicht möglich.
 * Fehlermeldungen nennen höchstens den Schlüssel, nie den Wert.
 */
public record Nutzlast(Map<String, Object> werte) {

    public static final int MAX_EINTRAEGE = 20;
    private static final Pattern SCHLUESSEL = Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");

    public Nutzlast {
        if (werte == null) {
            werte = Map.of();
        }
        if (werte.size() > MAX_EINTRAEGE) {
            throw new IllegalArgumentException("Nutzlast hat mehr als " + MAX_EINTRAEGE + " Einträge");
        }
        Map<String, Object> geprueft = new LinkedHashMap<>();
        werte.forEach((schluessel, wert) -> {
            if (schluessel == null || !SCHLUESSEL.matcher(schluessel).matches()) {
                throw new IllegalArgumentException("Ungültiger Schlüssel in der Nutzlast");
            }
            geprueft.put(schluessel, switch (wert) {
                case UUID id -> id;
                case Long zahl -> zahl;
                case Integer zahl -> zahl.longValue();
                case Boolean schalter -> schalter;
                case null, default -> throw new IllegalArgumentException("Wert für «" + schluessel
                        + "» ist keine UUID, Ganzzahl oder Wahrheitswert");
            });
        });
        werte = Collections.unmodifiableMap(geprueft);
    }

    public static Nutzlast leer() {
        return new Nutzlast(Map.of());
    }

    public Optional<UUID> uuid(String schluessel) {
        return werte.get(schluessel) instanceof UUID id ? Optional.of(id) : Optional.empty();
    }

    public OptionalLong zahl(String schluessel) {
        return werte.get(schluessel) instanceof Long zahl ? OptionalLong.of(zahl) : OptionalLong.empty();
    }

    public Optional<Boolean> schalter(String schluessel) {
        return werte.get(schluessel) instanceof Boolean schalter ? Optional.of(schalter) : Optional.empty();
    }
}
