package ch.urinet.archiv.integration.application.webseite;

import java.net.http.HttpClient;

/** Gegenprobe zu {@code ArchitekturTest.keinHttpClientInDerWebseitenSchnittstelle}: ein HTTP-Client im Paket. */
public class HttpClientGegenprobe {

    private final HttpClient client = HttpClient.newHttpClient();

    public HttpClient client() {
        return client;
    }
}
