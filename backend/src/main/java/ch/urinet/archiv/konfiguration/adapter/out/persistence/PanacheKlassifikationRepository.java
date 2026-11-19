package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.KlassifikationRepository;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheKlassifikationRepository implements KlassifikationRepository {

    private final Instance<EntityManager> em;

    public PanacheKlassifikationRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    private EntityManager entityManager() {
        return em.get();
    }

    @Override
    public List<Klassifikation> alle() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager().createNativeQuery("""
                select code, name, beschreibung, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv
                from klassifikation order by code""").getResultList();
        return rows.stream().map(this::klassifikation).toList();
    }

    @Override
    public Optional<Klassifikation> finden(String code) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager().createNativeQuery("""
                select code, name, beschreibung, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv
                from klassifikation where code = :code""").setParameter("code", code).getResultList();
        return rows.stream().findFirst().map(this::klassifikation);
    }

    @Override
    public void anlegen(Klassifikation klassifikation) {
        entityManager().createNativeQuery("""
                insert into klassifikation
                    (code, name, beschreibung, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv)
                values (:code, :name, :beschreibung, :mehrfach, :ki, :suchfilter, :oeffentlich, :aktiv)""")
                .setParameter("code", klassifikation.code()).setParameter("name", klassifikation.name())
                .setParameter("beschreibung", klassifikation.beschreibung())
                .setParameter("mehrfach", klassifikation.mehrfach())
                .setParameter("ki", klassifikation.kiVorschlag()).setParameter("suchfilter", klassifikation.suchfilter())
                .setParameter("oeffentlich", klassifikation.oeffentlichFilter())
                .setParameter("aktiv", klassifikation.aktiv()).executeUpdate();
        werteSpeichern(klassifikation);
    }

    @Override
    public void speichern(Klassifikation klassifikation) {
        entityManager().createNativeQuery("""
                update klassifikation set name = :name, beschreibung = :beschreibung, mehrfach = :mehrfach,
                    ki_vorschlag = :ki, suchfilter = :suchfilter, oeffentlich_filter = :oeffentlich, aktiv = :aktiv
                where code = :code""")
                .setParameter("name", klassifikation.name()).setParameter("beschreibung", klassifikation.beschreibung())
                .setParameter("mehrfach", klassifikation.mehrfach())
                .setParameter("ki", klassifikation.kiVorschlag()).setParameter("suchfilter", klassifikation.suchfilter())
                .setParameter("oeffentlich", klassifikation.oeffentlichFilter())
                .setParameter("aktiv", klassifikation.aktiv()).setParameter("code", klassifikation.code())
                .executeUpdate();
        werteSpeichern(klassifikation);
    }

    @Override
    public void loeschen(String code) {
        entityManager().createNativeQuery("delete from klassifikation where code = :code").setParameter("code", code)
                .executeUpdate();
    }

    @Override
    public boolean inGebrauch(String code) {
        Number count = (Number) entityManager().createNativeQuery("""
                select (select count(*) from dokument_klassifikation where klassifikation = :code)
                     + (select count(*) from dokumentart_klassifikation where klassifikation = :code)""")
                .setParameter("code", code).getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    public boolean wertInGebrauch(String code, String wert) {
        Number count = (Number) entityManager().createNativeQuery("""
                select count(*) from dokument_klassifikation where klassifikation = :code and wert = :wert""")
                .setParameter("code", code).setParameter("wert", wert).getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    public long dokumentAnzahl(String code) {
        Number count = (Number) entityManager().createNativeQuery("""
                select count(distinct dokument_id) from dokument_klassifikation where klassifikation = :code""")
                .setParameter("code", code).getSingleResult();
        return count.longValue();
    }

    @Override
    public long wertDokumentAnzahl(String code, String wert) {
        Number count = (Number) entityManager().createNativeQuery("""
                select count(distinct dokument_id) from dokument_klassifikation
                where klassifikation = :code and wert = :wert""")
                .setParameter("code", code).setParameter("wert", wert).getSingleResult();
        return count.longValue();
    }

    @Override
    public boolean mehrfachWirdVerletzt(String code) {
        Number count = (Number) entityManager().createNativeQuery("""
                select count(*) from (
                    select dokument_id from dokument_klassifikation
                    where klassifikation = :code group by dokument_id having count(*) > 1
                ) mehrfach""").setParameter("code", code).getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    public List<ArtKlassifikation> zuordnungen(String art) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager().createNativeQuery("""
                select klassifikation, pflicht, standard_wert from dokumentart_klassifikation
                where art = :art order by klassifikation""").setParameter("art", art).getResultList();
        return rows.stream().map(row -> new ArtKlassifikation((String) row[0], (Boolean) row[1], (String) row[2]))
                .toList();
    }

    @Override
    public boolean zugeordnet(String art, String klassifikation) {
        Number count = (Number) entityManager().createNativeQuery("""
                select count(*) from dokumentart_klassifikation where art = :art and klassifikation = :klasse""")
                .setParameter("art", art).setParameter("klasse", klassifikation).getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    public void zuordnen(String art, ArtKlassifikation zuordnung) {
        entityManager().createNativeQuery("""
                insert into dokumentart_klassifikation (art, klassifikation, pflicht, standard_wert)
                values (:art, :klasse, :pflicht, :standard)""")
                .setParameter("art", art).setParameter("klasse", zuordnung.klassifikation())
                .setParameter("pflicht", zuordnung.pflicht()).setParameter("standard", zuordnung.standardWert())
                .executeUpdate();
    }

    @Override
    public void zuordnungSpeichern(String art, ArtKlassifikation zuordnung) {
        entityManager().createNativeQuery("""
                update dokumentart_klassifikation set pflicht = :pflicht, standard_wert = :standard
                where art = :art and klassifikation = :klasse""")
                .setParameter("art", art).setParameter("klasse", zuordnung.klassifikation())
                .setParameter("pflicht", zuordnung.pflicht()).setParameter("standard", zuordnung.standardWert())
                .executeUpdate();
    }

    @Override
    public void zuordnungEntfernen(String art, String klassifikation) {
        entityManager().createNativeQuery("""
                delete from dokumentart_klassifikation where art = :art and klassifikation = :klasse""")
                .setParameter("art", art).setParameter("klasse", klassifikation).executeUpdate();
    }

    private Klassifikation klassifikation(Object[] row) {
        String code = (String) row[0];
        @SuppressWarnings("unchecked")
        List<Object[]> werte = entityManager().createNativeQuery("""
                select code, name, beschreibung, reihenfolge, aktiv from klassifikation_wert
                where klassifikation = :code order by reihenfolge, code""")
                .setParameter("code", code).getResultList();
        List<KlassifikationWert> liste = new ArrayList<>(werte.size());
        for (Object[] wert : werte) {
            liste.add(new KlassifikationWert((String) wert[0], (String) wert[1], (String) wert[2],
                    ((Number) wert[3]).intValue(), (Boolean) wert[4]));
        }
        return new Klassifikation(code, (String) row[1], (String) row[2], liste, (Boolean) row[3],
                (Boolean) row[4], (Boolean) row[5], (Boolean) row[6], (Boolean) row[7]);
    }

    private void werteSpeichern(Klassifikation klassifikation) {
        List<String> codes = klassifikation.werte().stream().map(KlassifikationWert::code).toList();
        if (codes.isEmpty()) {
            entityManager().createNativeQuery("delete from klassifikation_wert where klassifikation = :code")
                    .setParameter("code", klassifikation.code()).executeUpdate();
        } else {
            entityManager().createNativeQuery("""
                    delete from klassifikation_wert where klassifikation = :code and code not in (:codes)""")
                    .setParameter("code", klassifikation.code()).setParameter("codes", codes).executeUpdate();
        }
        for (KlassifikationWert wert : klassifikation.werte()) {
            Number count = (Number) entityManager().createNativeQuery("""
                    select count(*) from klassifikation_wert where klassifikation = :klasse and code = :code""")
                    .setParameter("klasse", klassifikation.code()).setParameter("code", wert.code()).getSingleResult();
            if (count.longValue() == 0) {
                entityManager().createNativeQuery("""
                        insert into klassifikation_wert
                            (klassifikation, code, name, beschreibung, reihenfolge, aktiv)
                        values (:klasse, :code, :name, :beschreibung, :reihenfolge, :aktiv)""")
                        .setParameter("klasse", klassifikation.code()).setParameter("code", wert.code())
                        .setParameter("name", wert.name()).setParameter("beschreibung", wert.beschreibung())
                        .setParameter("reihenfolge", wert.reihenfolge()).setParameter("aktiv", wert.aktiv())
                        .executeUpdate();
            } else {
                entityManager().createNativeQuery("""
                        update klassifikation_wert set name = :name, beschreibung = :beschreibung,
                            reihenfolge = :reihenfolge, aktiv = :aktiv
                        where klassifikation = :klasse and code = :code""")
                        .setParameter("klasse", klassifikation.code()).setParameter("code", wert.code())
                        .setParameter("name", wert.name()).setParameter("beschreibung", wert.beschreibung())
                        .setParameter("reihenfolge", wert.reihenfolge()).setParameter("aktiv", wert.aktiv())
                        .executeUpdate();
            }
        }
    }
}
