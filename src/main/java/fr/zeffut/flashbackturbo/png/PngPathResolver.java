package fr.zeffut.flashbackturbo.png;

import com.moulberry.flashback.exporting.ExportSettings;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reproduit la logique de résolution de chemin de {@code PNGSequenceVideoWriter}
 * (lignes ~78-109 du source vanilla) pour pouvoir générer le même nom de fichier
 * sans devoir passer par le thread vanilla.
 *
 * <p>Mantenir aligné avec Flashback en cas de mise à jour du format de séquence.
 */
public final class PngPathResolver {

    private final ExportSettings settings;
    private final boolean outputIsDirectory;
    private final boolean encodeMultiple;
    private final String format;

    public PngPathResolver(ExportSettings settings) {
        this.settings = settings;
        this.outputIsDirectory = Files.isDirectory(settings.output());
        this.encodeMultiple = outputIsDirectory || settings.startTick() != settings.endTick();
        String f = settings.pngSequenceFormat();
        this.format = f != null ? f : "%04d";
    }

    public Path resolve(int sequenceNumber) {
        Path output = settings.output();
        if (!encodeMultiple) {
            return output;
        }

        String filenamePart;
        try {
            filenamePart = String.format(format, sequenceNumber);
        } catch (Exception e) {
            filenamePart = String.format("%04d", sequenceNumber);
        }
        if (!filenamePart.endsWith(".png")) {
            filenamePart += ".png";
        }

        if (outputIsDirectory) {
            return output.resolve(filenamePart);
        } else {
            String fname = output.getFileName().toString() + "-" + filenamePart;
            return output.getParent().resolve(fname);
        }
    }
}
