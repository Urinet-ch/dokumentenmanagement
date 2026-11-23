package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KonfigurationsEinstellungenSpeichern;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.Wissenszeitplan;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class JdbcKonfigurationsEinstellungenSpeichern implements KonfigurationsEinstellungenSpeichern {

    private final Instance<EntityManager> em;
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;
    private final ObjectMapper json;
    private final WissenEinstellungenRepository wissen;
    private final String standardZeitplan;

    public JdbcKonfigurationsEinstellungenSpeichern(Instance<EntityManager> em, MandantAufloesung aufloesung,
                                                    MandantKontext kontext, ObjectMapper json,
                                                    WissenEinstellungenRepository wissen,
                                                    @ConfigProperty(name = "archiv.wissen.lint-zeitplan",
                                                            defaultValue = "0 0 4 ? * SUN") String standardZeitplan) {
        this.em = em;
        this.aufloesung = aufloesung;
        this.kontext = kontext;
        this.json = json;
        this.wissen = wissen;
        this.standardZeitplan = standardZeitplan;
    }

    @Override
    public void speichern(Einstellungen einstellungen) {
        String ibans;
        try {
            ibans = json.writeValueAsString(einstellungen.kennungen().ibans());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mandantenkennungen nicht serialisierbar", e);
        }
        int aktualisiert = em.get().createNativeQuery("""
                update public.mandant
                set geschaeftsjahr_ende = :ende,
                    kennungen = jsonb_build_object('uid', cast(:uid as text), 'ibans', cast(:ibans as jsonb))
                where code = :code""")
                .setParameter("ende", einstellungen.geschaeftsjahrEnde())
                .setParameter("uid", einstellungen.kennungen().uid())
                .setParameter("ibans", ibans)
                .setParameter("code", kontext.aktuell().orElseGet(aufloesung::pruefen).wert())
                .executeUpdate();
        if (aktualisiert != 1) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        var werte = einstellungen.wissen();
        wissen.speichern(new WissenEinstellungen.Werte(werte.budgetTokens(), werte.lintBudgetTokens(),
                Wissenszeitplan.cron(werte.lintZeitplan(), standardZeitplan), werte.monatsbudgetTokens()));
    }
}
