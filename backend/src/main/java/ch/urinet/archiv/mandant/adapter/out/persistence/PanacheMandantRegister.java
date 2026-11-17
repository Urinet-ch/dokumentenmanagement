package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Register in {@code public.mandant}; auch beim Start und im Admin-Kommando, darum mit eigenem Request-Kontext (Tenant-Resolver). */
@ApplicationScoped
@ActivateRequestContext
public class PanacheMandantRegister implements MandantRegister, MandantAbbauRegister {

    @Override
    @Transactional
    public boolean existiert(MandantCode code) {
        return MandantEintrag.findById(code.wert()) != null;
    }

    @Override
    @Transactional
    public void eintragen(Mandant mandant) {
        MandantEintrag eintrag = new MandantEintrag();
        eintrag.code = mandant.code().wert();
        eintrag.name = mandant.name();
        eintrag.schemaName = mandant.schema();
        eintrag.bucket = mandant.bucket();
        eintrag.geschaeftsjahrEnde = mandant.geschaeftsjahresende().wert();
        eintrag.aktiv = mandant.aktiv();
        eintrag.persist();
    }

    @Override
    @Transactional
    public List<Mandant> aktive() {
        return MandantEintrag.<MandantEintrag>list("aktiv = true order by code").stream()
                .map(e -> new Mandant(new MandantCode(e.code), e.name, e.schemaName, e.bucket,
                        new Geschaeftsjahresende(e.geschaeftsjahrEnde), e.aktiv))
                .toList();
    }

    @Override
    @Transactional
    public Optional<Instant> erstellt(MandantCode code) {
        MandantEintrag eintrag = MandantEintrag.findById(code.wert());
        return eintrag == null ? Optional.empty() : Optional.of(eintrag.erstellt.toInstant());
    }

    @Override
    @Transactional
    public Optional<MandantAbbauRegister.Status> status(MandantCode code) {
        MandantEintrag eintrag = MandantEintrag.findById(code.wert());
        if (eintrag == null) {
            return Optional.empty();
        }
        Mandant mandant = new Mandant(code, eintrag.name, eintrag.schemaName, eintrag.bucket,
                new Geschaeftsjahresende(eintrag.geschaeftsjahrEnde), eintrag.aktiv);
        return Optional.of(new MandantAbbauRegister.Status(mandant,
                Optional.ofNullable(eintrag.deaktiviertAm).map(OffsetDateTime::toInstant),
                Optional.ofNullable(eintrag.vollexportBestaetigt), eintrag.abbauAbgeschlossen));
    }

    @Override
    @Transactional
    public boolean deaktivieren(MandantCode code, UUID vollexportId, Instant jetzt) {
        MandantEintrag eintrag = MandantEintrag.findById(code.wert());
        if (eintrag == null) {
            return false;
        }
        if (!eintrag.aktiv) {
            return vollexportId.equals(eintrag.vollexportBestaetigt);
        }
        eintrag.aktiv = false;
        eintrag.deaktiviertAm = OffsetDateTime.ofInstant(jetzt, java.time.ZoneOffset.UTC);
        eintrag.vollexportBestaetigt = vollexportId;
        return true;
    }

    @Override
    @Transactional
    public boolean abschliessen(MandantCode code) {
        MandantEintrag eintrag = MandantEintrag.findById(code.wert());
        if (eintrag == null || eintrag.aktiv || eintrag.deaktiviertAm == null
                || eintrag.vollexportBestaetigt == null) {
            return false;
        }
        eintrag.abbauAbgeschlossen = true;
        return true;
    }
}
