package ch.urinet.archiv.dokumentart.adapter.out.persistence;

import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/** {@link DokumentartRepository} über {@link DokumentartEintrag} im Schema des Mandanten der Anfrage (ADR-002). */
@ApplicationScoped
public class PanacheDokumentartRepository implements DokumentartRepository {

    @Override
    public List<Dokumentart> alle() {
        return DokumentartEintrag.<DokumentartEintrag>listAll(Sort.by("code")).stream()
                .map(PanacheDokumentartRepository::art).toList();
    }

    @Override
    public Optional<Dokumentart> finden(String code) {
        return DokumentartEintrag.<DokumentartEintrag>findByIdOptional(code).map(PanacheDokumentartRepository::art);
    }

    @Override
    public boolean existiert(String code) {
        return DokumentartEintrag.count("code", code) > 0;
    }

    @Override
    public void anlegen(Dokumentart art) {
        DokumentartEintrag eintrag = new DokumentartEintrag();
        eintrag.code = art.code();
        uebernehmen(art, eintrag);
        eintrag.persist();
    }

    @Override
    public void speichern(Dokumentart art) {
        DokumentartEintrag eintrag = DokumentartEintrag.findById(art.code());
        uebernehmen(art, eintrag);
    }

    @Override
    public void loeschen(String code) {
        DokumentartEintrag.deleteById(code);
    }

    private static void uebernehmen(Dokumentart art, DokumentartEintrag eintrag) {
        eintrag.name = art.name();
        eintrag.beschreibung = art.beschreibung();
        eintrag.gruppen = art.gruppen().toArray(String[]::new);
        eintrag.pflichtfelder = art.pflichtfelder().toArray(String[]::new);
        eintrag.zusatzSchema = art.zusatzSchema();
        eintrag.vertraulichkeit = art.vertraulichkeit().name();
        eintrag.aufbewahrungJahre = art.aufbewahrung().jahre();
        eintrag.aufbewahrungAb = art.aufbewahrung().ab() == null ? null : art.aufbewahrung().ab().name();
        eintrag.gesetzlichePflicht = art.gesetzlichePflicht();
        eintrag.aktiv = art.aktiv();
    }

    /** Eine gespeicherte Art, die die Regeln verletzt, ist ein Serverfehler (500), keine ungültige Anfrage. */
    private static Dokumentart art(DokumentartEintrag e) {
        try {
            return gelesen(e);
        } catch (FachFehler | IllegalArgumentException | NullPointerException fehler) {
            throw new IllegalStateException("Dokumentart " + e.code + " in der Datenbank ist ungültig", fehler);
        }
    }

    private static Dokumentart gelesen(DokumentartEintrag e) {
        return new Dokumentart(e.code, e.name, e.beschreibung, liste(e.gruppen), liste(e.pflichtfelder),
                e.zusatzSchema == null ? Dokumentart.OHNE_ZUSATZ : e.zusatzSchema,
                Vertraulichkeit.valueOf(e.vertraulichkeit),
                new Aufbewahrungsregel(e.aufbewahrungJahre,
                        e.aufbewahrungAb == null ? null : AufbewahrungAb.valueOf(e.aufbewahrungAb)),
                e.gesetzlichePflicht, e.aktiv);
    }

    private static List<String> liste(String[] werte) {
        return werte == null ? List.of() : List.of(werte);
    }
}
