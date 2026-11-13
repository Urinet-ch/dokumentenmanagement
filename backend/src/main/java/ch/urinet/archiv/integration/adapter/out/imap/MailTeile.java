package ch.urinet.archiv.integration.adapter.out.imap;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Anhang;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Zerlegt eine Nachricht (MIME) in Text und Anhänge, auch in angehängten Nachrichten ({@code message/rfc822}, bis Tiefe
 * {@value #MAX_TIEFE}). Ein Anhang ist ein Teil mit Disposition {@code attachment} oder ein Teil mit Dateiname, der nicht
 * eingebettet ist. Eingebettet ist ein Teil mit Content-ID in {@code multipart/related} oder mit Disposition
 * {@code inline} (z. B. das Logo einer Signatur, ein Bild im HTML-Text); er gehört zum Text der Nachricht und ist kein
 * Anhang (Spec 02, Req 1.2, 1.3). Ein Teil ohne Dateiname und ohne {@code attachment} ist ebenfalls kein Anhang. Text ist
 * der erste {@code text/plain}-Teil, der kein Anhang ist, sonst der erste solche {@code text/html}-Teil ohne Markup.
 * Anhänge landen decodiert als Dateien im Verzeichnis der Nachricht. Ein Teil, der sich nicht lesen lässt, fällt weg;
 * die {@code .eml} bleibt vollständig.
 */
final class MailTeile {

    static final int MAX_TIEFE = 5;
    static final int MAX_TEXT = 1_000_000;

    private static final Logger LOG = Logger.getLogger(MailTeile.class);
    private static final Pattern SKRIPT = Pattern.compile("(?is)<(script|style|head)\\b.*?</\\1\\s*>");
    private static final Pattern UMBRUCH = Pattern.compile("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    private final Path verzeichnis;
    private final List<Anhang> anhaenge = new ArrayList<>();
    private String text;
    private String html;

    private MailTeile(Path verzeichnis) {
        this.verzeichnis = verzeichnis;
    }

    static MailTeile lesen(MimeMessage nachricht, Path verzeichnis) {
        MailTeile teile = new MailTeile(verzeichnis);
        teile.teil(nachricht, 0, false);
        return teile;
    }

    List<Anhang> anhaenge() {
        return List.copyOf(anhaenge);
    }

    String text() {
        String t = text != null ? text : html != null ? ohneMarkup(html) : "";
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    /** Die Adresse aus {@code From}, nur wenn es genau eine gültige gibt. */
    static Optional<String> absender(MimeMessage nachricht) {
        try {
            Address[] von = nachricht.getFrom();
            if (von != null && von.length == 1 && von[0] instanceof InternetAddress adresse
                    && adresse.getAddress() != null) {
                return Optional.of(adresse.getAddress());
            }
        } catch (MessagingException e) {
            LOG.debugf("Absender nicht lesbar (%s)", e.getClass().getName());
        }
        return Optional.empty();
    }

    static String betreff(MimeMessage nachricht) {
        try {
            return nachricht.getSubject();
        } catch (MessagingException e) {
            return null;
        }
    }

    private void teil(Part teil, int tiefe, boolean inRelated) {
        try {
            if (teil.isMimeType("multipart/*")) {
                Multipart teile = (Multipart) teil.getContent();
                boolean related = teil.isMimeType("multipart/related");
                for (int i = 0; i < teile.getCount(); i++) {
                    teil(teile.getBodyPart(i), tiefe, related);
                }
                return;
            }
            if (teil.isMimeType("message/rfc822") && tiefe < MAX_TIEFE && teil.getContent() instanceof Part innen) {
                teil(innen, tiefe + 1, false);
                return;
            }
            Optional<String> name = name(teil);
            if (!anhang(teil, name, inRelated)) {
                if (teil.isMimeType("text/plain") && text == null) {
                    text = inhalt(teil);
                } else if (teil.isMimeType("text/html") && html == null) {
                    html = inhalt(teil);
                }
            } else {
                Path datei = verzeichnis.resolve("anhang-" + (anhaenge.size() + 1));
                try (InputStream ein = teil.getInputStream()) {
                    Files.copy(ein, datei);
                }
                anhaenge.add(new Anhang(datei, name));
            }
        } catch (MessagingException | IOException | RuntimeException e) {
            LOG.infof("Mail-Eingang: ein Teil der Nachricht ist nicht lesbar und fällt weg, ursache=%s",
                    Ursachen.kette(e));
        }
    }

    /** Anhang oder Teil des Textes; Regel siehe Klassenkommentar. */
    static boolean anhang(Part teil, Optional<String> name, boolean inRelated) throws MessagingException {
        String disposition = teil.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return true;
        }
        boolean eingebettet = teil instanceof MimePart mime && mime.getContentID() != null
                && (inRelated || Part.INLINE.equalsIgnoreCase(disposition));
        return name.isPresent() && !eingebettet;
    }

    private static Optional<String> name(Part teil) throws MessagingException {
        String name = teil.getFileName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MimeUtility.decodeText(name));
        } catch (UnsupportedEncodingException e) {
            return Optional.of(name);
        }
    }

    private static String inhalt(Part teil) throws MessagingException, IOException {
        try {
            Object inhalt = teil.getContent();
            if (inhalt instanceof String s) {
                return s;
            }
        } catch (UnsupportedEncodingException e) {
            // unbekannter Zeichensatz: unten als UTF-8 lesen
        }
        try (InputStream ein = teil.getInputStream()) {
            return new String(ein.readNBytes(MAX_TEXT), StandardCharsets.UTF_8);
        }
    }

    static String ohneMarkup(String html) {
        String t = SKRIPT.matcher(html).replaceAll(" ");
        t = UMBRUCH.matcher(t).replaceAll("\n");
        t = TAG.matcher(t).replaceAll("");
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&").replaceAll("[ \\t]+", " ").replaceAll(" ?\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
