package ch.urinet.archiv.integration;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ein HTTP/1.1-Client auf einem rohen Socket für die Streams der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.8): Er
 * liest den Körper genau so schnell, wie der Test es will, und setzt einen kleinen Empfangspuffer, damit ein
 * stehengebliebener Leser den Server wirklich aufhält (Gegendruck) und nicht der Kernel die Datei aufnimmt. Nur für
 * Antworten mit {@code Content-Length} und {@code Connection: close}.
 */
public final class RohClient implements Closeable {

    /** Statuszeile und Kopf; der Körper folgt aus {@link #koerper()}. */
    public record Kopf(int status, Map<String, String> header) {
        public long laenge() {
            return Long.parseLong(header.getOrDefault("content-length", "-1"));
        }
    }

    private final Socket socket;
    private final InputStream ein;
    private Kopf kopf;

    private RohClient(Socket socket) throws IOException {
        this.socket = socket;
        this.ein = socket.getInputStream();
    }

    /** Schickt die Anfrage und liest den Kopf der Antwort; {@code kopfzeilen} sind {@code Name: Wert}. */
    public static RohClient get(String host, int port, String pfad, String... kopfzeilen) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4096);
        socket.setSoTimeout(60_000);
        socket.connect(new InetSocketAddress(host, port));
        RohClient client = new RohClient(socket);
        StringBuilder anfrage = new StringBuilder("GET ").append(pfad).append(" HTTP/1.1\r\nHost: ").append(host)
                .append("\r\nConnection: close\r\n");
        for (String zeile : kopfzeilen) {
            anfrage.append(zeile).append("\r\n");
        }
        anfrage.append("\r\n");
        socket.getOutputStream().write(anfrage.toString().getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        client.kopf = client.kopfLesen();
        return client;
    }

    public Kopf kopf() {
        return kopf;
    }

    public InputStream koerper() {
        return ein;
    }

    private Kopf kopfLesen() throws IOException {
        ByteArrayOutputStream zeile = new ByteArrayOutputStream();
        String status = null;
        Map<String, String> header = new LinkedHashMap<>();
        for (int b; (b = ein.read()) >= 0; ) {
            if (b == '\n') {
                String text = zeile.toString(StandardCharsets.ISO_8859_1).stripTrailing();
                zeile.reset();
                if (text.isEmpty()) {
                    break;
                }
                if (status == null) {
                    status = text;
                } else {
                    int doppelpunkt = text.indexOf(':');
                    header.putIfAbsent(text.substring(0, doppelpunkt).toLowerCase(Locale.ROOT),
                            text.substring(doppelpunkt + 1).strip());
                }
            } else {
                zeile.write(b);
            }
        }
        if (status == null) {
            throw new IOException("Keine Antwort");
        }
        return new Kopf(Integer.parseInt(status.split(" ")[1]), header);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // schon zu
        }
    }
}
