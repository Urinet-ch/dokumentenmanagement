package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.io.IOException;
import java.io.OutputStream;

public interface WissenOkfExportPort {
    void schreiben(MandantCode mandant, OutputStream ziel) throws IOException, InterruptedException;
}
