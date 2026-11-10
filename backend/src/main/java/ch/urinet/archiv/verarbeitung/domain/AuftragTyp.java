package ch.urinet.archiv.verarbeitung.domain;

import java.util.regex.Pattern;

/** Typ eines Auftrags in {@code public.auftrag}, z. B. {@code VERARBEITEN} (design.md «Verarbeitung»). */
public record AuftragTyp(String wert) {

    private static final Pattern TYP = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    public AuftragTyp {
        if (wert == null || !TYP.matcher(wert).matches()) {
            throw new IllegalArgumentException("Ungültiger Auftragstyp");
        }
    }

    @Override
    public String toString() {
        return wert;
    }
}
