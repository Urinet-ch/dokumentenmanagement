package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.KiTexteRepository;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.KiTexte;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class PanacheKiTexteRepository implements KiTexteRepository {

    private final Instance<EntityManager> em;

    public PanacheKiTexteRepository(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public KiTexte lesen() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.get().createNativeQuery("""
                select rolle, feldregeln, ki_freigegeben from ki_texte where id = 1""").getResultList();
        return rows.stream().findFirst()
                .map(row -> new KiTexte((String) row[0], (String) row[1], (Boolean) row[2]))
                .orElseThrow(() -> new IllegalStateException("KI-Texte für den Mandanten fehlen"));
    }

    @Override
    public void speichern(KiTexte kiTexte) {
        int aktualisiert = em.get().createNativeQuery("""
                update ki_texte set rolle = :rolle, feldregeln = :feldregeln, ki_freigegeben = :freigegeben
                where id = 1""")
                .setParameter("rolle", kiTexte.rolle())
                .setParameter("feldregeln", kiTexte.feldregeln())
                .setParameter("freigegeben", kiTexte.kiFreigegeben())
                .executeUpdate();
        if (aktualisiert != 1) {
            throw new IllegalStateException("KI-Texte für den Mandanten fehlen");
        }
    }
}
