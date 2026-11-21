package ch.urinet.archiv.wissenpaket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WissenPaketNetzTest {
    private static final Pattern NETZ = Pattern.compile(
            "(?m)\\b(?:connect|bind|sendto|sendmsg|sendmmsg)\\([^\\n]*AF_INET6?\\b");

    @TempDir Path arbeit;

    @Test
    @EnabledOnOs(OS.LINUX)
    void bauenUndValidierenOeffnenAuchMitQuellenpruefungKeineNetzverbindung() throws Exception {
        Path konzept = Files.createDirectories(arbeit.resolve("eingabe/konzepte"));
        Path quellen = Files.createDirectory(arbeit.resolve("quellen"));
        byte[] pdf = "synthetisches original".getBytes(StandardCharsets.UTF_8);
        Files.write(quellen.resolve("original.pdf"), pdf);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        Files.writeString(konzept.resolve("test.md"), """
                ---
                type: Konzept
                title: Test
                description: Synthetisches Beispiel.
                tags: []
                stale_after: null
                sources:
                  - id: q-test
                    sha256: '%s'
                    seite: 1
                    auszug: 'Wortlaut'
                ---
                # Beispiel
                """.formatted(hash));
        Path bin = Files.createDirectory(arbeit.resolve("bin"));
        Path extraktor = bin.resolve("pdftotext");
        Files.writeString(extraktor, "#!/bin/sh\nprintf 'Wortlaut\\f'\n");
        assertThat(extraktor.toFile().setExecutable(true)).isTrue();

        assertThat(netz(aufzeichnen("kontrolle", NetzGegenprobe.class.getName()))).isTrue();
        Path zip = arbeit.resolve("paket.zip");
        String bauTrace = aufzeichnen("bauen", WissenPaketCli.class.getName(),
                "bauen", konzept.getParent().toString(), "-o", zip.toString(),
                "--mandant", "urinet", "--erzeugt-von", "test", "--erzeugt-mit", "lokal",
                "--kontext-stufe", "NORMAL", "--quellen", quellen.toString());
        assertThat(netz(bauTrace)).as(bauTrace).isFalse();
        assertThat(Files.isRegularFile(zip)).isTrue();
        assertThat(netz(aufzeichnen("validate", WissenPaketCli.class.getName(),
                "validate", zip.toString(), "--quellen", quellen.toString(), "--json"))).isFalse();
    }

    @Test
    void unterscheidetNetzverbindungenVonDerSocketInitialisierung() {
        assertThat(netz("socket(AF_INET6, SOCK_STREAM, IPPROTO_IP) = 27")).isFalse();
        assertThat(netz("connect(4, {sa_family=AF_UNIX}, 10) = 0")).isFalse();
        assertThat(netz("connect(4, {sa_family=AF_INET, sin_port=0}, 16) = 0")).isTrue();
        assertThat(netz("sendto(4, 0, 0, 0, {sa_family=AF_INET6}, 28) = 0")).isTrue();
    }

    private String aufzeichnen(String name, String klasse, String... argumente) throws Exception {
        Path trace = arbeit.resolve(name + ".trace");
        Path ausgabe = arbeit.resolve(name + ".log");
        List<String> befehl = new ArrayList<>(List.of("strace", "-f", "-qq", "-e", "trace=network",
                "-o", trace.toString(), Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), klasse));
        befehl.addAll(List.of(argumente));
        ProcessBuilder builder = new ProcessBuilder(befehl).redirectErrorStream(true).redirectOutput(ausgabe.toFile());
        builder.environment().put("PATH", arbeit.resolve("bin") + ":" + System.getenv("PATH"));
        Process prozess = builder.start();
        try {
            assertThat(prozess.waitFor(30, TimeUnit.SECONDS)).as("strace und %s müssen enden", name).isTrue();
            assertThat(prozess.exitValue()).as(Files.readString(ausgabe)).isZero();
            return Files.readString(trace);
        } finally {
            prozess.descendants().forEach(ProcessHandle::destroyForcibly);
            prozess.destroyForcibly();
        }
    }

    private static boolean netz(String trace) {
        return NETZ.matcher(trace).find();
    }

    public static class NetzGegenprobe {
        public static void main(String[] args) throws Exception {
            InetAddress lokal = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            try (ServerSocket server = new ServerSocket(0, 1, lokal);
                 Socket client = new Socket(lokal, server.getLocalPort());
                 Socket verbindung = server.accept()) {
                client.getOutputStream().write(1);
                if (verbindung.getInputStream().read() != 1) throw new IllegalStateException("Gegenprobe fehlgeschlagen");
            }
        }
    }
}
