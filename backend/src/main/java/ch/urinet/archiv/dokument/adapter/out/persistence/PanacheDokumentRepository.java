package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.application.DateiFundstelle;
import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.dokument.application.NeueDatei;
import ch.urinet.archiv.dokument.application.NeuesDokument;
import ch.urinet.archiv.dokument.application.Seite;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

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

    /**
     * Sperrt die Prüfsumme im Schema des Mandanten bis zum Ende der Transaktion; zwei gleichzeitige Uploads derselben
     * Datei laufen so nacheinander, der zweite sieht die erste als Dublette (Req 3.4).
     */
    private static final String DUBLETTE_SPERREN =
            "select 1 from pg_advisory_xact_lock(hashtextextended(current_schema() || ':' || :sha, 0))";

    private static final String DUBLETTE = """
            select dokument_id from dokument_datei
            where sha256 = :sha and rolle = 'ORIGINAL' and status <> 'VERWORFEN'
            limit 1""";

    private static final String DOKUMENT_ANLEGEN = """
            insert into dokument (id, eingang, quelle, vertraulichkeit, erstellt_von, status, hinweis)
            values (:id, :eingang, :quelle, 'PERSONAL', :erstelltVon, 'NEU', :hinweis)""";

    private static final String DATEI_ANLEGEN = """
            insert into dokument_datei (dokument_id, rolle, nr, sha256, s3_schluessel, mime, groesse, empfangen)
            values (:id, :rolle, :nr, :sha, :schluessel, :mime, :groesse, :empfangen)""";

    private static final String DATEI_ABGELEGT = """
            update dokument_datei set s3_version = :version
            where dokument_id = :id and rolle = :rolle and nr = :nr""";

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
    public Optional<DateiFundstelle> dateiFinden(UUID id, Sichtregel regel, DateiRolle rolle, int nr) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        String sql = rolle == DateiRolle.PDFA
                ? "select d.s3_pdfa, d.s3_version_pdfa from dokument d where d.id = :id and d.status <> 'VERWORFEN' "
                + "and d.s3_pdfa is not null and d.s3_version_pdfa is not null and " + sicht.bedingung()
                : "select f.s3_schluessel, f.s3_version from dokument d join dokument_datei f on f.dokument_id = d.id "
                + "where d.id = :id and d.status <> 'VERWORFEN' and f.rolle = :rolle and f.nr = :nr "
                + "and f.status = 'AKTIV' and f.s3_version is not null and " + sicht.bedingung();
        var abfrage = DokumentEintrag.getEntityManager().createNativeQuery(sql).setParameter("id", id);
        sicht.parameter().forEach(abfrage::setParameter);
        if (rolle != DateiRolle.PDFA) {
            abfrage.setParameter("rolle", rolle.name()).setParameter("nr", nr);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        return zeilen.stream().findFirst().map(z -> new DateiFundstelle((String) z[0], (String) z[1]));
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
    public Optional<UUID> dubletteSperren(String sha256) {
        var em = DokumentEintrag.getEntityManager();
        em.createNativeQuery(DUBLETTE_SPERREN).setParameter("sha", sha256).getSingleResult();
        @SuppressWarnings("unchecked")
        List<UUID> treffer = em.createNativeQuery(DUBLETTE, UUID.class).setParameter("sha", sha256).getResultList();
        return treffer.stream().findFirst();
    }

    @Override
    public void anlegen(NeuesDokument dokument) {
        DokumentEintrag.getEntityManager().createNativeQuery(DOKUMENT_ANLEGEN)
                .setParameter("id", dokument.id())
                .setParameter("eingang", dokument.eingang().atOffset(ZoneOffset.UTC))
                .setParameter("quelle", dokument.quelle())
                .setParameter("erstelltVon", dokument.erstelltVon())
                .setParameter("hinweis", dokument.hinweis().orElse(null))
                .executeUpdate();
    }

    @Override
    public void dateiAnlegen(NeueDatei datei) {
        DokumentEintrag.getEntityManager().createNativeQuery(DATEI_ANLEGEN)
                .setParameter("id", datei.dokumentId())
                .setParameter("rolle", datei.rolle().name())
                .setParameter("nr", datei.nr())
                .setParameter("sha", datei.sha256())
                .setParameter("schluessel", datei.s3Schluessel())
                .setParameter("mime", datei.mime())
                .setParameter("groesse", datei.groesse())
                .setParameter("empfangen", datei.empfangen().atOffset(ZoneOffset.UTC))
                .executeUpdate();
    }

    @Override
    public void dateiAbgelegt(UUID dokumentId, DateiRolle rolle, int nr, String s3Version) {
        DokumentEintrag.getEntityManager().createNativeQuery(DATEI_ABGELEGT)
                .setParameter("version", s3Version).setParameter("id", dokumentId)
                .setParameter("rolle", rolle.name()).setParameter("nr", nr)
                .executeUpdate();
    }

    private static Dokument dokument(DokumentEintrag e) {
        return new Dokument(e.id, e.art, e.titel, e.datum, e.eingang.toInstant(), e.quelle,
                e.tags == null ? List.of() : List.of(e.tags), e.vertraulichkeit, e.personBenutzer, e.erstelltVon,
                DokumentStatus.valueOf(e.status), e.bestaetigt == null ? null : e.bestaetigt.toInstant(),
                e.geschaeftsjahr, e.aufbewahrenBis, e.sperreBis, e.fristAbgelaufen, e.zusatz, e.version, e.fehler,
                e.pdfaFehlt, e.seiten, e.partnerId, e.partnerName, e.kiVorschlag, e.kiSicherheit);
    }
}
