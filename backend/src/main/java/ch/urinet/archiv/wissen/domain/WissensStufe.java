package ch.urinet.archiv.wissen.domain;

public enum WissensStufe {
    NORMAL,
    INTERN;

    public WissensStufe hoechste(WissensStufe andere) {
        return ordinal() >= andere.ordinal() ? this : andere;
    }
}
