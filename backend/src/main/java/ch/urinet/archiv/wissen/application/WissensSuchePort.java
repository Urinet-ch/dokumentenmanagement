package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.Seitentyp;

import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Objects;

public interface WissensSuchePort {
    record Filter(Sichtregel regel, Seitentyp typ, String tag, UUID dokumentId, Set<UUID> erlaubteSeiten) {
        public Filter(Sichtregel regel, Seitentyp typ, String tag, UUID dokumentId) {
            this(regel, typ, tag, dokumentId, null);
        }
        public Filter {
            if (regel == null) Objects.requireNonNull(erlaubteSeiten, "Ingest-Seitenkontext fehlt");
            if (erlaubteSeiten != null) erlaubteSeiten = Set.copyOf(erlaubteSeiten);
        }
    }
    record Treffer(WissenLesenRepository.IndexZeile seite, int version, String text) { }

    List<Treffer> volltext(String frage, Filter filter, int groesse);

    List<Treffer> vektor(float[] frage, Filter filter, int groesse);

    List<Treffer> sichtbar(List<UUID> ids, Filter filter);
}
