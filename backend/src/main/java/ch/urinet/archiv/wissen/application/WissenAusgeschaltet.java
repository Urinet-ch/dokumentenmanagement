package ch.urinet.archiv.wissen.application;

/**
 * Wissen wurde für den Mandanten abgeschaltet, während ein Auftrag der Wissensbasis lief (Spec 04, Req 22.9): geworfen
 * unmittelbar vor einem Modellaufruf; der Auftrag endet dann ohne neuen Entwurf mit dem Grund
 * {@value Wissensfreischaltung#GRUND_AUSGESCHALTET}.
 */
public final class WissenAusgeschaltet extends RuntimeException {

    public WissenAusgeschaltet() {
        super(Wissensfreischaltung.GRUND_AUSGESCHALTET, null, false, false);
    }
}
