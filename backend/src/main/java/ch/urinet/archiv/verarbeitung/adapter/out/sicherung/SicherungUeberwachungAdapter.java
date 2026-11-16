package ch.urinet.archiv.verarbeitung.adapter.out.sicherung;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import ch.urinet.archiv.verarbeitung.application.SicherungUeberwachung;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prüft die jüngsten Sicherungsexporte, ohne Details der abgelegten Dateien offenzulegen. */
@ApplicationScoped
public class SicherungUeberwachungAdapter implements SicherungUeberwachung {

    private static final Logger LOG = Logger.getLogger(SicherungUeberwachungAdapter.class);

    private final SicherungExportSpeicher speicher;
    private final MandantRegister register;
    private final Duration frist;
    private final boolean aktiv;
    private final Clock uhr;
    private final AtomicBoolean deaktiviertGemeldet = new AtomicBoolean();

    @Inject
    public SicherungUeberwachungAdapter(SicherungExportSpeicher speicher, MandantRegister register,
                                       @ConfigProperty(name = "archiv.ueberwachung.sicherung-frist") Duration frist,
                                       @ConfigProperty(name = "archiv.ueberwachung.sicherung-aktiv") Boolean aktiv) {
        this(speicher, register, frist, aktiv, Clock.systemUTC());
    }

    SicherungUeberwachungAdapter(SicherungExportSpeicher speicher, MandantRegister register,
                                Duration frist, Boolean aktiv, Clock uhr) {
        this.speicher = speicher;
        this.register = register;
        this.frist = frist;
        this.aktiv = Ueberwachung.pruefeSicherungEinstellungen(frist, aktiv);
        this.uhr = uhr;
    }

    @Override
    public Optional<List<Befund>> befunde() {
        if (!aktiv) {
            if (deaktiviertGemeldet.compareAndSet(false, true)) {
                LOG.warn("Überwachung der Sicherung ist deaktiviert; bestehende Sicherungsalarme bleiben unverändert");
            }
            return Optional.empty();
        }

        Instant grenze = uhr.instant().minus(frist);
        List<Befund> befunde = new ArrayList<>();
        for (SicherungsArt art : SicherungsArt.values()) {
            if (!art.mandantPflicht()) {
                pruefen(art, Optional.empty(), art.code(), grenze, befunde);
            }
        }
        for (Mandant mandant : register.aktive()) {
            for (SicherungsArt art : SicherungsArt.values()) {
                if (art.mandantPflicht()) {
                    MandantCode code = mandant.code();
                    pruefen(art, Optional.of(code), art.code() + "-" + code.wert(), grenze, befunde);
                }
            }
        }
        return Optional.of(List.copyOf(befunde));
    }

    private void pruefen(SicherungsArt art, Optional<MandantCode> mandant, String gegenstand, Instant grenze,
                        List<Befund> befunde) {
        Optional<Instant> letzter = speicher.juengsterEintrag(art, mandant);
        if (letzter.isEmpty()) {
            befunde.add(new Befund(Befund.Quelle.SICHERUNG, gegenstand, "FEHLT"));
        } else if (letzter.get().isBefore(grenze)) {
            befunde.add(new Befund(Befund.Quelle.SICHERUNG, gegenstand, "ZU_ALT"));
        }
    }
}
