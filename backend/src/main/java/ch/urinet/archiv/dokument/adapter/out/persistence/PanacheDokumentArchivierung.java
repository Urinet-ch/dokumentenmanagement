package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.application.DokumentArchivierungPort;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DokumentArchivierungPort} mit nativem SQL im Schema des Mandanten aus dem Kontext (ADR-002), in der
 * Transaktion des Aufrufers und mit {@code statement_timeout} je Methode. Jede Änderung erhöht {@code version}.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentArchivierung implements DokumentArchivierungPort {

    private static final String PDF = "application/pdf";

    @Override
    public Optional<Archivierungsstand> laden(UUID id) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select d.status, d.eingang, coalesce(a.gesetzliche_pflicht, true), d.aufbewahren_bis,
                            d.frist_abgelaufen, d.pdfa_fehlt, d.s3_pdfa, d.s3_version_pdfa, d.sha256_pdfa
                        from dokument d left join dokumentart a on a.code = d.art
                        where d.id = :id""")
                .setParameter("id", id).getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] d = zeilen.getFirst();
        @SuppressWarnings("unchecked")
        List<Object[]> dateiZeilen = em().createNativeQuery("""
                        select rolle, nr, mime, s3_schluessel, s3_version, sha256 from dokument_datei
                        where dokument_id = :id and status = 'AKTIV' order by rolle, nr""")
                .setParameter("id", id).getResultList();
        List<Datei> dateien = dateiZeilen.stream()
                .map(z -> new Datei(DateiRolle.valueOf((String) z[0]), ((Number) z[1]).intValue(), (String) z[2],
                        (String) z[3], (String) z[4], (String) z[5]))
                .toList();
        Optional<Datei> pdfa = d[6] == null && d[7] == null && d[8] == null ? Optional.empty()
                : Optional.of(new Datei(DateiRolle.PDFA, 1, PDF, (String) d[6], (String) d[7], (String) d[8]));
        return Optional.of(new Archivierungsstand(id, DokumentStatus.valueOf((String) d[0]), zeitpunkt(d[1]),
                (Boolean) d[2], datum(d[3]), (Boolean) d[4], dateien, (Boolean) d[5], pdfa));
    }

    @Override
    public boolean festhalten(UUID id, List<Archiviert> dateien, LocalDate sperreBis) {
        zeitgrenze();
        Archiviert pdfa = dateien.stream().filter(a -> a.rolle() == DateiRolle.PDFA).findFirst().orElse(null);
        boolean geaendert = em().createNativeQuery("""
                        update dokument set status = 'ARCHIVIERT', fehler = null, archiviert = clock_timestamp(), sperre_bis = :sperre,
                            s3_pdfa = coalesce(:s3Pdfa, s3_pdfa), s3_version_pdfa = coalesce(:versionPdfa, s3_version_pdfa),
                            version = version + 1, geaendert = clock_timestamp()
                        where id = :id and status = 'ARCHIVIERUNG'""")
                .unwrap(NativeQuery.class)
                .setParameter("sperre", sperreBis, LocalDate.class)
                .setParameter("s3Pdfa", pdfa == null ? null : pdfa.s3Schluessel(), String.class)
                .setParameter("versionPdfa", pdfa == null ? null : pdfa.s3Version(), String.class)
                .setParameter("id", id)
                .executeUpdate() == 1;
        if (!geaendert) {
            return false;
        }
        for (Archiviert datei : dateien) {
            if (datei.rolle() == DateiRolle.PDFA) {
                continue;
            }
            int zeilen = em().createNativeQuery("""
                            update dokument_datei set s3_schluessel = :schluessel, s3_version = :version
                            where dokument_id = :id and rolle = :rolle and nr = :nr and status = 'AKTIV'""")
                    .setParameter("schluessel", datei.s3Schluessel()).setParameter("version", datei.s3Version())
                    .setParameter("id", id).setParameter("rolle", datei.rolle().name()).setParameter("nr", datei.nr())
                    .executeUpdate();
            if (zeilen != 1) {
                throw new IllegalStateException("Datei " + datei.rolle() + " " + datei.nr() + " von " + id
                        + " nicht gefunden");
            }
        }
        return true;
    }

    @Override
    public boolean fehlerSetzen(UUID id, String fehlertext) {
        zeitgrenze();
        return em().createNativeQuery("update dokument set status = 'ARCHIVIERUNG_FEHLER', fehler = :fehler, "
                        + "version = version + 1, geaendert = clock_timestamp() where id = :id "
                        + "and status = 'ARCHIVIERUNG'")
                .setParameter("fehler", fehlertext).setParameter("id", id).executeUpdate() == 1;
    }

    private void zeitgrenze() {
        em().createNativeQuery("select set_config('statement_timeout', '"
                + PanacheDokumentVerarbeitung.ZEITGRENZE + "', true)").getSingleResult();
    }

    private static Instant zeitpunkt(Object wert) {
        return switch (wert) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unerwarteter Typ für eingang: " + wert.getClass().getName());
        };
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
