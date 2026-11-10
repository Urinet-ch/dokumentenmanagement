package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.dokument.application.Seite;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.OptimisticLockException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DokumentRepository} über {@link DokumentEintrag} im Schema des Mandanten der Anfrage (ADR-002); jede Abfrage
 * für einen Benutzer hängt den {@link VertraulichkeitFilter} an.
 */
@ApplicationScoped
public class PanacheDokumentRepository implements DokumentRepository {

    /** Vorherige Fassung der Metadaten als JSON (Req 10.1); Volltext und Suchvektor sind keine Metadaten. */
    private static final String VERSION_SICHERN = """
            insert into dokument_version (dokument_id, version, daten, geaendert_von, grund)
            select d.id, d.version, jsonb_build_object(
                'dokument', to_jsonb(d) - 'volltext' - 'volltext_tsv',
                'finanz', (select to_jsonb(f) - 'dokument_id' from dokument_finanz f where f.dokument_id = d.id),
                'vertrag', (select to_jsonb(v) - 'dokument_id' from dokument_vertrag v where v.dokument_id = d.id)),
                :benutzer, :grund
            from dokument d where d.id = :id""";

    private static final List<String> EINGANGSKORB = DokumentStatus.EINGANGSKORB_STATUS.stream()
            .map(Enum::name).sorted().toList();

    @Override
    public Optional<Dokument> finden(UUID id, Sichtregel regel) {
        VertraulichkeitFilter filter = VertraulichkeitFilter.fuer(regel).und("id = :id", Map.of("id", id));
        return DokumentEintrag.<DokumentEintrag>find(filter.bedingung(), filter.parameter()).firstResultOptional()
                .map(PanacheDokumentRepository::dokument);
    }

    @Override
    public boolean sichtbar(UUID id, Sichtregel regel) {
        VertraulichkeitFilter filter = VertraulichkeitFilter.fuer(regel).und("id = :id", Map.of("id", id));
        return DokumentEintrag.count(filter.bedingung(), filter.parameter()) > 0;
    }

    @Override
    public Seite<Dokument> eingangskorb(Sichtregel regel, int seite, int groesse) {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("status", EINGANGSKORB);
        String bedingung = "status in :status";
        if (regel.eingangskorbNurErfasste()) {
            bedingung += " and erstelltVon = :erfasser";
            parameter.put("erfasser", regel.benutzer());
        }
        VertraulichkeitFilter filter = VertraulichkeitFilter.fuer(regel).und(bedingung, parameter);
        long gesamt = DokumentEintrag.count(filter.bedingung(), filter.parameter());
        List<Dokument> eintraege = DokumentEintrag.<DokumentEintrag>find(filter.bedingung(),
                        Sort.descending("eingang", "id"), filter.parameter())
                .page(Page.of(seite, groesse)).list().stream().map(PanacheDokumentRepository::dokument).toList();
        return new Seite<>(eintraege, gesamt);
    }

    @Override
    public long anzahlMitArt(String art) {
        return DokumentEintrag.count("art", art);
    }

    @Override
    public int zusatzSpeichern(UUID id, int erwarteteVersion, String zusatzJson, String benutzer, String grund,
                               boolean versionSichern) {
        DokumentEintrag eintrag = DokumentEintrag.findById(id);
        if (eintrag == null) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        if (eintrag.version != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (versionSichern) {
            DokumentEintrag.getEntityManager().createNativeQuery(VERSION_SICHERN)
                    .setParameter("id", id).setParameter("benutzer", benutzer).setParameter("grund", grund)
                    .executeUpdate();
        }
        eintrag.zusatz = zusatzJson;
        eintrag.geaendert = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            DokumentEintrag.flush();
        } catch (OptimisticLockException e) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        return eintrag.version;
    }

    private static Dokument dokument(DokumentEintrag e) {
        return new Dokument(e.id, e.art, e.titel, e.datum, e.eingang.toInstant(), e.quelle,
                e.tags == null ? List.of() : List.of(e.tags), e.vertraulichkeit, e.personBenutzer, e.erstelltVon,
                DokumentStatus.valueOf(e.status), e.bestaetigt == null ? null : e.bestaetigt.toInstant(),
                e.geschaeftsjahr, e.aufbewahrenBis, e.sperreBis, e.fristAbgelaufen, e.zusatz, e.version);
    }
}
