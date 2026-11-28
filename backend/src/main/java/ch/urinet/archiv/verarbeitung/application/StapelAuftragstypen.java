package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

public final class StapelAuftragstypen {

    public static final AuftragTyp ANALYSE = new AuftragTyp("STAPEL_ANALYSE");
    public static final AuftragTyp TRENNEN = new AuftragTyp("STAPEL_TRENNEN");
    public static final AuftragTyp ABSCHLUSS = new AuftragTyp("STAPEL_ABSCHLUSS");

    private StapelAuftragstypen() {
    }
}
