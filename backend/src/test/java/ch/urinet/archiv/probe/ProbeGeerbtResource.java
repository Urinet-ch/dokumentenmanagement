package ch.urinet.archiv.probe;

import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import jakarta.ws.rs.Path;

/** Probe nur für Tests: geerbte Methode, {@code @Protokolliert} an der Klasse (Vorab-Korrektur 7). */
@Path("/probe/geerbt")
@Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
public class ProbeGeerbtResource extends ProbeLesenBasis {
}
