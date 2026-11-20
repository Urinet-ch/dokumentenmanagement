package ch.urinet.archiv.wissen.domain.paket;

public record PaketQuelle(String id, String resource, String sha256, String seite, String auszug,
                          boolean wissensquelle) {}
