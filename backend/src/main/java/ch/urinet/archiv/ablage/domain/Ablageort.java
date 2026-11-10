package ch.urinet.archiv.ablage.domain;

/** Präfix im Bucket: {@code eingang/} ohne Sperre (Req 6.2), {@code archiv/} nach der Archivierung (Req 6.3). */
public enum Ablageort {
    EINGANG("eingang"),
    ARCHIV("archiv");

    private final String praefix;

    Ablageort(String praefix) {
        this.praefix = praefix;
    }

    public String praefix() {
        return praefix;
    }
}
