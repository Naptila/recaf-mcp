package me.naptila.recafmcp.mcp.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipFile;

/**
 * Installs a generated ZIP/JAR without exposing a partial destination file.
 */
public final class AtomicFileInstaller {
    private AtomicFileInstaller() {}

    /**
     * Export to a temporary sibling, validate it, and atomically install it.
     *
     * @param originalInput
     *        Original workspace input, which must never be replaced.
     * @param destination
     *        Requested output path.
     * @param overwrite
     *        Whether an existing non-input destination may be replaced.
     * @param writer
     *        Operation which writes the complete export to the supplied path.
     *
     * @return Installed output metadata.
     *
     * @throws IOException
     *         When path validation, export, ZIP validation, or installation fails.
     */
    public static Result install(Path originalInput,
                                 Path destination,
                                 boolean overwrite,
                                 PathWriter writer) throws IOException {
        return install(originalInput, destination, overwrite, writer, AtomicFileInstaller::validateZip);
    }

    public static Result install(Path originalInput,
                                 Path destination,
                                 boolean overwrite,
                                 PathWriter writer,
                                 PathValidator validator) throws IOException {
        Path input = originalInput.toRealPath();
        Path output = destination.toAbsolutePath().normalize();
        Path fileName = output.getFileName();
        Path parent = output.getParent();
        if (fileName == null || parent == null) {
            throw new IOException("destination must name a file");
        }

        Path realParent = parent.toRealPath();
        if (!Files.isDirectory(realParent) || !Files.isWritable(realParent)) {
            throw new IOException("destination parent is not a writable directory");
        }

        boolean destinationExists = Files.exists(output, LinkOption.NOFOLLOW_LINKS);
        Path comparableOutput = destinationExists
                ? output.toRealPath()
                : realParent.resolve(fileName).normalize();
        if (input.equals(comparableOutput)) {
            throw new IOException("destination resolves to the original input");
        }
        if (destinationExists && !overwrite) {
            throw new FileAlreadyExistsException(output.toString());
        }

        Path temporary = Files.createTempFile(realParent, "." + fileName + ".", ".tmp");
        boolean installed = false;
        try {
            writer.write(temporary);
            int entryCount = validator.validate(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            if (overwrite) {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            }
            installed = true;
            return new Result(output.toRealPath(), Files.size(output), entryCount);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("destination filesystem does not support atomic moves", ex);
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static int validateZip(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path) == 0) {
            throw new IOException("export is not a non-empty readable file");
        }

        try (ZipFile zip = new ZipFile(path.toFile())) {
            int entryCount = zip.size();
            if (entryCount == 0) {
                throw new IOException("export ZIP has no entries");
            }
            return entryCount;
        }
    }

    /**
     * IO operation which writes an export to a path.
     */
    @FunctionalInterface
    public interface PathWriter {
        void write(Path path) throws IOException;
    }

    @FunctionalInterface
    public interface PathValidator {
        int validate(Path path) throws IOException;
    }

    /**
     * Metadata for an installed export.
     */
    public record Result(Path path, long sizeBytes, int zipEntries) {}
}
