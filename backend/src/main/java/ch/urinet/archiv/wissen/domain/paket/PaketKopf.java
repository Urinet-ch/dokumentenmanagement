package ch.urinet.archiv.wissen.domain.paket;

import java.time.Instant;

public record PaketKopf(int paketFormat, String mandant, String erzeugtVon, String erzeugtMit,
                        String kontextStufe, Instant erzeugtAm, String instanz) {}
