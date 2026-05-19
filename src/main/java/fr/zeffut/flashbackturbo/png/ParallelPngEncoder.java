package fr.zeffut.flashbackturbo.png;

import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.minecraft.client.texture.NativeImage;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encoder PNG parallèle. Remplace le single-thread de {@code PNGSequenceVideoWriter}
 * par un pool de {@code N-1} threads écrivant les frames en parallèle.
 *
 * <p>L'API mime celle du writer vanilla : {@link #submit}, {@link #finish}, {@link #close}.
 *
 * <p>Borne le nombre de frames en vol via un {@link Semaphore} pour éviter d'exploser
 * la mémoire si l'écriture est plus lente que la production (back-pressure).
 */
public final class ParallelPngEncoder implements AutoCloseable {

    private final ExecutorService pool;
    private final Semaphore inFlightLimit;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();
    private volatile boolean closed = false;

    private final boolean keepAlpha;

    public ParallelPngEncoder(boolean keepAlpha) {
        this.keepAlpha = keepAlpha;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int maxInFlight = Math.max(threads * 2, 8);
        this.inFlightLimit = new Semaphore(maxInFlight);

        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "FlashbackTurbo-PNG-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = Executors.newFixedThreadPool(threads, tf);

        FlashbackTurboClient.LOGGER.info("[H2] ParallelPngEncoder démarré avec {} threads (max {} frames in-flight)", threads, maxInFlight);
    }

    /** Soumet une frame pour encodage asynchrone. Bloque si la file est saturée (back-pressure). */
    public void submit(NativeImage image, Path output) {
        if (closed) {
            image.close();
            throw new IllegalStateException("ParallelPngEncoder déjà fermé");
        }
        checkError();
        acquireSlot();

        pool.execute(() -> {
            try {
                FastPngWriter.write(image, output, TurboConfig.current().pngCompressionLevel, keepAlpha);
            } catch (Throwable t) {
                firstError.compareAndSet(null, t);
                FlashbackTurboClient.LOGGER.error("[H2] échec encodage PNG vers {}", output, t);
            } finally {
                image.close();
                releaseSlot();
            }
        });
    }

    /**
     * Variante testable sans dépendance Minecraft : prend directement un tableau ARGB.
     * Le tableau est consommé par le pool, l'appelant n'a pas à le conserver.
     */
    public void submitArgb(int[] argb, int width, int height, Path output) {
        if (closed) {
            throw new IllegalStateException("ParallelPngEncoder déjà fermé");
        }
        checkError();
        acquireSlot();

        pool.execute(() -> {
            try {
                FastPngWriter.writeArgb(argb, width, height, output, TurboConfig.current().pngCompressionLevel, keepAlpha);
            } catch (Throwable t) {
                firstError.compareAndSet(null, t);
                FlashbackTurboClient.LOGGER.error("[H2] échec encodage PNG vers {}", output, t);
            } finally {
                releaseSlot();
            }
        });
    }

    private void acquireSlot() {
        try {
            inFlightLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        inFlight.incrementAndGet();
    }

    private void releaseSlot() {
        inFlight.decrementAndGet();
        inFlightLimit.release();
    }

    /** Bloque jusqu'à ce que toutes les frames soumises soient écrites. */
    public void finish() {
        while (inFlight.get() > 0) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        checkError();
    }

    @Override
    public void close() {
        closed = true;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void checkError() {
        Throwable t = firstError.get();
        if (t != null) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }
}
