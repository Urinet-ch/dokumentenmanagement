package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * {@link DokumentVerarbeitungPort} mit nativem SQL im Schema des Mandanten aus dem Kontext (ADR-002). Jede Methode
 * läuft in der Transaktion des Aufrufers und setzt zuerst {@code statement_timeout} für diese Transaktion (Pflicht (1)
 * der Handler, design.md «Verarbeitung»). Jede Änderung erhöht {@code version}, damit ein gleichzeitiges
 * {@code PATCH} mit altem ETag scheitert.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentVerarbeitung implements DokumentVerarbeitungPort {

    static final String ZEITGRENZE = "30s";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String OFFEN = "status in ('NEU', 'VERARBEITUNG')";

    @Override
    public Optional<Verarbeitungsstand> laden(UUID id) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select status, eingang, quelle, (s3_version_pdfa is not null or pdfa_fehlt)
                        from dokument where id = :id""")
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
        return Optional.of(new Verarbeitungsstand(id, DokumentStatus.valueOf((String) d[0]), zeitpunkt(d[1]),
                (String) d[2], (Boolean) d[3], dateien));
    }

    @Override
    public boolean verarbeitungBeginnen(UUID id) {
        zeitgrenze();
        return em().createNativeQuery("update dokument set status = 'VERARBEITUNG', version = version + 1, "
                        + "geaendert = clock_timestamp() where id = :id and " + OFFEN
                        + " and s3_version_pdfa is null and not pdfa_fehlt")
                .setParameter("id", id).executeUpdate() == 1;
    }

    @Override
    public OptionalInt ergebnisSpeichern(UUID id, Verarbeitungsergebnis ergebnis, DokumentStatus neuerStatus) {
        zeitgrenze();
        List<?> zeilen = em().createNativeQuery("""
                        update dokument set sha256_pdfa = :sha256, s3_pdfa = :s3, s3_version_pdfa = :version,
                            pdfa_fehlt = :fehlt, fehler = :fehler, seiten = :seiten, volltext = :volltext,
                            status = :status, version = version + 1, geaendert = clock_timestamp()
                        where id = :id and status = 'VERARBEITUNG' and s3_version_pdfa is null and not pdfa_fehlt
                        returning version""")
                .unwrap(NativeQuery.class)
                // mit Typ, weil ein leerer Wert sonst ohne Typ gebunden würde
                .setParameter("sha256", ergebnis.sha256Pdfa(), String.class)
                .setParameter("s3", ergebnis.s3Pdfa(), String.class)
                .setParameter("version", ergebnis.s3VersionPdfa(), String.class)
                .setParameter("fehlt", ergebnis.pdfaFehlt())
                .setParameter("fehler", ergebnis.pdfaFehlt() ? ergebnis.fehlertext() : null, String.class)
                .setParameter("seiten", ergebnis.seiten(), Integer.class)
                // PostgreSQL nimmt kein NUL in text an; pdftotext kann es aus kaputten Schriften liefern
                .setParameter("volltext", ergebnis.volltext().replace("\u0000", ""))
                .setParameter("status", neuerStatus.name())
                .setParameter("id", id)
                .getResultList();
        return zeilen.isEmpty() ? OptionalInt.empty() : OptionalInt.of(((Number) zeilen.getFirst()).intValue());
    }

    @Override
    public boolean fehlerSetzen(UUID id, String fehlertext) {
        zeitgrenze();
        return em().createNativeQuery("update dokument set status = 'FEHLER', fehler = :fehler, "
                        + "version = version + 1, geaendert = clock_timestamp() where id = :id and " + OFFEN)
                .setParameter("fehler", fehlertext).setParameter("id", id).executeUpdate() == 1;
    }

    @Override
    public Optional<KiStand> kiStandLaden(UUID id) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select status, bestaetigt is not null, version, pdfa_fehlt, ki_vorschlag is not null, volltext,
                            eingang, s3_pdfa, s3_version_pdfa, sha256_pdfa,
                            (select mime from dokument_datei where dokument_id = d.id and rolle = 'ORIGINAL'
                                and status = 'AKTIV' order by nr limit 1)
                        from dokument d where id = :id""")
                .setParameter("id", id).getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] d = zeilen.getFirst();
        Optional<Datei> pdfa = d[8] == null ? Optional.empty()
                : Optional.of(new Datei(DateiRolle.PDFA, 1, "application/pdf", (String) d[7], (String) d[8],
                        (String) d[9]));
        return Optional.of(new KiStand(id, DokumentStatus.valueOf((String) d[0]), (Boolean) d[1],
                ((Number) d[2]).intValue(), (Boolean) d[3], (Boolean) d[4], (String) d[5], zeitpunkt(d[6]), pdfa,
                (String) d[10]));
    }

    @Override
    public boolean vorschlagSpeichern(UUID id, int erwarteteVersion, Map<String, Object> vorschlag,
                                      Map<String, BigDecimal> sicherheit, UUID partnerId, String partnerName) {
        zeitgrenze();
        String sql = "update dokument set ki_vorschlag = cast(:vorschlag as jsonb), "
                + "ki_sicherheit = cast(:sicherheit as jsonb), status = 'EINGANGSKORB', version = version + 1, "
                + "geaendert = clock_timestamp()"
                + (partnerId == null ? "" : ", partner_name = case when partner_id is null then :partnerName "
                        + "else partner_name end, partner_id = coalesce(partner_id, :partner), ki_partner_id = :partner")
                + " where id = :id and status in ('VERARBEITUNG', 'EINGANGSKORB') and bestaetigt is null "
                + "and ki_vorschlag is null and version = :erwartet";
        var abfrage = em().createNativeQuery(sql)
                .setParameter("vorschlag", json(vorschlag))
                .setParameter("sicherheit", json(sicherheit))
                .setParameter("id", id)
                .setParameter("erwartet", erwarteteVersion);
        if (partnerId != null) {
            abfrage.setParameter("partner", partnerId).setParameter("partnerName", partnerName);
        }
        return abfrage.executeUpdate() == 1;
    }

    @Override
    public boolean ohneVorschlagWeiter(UUID id) {
        zeitgrenze();
        return em().createNativeQuery("update dokument set status = 'EINGANGSKORB', version = version + 1, "
                        + "geaendert = clock_timestamp() where id = :id and status = 'VERARBEITUNG'")
                .setParameter("id", id).executeUpdate() == 1;
    }

    private static String json(Object wert) {
        try {
            return JSON.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("KI-Vorschlag nicht serialisierbar", e);
        }
    }

    private void zeitgrenze() {
        em().createNativeQuery("select set_config('statement_timeout', '" + ZEITGRENZE + "', true)").getSingleResult();
    }

    private static Instant zeitpunkt(Object wert) {
        return switch (wert) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unerwarteter Typ für eingang: " + wert.getClass().getName());
        };
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
