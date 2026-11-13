package ch.urinet.archiv.ki.adapter.out.bild;

import ch.urinet.archiv.ki.application.SeiteUngeeignet;
import ch.urinet.archiv.ki.application.SeitenbildPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Seitenbild für den Bildweg (Req 7.1, design.md «KI»), wie im Golden-Lauf ({@code Seitenbild}): alle Seiten
 * untereinander in einem Bild, lange Kante höchstens {@value #LANGE_KANTE} px, weisser Grund, bikubisch, JPEG mit
 * Qualität 0.85. Die Abmessungen kommen zuerst aus den Köpfen der Dateien, danach wird Seite für Seite gezeichnet,
 * damit höchstens eine Seite in voller Auflösung im Speicher liegt (Speicherbudget Stufe A).
 * <p>
 * Seiten kommen als PPM ({@code pdftoppm} ohne {@code -png}, Review PR #93): {@link PpmLeser} liest sie mit denselben
 * Pixeln in denselben Bildtyp ({@code TYPE_3BYTE_BGR}) wie ImageIO das PNG des Golden-Laufs, darum entsteht dasselbe
 * JPEG ({@code SeitenbildGleichheitTest}). Andere Formate (PNG) liest weiter ImageIO. Ist ein Kopf nicht lesbar oder
 * hat eine Seite mehr als {@code archiv.ki.render-pixel-max} Pixel, wirft sie {@link SeiteUngeeignet}, bevor ein
 * Raster angelegt wird (Review PR #120).
 */
@ApplicationScoped
public class AwtSeitenbild implements SeitenbildPort {

    static final int LANGE_KANTE = 2000;

    private final long pixelMax;

    @Inject
    public AwtSeitenbild(@ConfigProperty(name = "archiv.ki.render-pixel-max") long pixelMax) {
        this.pixelMax = pixelMax;
    }

    @Override
    public byte[] jpeg(List<Path> seiten) throws IOException {
        if (seiten.isEmpty()) {
            throw new IOException("keine Seiten");
        }
        List<int[]> masse = new ArrayList<>();
        for (Path seite : seiten) {
            masse.add(abmessungen(seite));
        }
        int breite = masse.stream().mapToInt(m -> m[0]).max().orElseThrow();
        long hoehe = masse.stream().mapToLong(m -> m[1]).sum();
        double faktor = Math.min(1.0, (double) LANGE_KANTE / Math.max(breite, hoehe));
        int b = Math.max(1, (int) (breite * faktor));
        int h = Math.max(1, (int) (hoehe * faktor));
        BufferedImage ziel = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = ziel.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, b, h);
            int y = 0;
            for (int i = 0; i < seiten.size(); i++) {
                BufferedImage bild = lesen(seiten.get(i));
                if (bild == null) {
                    throw new SeiteUngeeignet("Seite nicht lesbar");
                }
                int bh = (int) (masse.get(i)[1] * faktor);
                g.drawImage(bild, 0, y, (int) (masse.get(i)[0] * faktor), bh, null);
                y += bh;
            }
        } finally {
            g.dispose();
        }
        return alsJpeg(ziel);
    }

    private BufferedImage lesen(Path seite) throws IOException {
        return PpmLeser.istPpm(seite) ? PpmLeser.lesen(seite, pixelMax) : ImageIO.read(seite.toFile());
    }

    private int[] abmessungen(Path seite) throws IOException {
        if (PpmLeser.istPpm(seite)) {
            PpmLeser.Kopf kopf = PpmLeser.kopf(seite, pixelMax);
            return new int[]{kopf.breite(), kopf.hoehe()};
        }
        try (ImageInputStream ein = ImageIO.createImageInputStream(seite.toFile())) {
            Iterator<ImageReader> leser = ein == null ? null : ImageIO.getImageReaders(ein);
            if (leser == null || !leser.hasNext()) {
                throw new SeiteUngeeignet("Seite nicht lesbar");
            }
            ImageReader reader = leser.next();
            try {
                reader.setInput(ein, true, true);
                int breite = reader.getWidth(0);
                int hoehe = reader.getHeight(0);
                if ((long) breite * hoehe > pixelMax) {
                    throw new SeiteUngeeignet("Seite mit " + (long) breite * hoehe + " Pixeln über der Grenze");
                }
                return new int[]{breite, hoehe};
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] alsJpeg(BufferedImage bild) throws IOException {
        ByteArrayOutputStream puffer = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        try (ImageOutputStream aus = ImageIO.createImageOutputStream(puffer)) {
            writer.setOutput(aus);
            writer.write(null, new IIOImage(bild, null, null), param);
        } finally {
            writer.dispose();
        }
        return puffer.toByteArray();
    }
}
