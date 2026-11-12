package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DokumentPrueflaufPort} mit nativem SQL im Schema des Mandanten aus dem Kontext (ADR-002), nur lesend, in der
 * Transaktion des Aufrufers und mit {@code statement_timeout} je Methode.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentPrueflauf implements DokumentPrueflaufPort {

    private static final String ARCHIV = "archiv/%";
    /** Kleinste UUID in der Ordnung von PostgreSQL (bytweise); Dokument-IDs sind zufällig, nie diese. */
    private static final UUID KLEINSTE = new UUID(0L, 0L);

    @Override
    public List<Pruefdokument> archivierte(Optional<UUID> nach, int anzahl) {
        zeitgrenze();
        var abfrage = em().createNativeQuery("""
                        select d.id, d.status, coalesce(a.gesetzliche_pflicht, true), d.aufbewahren_bis,
                            coalesce(d.frist_abgelaufen, false), d.s3_pdfa, d.s3_version_pdfa, d.sha256_pdfa
                        from dokument d left join dokumentart a on a.code = d.art
                        where d.id > :nach and (d.s3_pdfa like :archiv or exists (select 1 from dokument_datei f
                            where f.dokument_id = d.id and f.s3_schluessel like :archiv))
                        order by d.id limit :anzahl""")
                .setParameter("nach", nach.orElse(KLEINSTE))
                .setParameter("archiv", ARCHIV).setParameter("anzahl", anzahl);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        if (zeilen.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Datei>> dateien = new LinkedHashMap<>();
        zeilen.forEach(z -> dateien.put((UUID) z[0], new ArrayList<>()));
        @SuppressWarnings("unchecked")
        List<Object[]> dateiZeilen = em().createNativeQuery("""
                        select dokument_id, s3_schluessel, s3_version, sha256 from dokument_datei
                        where dokument_id in (:ids) and s3_schluessel like :archiv
                        order by dokument_id, rolle, nr""")
                .setParameter("ids", dateien.keySet()).setParameter("archiv", ARCHIV).getResultList();
        dateiZeilen.forEach(z -> dateien.get((UUID) z[0]).add(datei(z[1], z[2], z[3])));
        List<Pruefdokument> ergebnis = new ArrayList<>();
        for (Object[] z : zeilen) {
            UUID id = (UUID) z[0];
            List<Datei> liste = dateien.get(id);
            if (z[5] instanceof String pdfa && pdfa.startsWith("archiv/")) {
                liste.add(datei(z[5], z[6], z[7]));
            }
            ergebnis.add(new Pruefdokument(id, DokumentStatus.valueOf((String) z[1]),
                    new Sperrvorgabe((Boolean) z[2], datum(z[3]), (Boolean) z[4]), liste));
        }
        return ergebnis;
    }

    @Override
    public Set<Bekannt> bekannte(Collection<String> schluessel) {
        if (schluessel.isEmpty()) {
            return Set.of();
        }
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select s3_schluessel, s3_version from dokument_datei
                        where s3_schluessel in (:k) and s3_version is not null
                        union all
                        select s3_pdfa, s3_version_pdfa from dokument
                        where s3_pdfa in (:k) and s3_version_pdfa is not null""")
                .setParameter("k", new HashSet<>(schluessel)).getResultList();
        Set<Bekannt> bekannt = new HashSet<>();
        zeilen.forEach(z -> bekannt.add(new Bekannt((String) z[0], (String) z[1])));
        return bekannt;
    }

    @Override
    public Map<UUID, DokumentStatus> status(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("select id, status from dokument where id in (:ids)")
                .setParameter("ids", new HashSet<>(ids)).getResultList();
        Map<UUID, DokumentStatus> status = new HashMap<>();
        zeilen.forEach(z -> status.put((UUID) z[0], DokumentStatus.valueOf((String) z[1])));
        return status;
    }

    private static Datei datei(Object schluessel, Object versionId, Object sha256) {
        return new Datei((String) schluessel, Optional.ofNullable((String) versionId),
                Optional.ofNullable((String) sha256));
    }

    private void zeitgrenze() {
        em().createNativeQuery("select set_config('statement_timeout', '"
                + PanacheDokumentVerarbeitung.ZEITGRENZE + "', true)").getSingleResult();
    }

    private static LocalDate datum(Object wert) {
        return switch (wert) {
            case null -> null;
            case LocalDate l -> l;
            case Date d -> d.toLocalDate();
            default -> throw new IllegalStateException("Unerwarteter Typ für ein Datum: " + wert.getClass().getName());
        };
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
