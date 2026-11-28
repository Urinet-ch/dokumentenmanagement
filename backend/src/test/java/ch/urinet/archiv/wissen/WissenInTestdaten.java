package ch.urinet.archiv.wissen;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/**
 * Nur Tests, Testdaten (Spec 04, Req 22.1, design.md «Wissen je Mandant freischalten»): Neue Mandanten haben kein Wissen.
 * Die beiden Standard-Testmandanten {@value #URINET} und {@value #VHF_GSK}, mit denen die bestehenden Szenarien und Tests
 * die Wissensbasis prüfen, schalten Wissen hier ausdrücklich ein, sobald die Provisionierung sie aktiviert. Jeder andere Testmandant bleibt ohne Wissen, bis ein Test ihn mit {@code WissenFreischalten} einschaltet;
 * die Szenarien zu Req 22 schalten auch diese beiden ausdrücklich aus und wieder ein. Der Programmcode kennt keinen
 * Sonderfall für einen Mandantencode.
 */
@Decorator
@Priority(10)
public abstract class WissenInTestdaten implements MandantRegister {

    public static final String URINET = "urinet";
    public static final String VHF_GSK = "vhf-gsk";
    private static final Set<String> MIT_WISSEN = Set.of(URINET, VHF_GSK);

    @Inject
    @Delegate
    @Any
    MandantRegister register;

    @Inject
    Instance<AgroalDataSource> datenbank;

    // Standardmethoden des Interfaces ausdrücklich weiterreichen, sonst liefe im Decorator die leere Standardfassung
    @Override
    public Optional<Mandant> inProvisionierung(MandantCode code) {
        return register.inProvisionierung(code);
    }

    @Override
    public void aktivieren(MandantCode code) {
        register.aktivieren(code);
        if (!MIT_WISSEN.contains(code.wert())) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement setzen = verbindung.prepareStatement(
                "update public.mandant set wissen_freigeschaltet = true where code = ?")) {
            setzen.setString(1, code.wert());
            setzen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Testdaten: Wissen nicht eingeschaltet", e);
        }
    }
}
