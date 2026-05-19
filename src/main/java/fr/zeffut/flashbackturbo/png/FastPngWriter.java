package fr.zeffut.flashbackturbo.png;

import net.minecraft.client.texture.NativeImage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * PNG writer minimaliste avec niveau zlib configurable.
 *
 * <p>Spécifications respectées :
 * <ul>
 *   <li>Color type 2 (RGB, 3 octets/pixel) si {@code keepAlpha=false}, 6 (RGBA, 4 octets) sinon</li>
 *   <li>Bit depth 8, filter type 0 (None) sur toutes les scanlines — encodage le plus rapide</li>
 *   <li>Compression zlib via {@link Deflater} avec le niveau passé en paramètre (1=BEST_SPEED, 9=BEST_COMPRESSION)</li>
 *   <li>CRC32 sur chaque chunk conforme spec PNG</li>
 * </ul>
 *
 * <p>L'output décodé est strictement bit-identique pour les pixels par rapport à un PNG L9
 * (zlib est lossless quel que soit le niveau, seule la taille de sortie change).
 *
 * <p>Si {@code keepAlpha=false} et que l'image source a un canal alpha, le canal est
 * supprimé à l'encodage (color type 2) — pas de boucle "force alpha=255" car PNG sans alpha
 * n'a tout simplement pas le canal. Résultat décodé identique à une image vue comme RGB.
 */
public final class FastPngWriter {

    // Signature PNG : 137 80 78 71 13 10 26 10
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private FastPngWriter() {}

    public static void write(NativeImage image, Path output, int zlibLevel, boolean keepAlpha) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean writeAlpha = keepAlpha && image.getFormat().hasAlpha();
        int[] argb = image.copyPixelsArgb();
        writeArgb(argb, width, height, output, zlibLevel, writeAlpha);
    }

    /**
     * Variante MC-free pour les tests unitaires.
     *
     * @param argb pixels au format ARGB (alpha 0xFF000000, R, G, B), scanline order
     */
    public static void writeArgb(int[] argb, int width, int height, Path output, int zlibLevel, boolean writeAlpha) throws IOException {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("argb.length=" + argb.length + " != width*height=" + (width * height));
        }
        try (var ch = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             var out = java.nio.channels.Channels.newOutputStream(ch)) {
            out.write(PNG_SIGNATURE);
            writeIhdr(out, width, height, writeAlpha);
            writeIdatFromArgb(out, argb, width, height, writeAlpha, zlibLevel);
            writeChunk(out, "IEND", new byte[0]);
        }
    }

    private static void writeIhdr(OutputStream out, int width, int height, boolean withAlpha) throws IOException {
        ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);                              // bit depth
        ihdr.put((byte) (withAlpha ? 6 : 2));            // color type: 6=RGBA, 2=RGB
        ihdr.put((byte) 0);                              // compression
        ihdr.put((byte) 0);                              // filter
        ihdr.put((byte) 0);                              // interlace
        writeChunk(out, "IHDR", ihdr.array());
    }

    private static void writeIdatFromArgb(OutputStream out, int[] allArgb, int width, int height, boolean withAlpha, int zlibLevel) throws IOException {
        int bytesPerPixel = withAlpha ? 4 : 3;
        int scanlineSize = 1 + width * bytesPerPixel;

        var raw = new java.io.ByteArrayOutputStream(scanlineSize * height);
        for (int y = 0; y < height; y++) {
            raw.write(0); // filter type None
            int rowStart = y * width;
            if (withAlpha) {
                for (int x = 0; x < width; x++) {
                    int argb = allArgb[rowStart + x];
                    raw.write((argb >>> 16) & 0xFF);
                    raw.write((argb >>> 8) & 0xFF);
                    raw.write(argb & 0xFF);
                    raw.write((argb >>> 24) & 0xFF);
                }
            } else {
                for (int x = 0; x < width; x++) {
                    int argb = allArgb[rowStart + x];
                    raw.write((argb >>> 16) & 0xFF);
                    raw.write((argb >>> 8) & 0xFF);
                    raw.write(argb & 0xFF);
                }
            }
        }

        var deflated = new java.io.ByteArrayOutputStream(raw.size() / 4);
        Deflater deflater = new Deflater(clampLevel(zlibLevel));
        try (DeflaterOutputStream dos = new DeflaterOutputStream(deflated, deflater, 64 * 1024)) {
            raw.writeTo(dos);
        } finally {
            deflater.end();
        }

        writeChunk(out, "IDAT", deflated.toByteArray());
    }

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // Length (4) + Type (4) + Data + CRC (4)
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        header.putInt(data.length);
        header.put(typeBytes);
        out.write(header.array());
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        crcBuf.putInt((int) crc.getValue());
        out.write(crcBuf.array());
    }

    private static int clampLevel(int level) {
        if (level < Deflater.BEST_SPEED) return Deflater.BEST_SPEED;
        if (level > Deflater.BEST_COMPRESSION) return Deflater.BEST_COMPRESSION;
        return level;
    }
}
