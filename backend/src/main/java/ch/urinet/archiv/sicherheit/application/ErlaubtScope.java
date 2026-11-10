package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Zusatz zu {@link ErfordertAktion}: Die Methode nimmt auch ein API-Token dieses Scopes an (Req 2.5). Ohne diese
 * Erklärung antwortet jeder Endpunkt einem Token mit 403. {@code @RolesAllowed} nennt zusätzlich
 * {@link ApiTokenScope#rolle()}. {@code EndpunktWaechterTest} erlaubt sie nur an {@code POST /dokumente}
 * ({@code import}) und {@code POST /dokumente/generator} ({@code generator}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ErlaubtScope {
    ApiTokenScope value();
}
