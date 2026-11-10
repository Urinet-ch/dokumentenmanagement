package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import jakarta.enterprise.context.RequestScoped;

/**
 * Schema pro Mandant (ADR-002): Die Tenant-ID ist der Schemaname, den Quarkus per {@code Connection.setSchema} setzt.
 * Ohne Mandant {@code public} – dort gibt es keine Mandanten-Tabellen, ein Zugriff darauf scheitert (fail closed).
 */
@PersistenceUnitExtension
@RequestScoped
public class MandantTenantResolver implements TenantResolver {

    private final MandantAufloesung aufloesung;

    public MandantTenantResolver(MandantAufloesung aufloesung) {
        this.aufloesung = aufloesung;
    }

    @Override
    public String getDefaultTenantId() {
        return MandantAufloesung.OHNE_MANDANT;
    }

    @Override
    public String resolveTenantId() {
        return aufloesung.schema();
    }
}
