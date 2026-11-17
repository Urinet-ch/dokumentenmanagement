package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.FesterFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheDokumente;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlichesDokument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dokumente für den öffentlichen Chatbot (Spec 03, Aufgabe 11.3): jede Abfrage mit dem festen Filter
 * ({@link FesterFilter}) und den Stufen aus dem {@link Lesezugriff}, nie mit der Sicht der App. Das SQL entsteht aus
 * festen Stücken, jeder Wert wird gebunden. Titel, Reihe, Ausgabe und Jahr (aus {@code zusatz}) sind die einzigen Felder;
 * {@code zusatz.jahr} zählt nur als ganze Zahl.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheOeffentlicheDokumente implements OeffentlicheDokumente {

    @Override
    public List<OeffentlichesDokument> sichtbare(Collection<UUID> ids, Lesezugriff zugriff) {
        if (ids.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("select d.id, d.titel, d.art, a.name, "
                        + "d.zusatz ->> 'ausgabe', case when jsonb_typeof(d.zusatz -> 'jahr') = 'number' "
                        + "then d.zusatz ->> 'jahr' end, d.seiten from dokument d left join dokumentart a on a.code = d.art "
                        + "where d.id in (:ids) and " + FesterFilter.bedingung("d"))
                .setParameter("ids", List.copyOf(ids))
                .setParameter(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff)).getResultList();
        List<OeffentlichesDokument> ergebnis = new ArrayList<>(zeilen.size());
        for (Object[] z : zeilen) {
            ergebnis.add(new OeffentlichesDokument((UUID) z[0], (String) z[1], (String) z[2], (String) z[3],
                    (String) z[4], jahr((String) z[5]), z[6] == null ? null : ((Number) z[6]).intValue()));
        }
        return List.copyOf(ergebnis);
    }

    @Override
    public Optional<String> inhalt(UUID id, Lesezugriff zugriff, int zeichen) {
        @SuppressWarnings("unchecked")
        List<String> zeilen = em().createNativeQuery("select left(d.volltext, :zeichen) from dokument d "
                        + "where d.id = :id and " + FesterFilter.bedingung("d"))
                .setParameter("id", id).setParameter("zeichen", zeichen)
                .setParameter(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff)).getResultList();
        // ein Dokument ohne Volltext liefert eine Zeile mit null
        return zeilen.stream().filter(java.util.Objects::nonNull).findFirst().filter(t -> !t.isBlank());
    }

    private static Integer jahr(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
