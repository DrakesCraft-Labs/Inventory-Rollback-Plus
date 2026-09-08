package com.nuclyon.technicallycoded.inventoryrollback.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regresion del ticket #373: el acuse de force backup no puede emitirse al encolar.
 */
public class ForceBackupAcknowledgementTest {

    @Test
    public void noAcusaMientrasQuedeAlgunaEscrituraPendiente() {
        CompletableFuture<Void> primera = new CompletableFuture<>();
        CompletableFuture<Void> segunda = new CompletableFuture<>();
        AtomicInteger acuses = new AtomicInteger();
        AtomicReference<Throwable> fallo = new AtomicReference<>();

        ForceBackupAcknowledgement.onAllPersisted(Arrays.asList(primera, segunda),
                acuses::incrementAndGet, fallo::set);

        assertEquals(0, acuses.get(), "no debe acusar con dos escrituras pendientes");

        primera.complete(null);
        assertEquals(0, acuses.get(), "no debe acusar con una escritura todavia pendiente");

        segunda.complete(null);
        assertEquals(1, acuses.get(), "debe acusar una sola vez al completarse la ultima");
        assertNull(fallo.get());
    }

    @Test
    public void noAcusaSiUnaEscrituraFalla() {
        CompletableFuture<Void> correcta = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> rota = new CompletableFuture<>();
        AtomicInteger acuses = new AtomicInteger();
        AtomicReference<Throwable> fallo = new AtomicReference<>();

        ForceBackupAcknowledgement.onAllPersisted(Arrays.asList(correcta, rota),
                acuses::incrementAndGet, fallo::set);

        rota.completeExceptionally(new IllegalStateException("disco lleno"));

        assertEquals(0, acuses.get(), "un fallo de escritura no puede confirmarse como copia hecha");
        assertNotNull(fallo.get(), "el fallo debe propagarse al llamador");
    }

    @Test
    public void acusaDeInmediatoSinEscriturasPendientes() {
        AtomicInteger acuses = new AtomicInteger();
        AtomicReference<Throwable> fallo = new AtomicReference<>();

        ForceBackupAcknowledgement.onAllPersisted(Collections.<CompletableFuture<Void>>emptyList(),
                acuses::incrementAndGet, fallo::set);

        assertEquals(1, acuses.get(), "sin jugadores conectados no hay nada que esperar");
        assertNull(fallo.get());
    }

    @Test
    public void acusaTrasEscriturasYaCompletadas() {
        List<CompletableFuture<Void>> hechas = new ArrayList<>();
        hechas.add(CompletableFuture.completedFuture(null));
        hechas.add(CompletableFuture.completedFuture(null));
        AtomicInteger acuses = new AtomicInteger();

        ForceBackupAcknowledgement.onAllPersisted(hechas, acuses::incrementAndGet, t -> {});

        assertEquals(1, acuses.get(), "el camino sincrono (--sync) acusa sin esperar");
    }

}
