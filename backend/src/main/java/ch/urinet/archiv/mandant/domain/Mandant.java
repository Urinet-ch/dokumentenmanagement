package ch.urinet.archiv.mandant.domain;

/** Eintrag im Mandantenregister ({@code public.mandant}). */
public record Mandant(MandantCode code, String name, String schema, String bucket,
                      Geschaeftsjahresende geschaeftsjahresende, boolean aktiv) {
}
