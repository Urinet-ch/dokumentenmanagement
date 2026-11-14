package ch.urinet.archiv.sicherheit.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine Aktion, die eine dokumentbezogene Matrixzeile verwendet, aber kein Dokument betrifft. Der Zugangfilter
 * prüft nur das Aktionsrecht und keine Sichtbarkeit eines Dokuments.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NichtDokumentbezogen {
}
