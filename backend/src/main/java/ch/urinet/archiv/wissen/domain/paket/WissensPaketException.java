package ch.urinet.archiv.wissen.domain.paket;

public final class WissensPaketException extends RuntimeException {
    public enum Code { DATEI_ZU_GROSS, DATEITYP_UNBEKANNT, ZIP_UNGUELTIG, GRENZE, PFAD,
        DUPLIKAT, SYMLINK, VERSCHLUESSELT, ZIP_BOMBE, YAML, FORMAT, CLOUD_STUFE, ZEIT }

    private final Code code;
    private final String pfad;

    public WissensPaketException(Code code, String pfad) {
        super(code.name());
        this.code = code;
        this.pfad = pfad;
    }
    public Code code() { return code; }
    public String pfad() { return pfad; }
}
