package ch.urinet.archiv.sicherheit.domain;

/** Fussnoten der Rechtematrix (Req 2.6, Spec 06 Req 4.1); die Rechte-Übersicht gibt sie unverändert aus (Req 4.12). */
public enum Fussnote {
    /** ¹ in der Spalte {@code geschaeftsstelle}: 403, bis Spec 03, 3.5 die Rechte entscheidet. */
    GESCHAEFTSSTELLE_OFFEN("¹", "Für geschaeftsstelle gilt 403, bis Spec 03, 3.5 die Rechte der Verwaltung entscheidet."),
    /** ² bei «nur eigene»: bestätigt mit {@code person_benutzer} = Benutzer, oder unbestätigt und von ihm erfasst. */
    NUR_EIGENE("²", "eigene: Die Art ist bestätigt und person_benutzer ist der Benutzer selbst, oder die Art ist noch nicht "
            + "bestätigt und er hat das Dokument erfasst (Spec 01, 2.21).");

    private final String zeichen;
    private final String text;

    Fussnote(String zeichen, String text) {
        this.zeichen = zeichen;
        this.text = text;
    }

    public String zeichen() {
        return zeichen;
    }

    public String text() {
        return text;
    }
}
