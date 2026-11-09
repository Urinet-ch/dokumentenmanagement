package ch.urinet.archiv.spike.golden;

import dev.langchain4j.data.image.Image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Variante K1: gerenderte Seiten (300 dpi) untereinander, lange Kante höchstens 2000 px, als JPEG. */
final class Seitenbild {

    static final int LANGE_KANTE = 2000;

    private Seitenbild() {
    }

    static Image aus(List<Path> seiten) throws IOException {
        List<BufferedImage> bilder = new ArrayList<>();
        for (Path p : seiten) {
            bilder.add(ImageIO.read(p.toFile()));
        }
        int breite = bilder.stream().mapToInt(BufferedImage::getWidth).max().orElseThrow();
        int hoehe = bilder.stream().mapToInt(BufferedImage::getHeight).sum();
        double faktor = Math.min(1.0, (double) LANGE_KANTE / Math.max(breite, hoehe));
        int b = (int) (breite * faktor), h = (int) (hoehe * faktor);
        BufferedImage ziel = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = ziel.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, b, h);
        int y = 0;
        for (BufferedImage bild : bilder) {
            int bh = (int) (bild.getHeight() * faktor);
            g.drawImage(bild, 0, y, (int) (bild.getWidth() * faktor), bh, null);
            y += bh;
        }
        g.dispose();
        ByteArrayOutputStream puffer = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(puffer)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(ziel, null, null), param);
        } finally {
            writer.dispose();
        }
        return Image.builder().base64Data(Base64.getEncoder().encodeToString(puffer.toByteArray()))
                .mimeType("image/jpeg").build();
    }
}
