package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;

import java.util.List;

/** Register in {@code public.mandant}; auch beim Start und im Admin-Kommando, darum mit eigenem Request-Kontext (Tenant-Resolver). */
@ApplicationScoped
@ActivateRequestContext
public class PanacheMandantRegister implements MandantRegister {

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
}
