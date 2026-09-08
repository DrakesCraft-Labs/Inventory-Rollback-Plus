package com.nuclyon.technicallycoded.inventoryrollback.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresion del ticket #373: el future que dispara el acuse de {@code /irp forcebackup} tiene que
 * describir la escritura de la copia, no el intento.
 */
class BackupPersistenceTest {

    @Test
    @DisplayName("con purga y escritura correctas el future completa")
    void completaCuandoTodoVaBien() {
        AtomicBoolean purgaFallida = new AtomicBoolean(false);

        CompletableFuture<Void> resultado = BackupPersistence.afterPurge(
                CompletableFuture.completedFuture(null),
                () -> CompletableFuture.completedFuture(null),
                error -> purgaFallida.set(true));

        assertTrue(resultado.isDone());
        assertFalse(resultado.isCompletedExceptionally());
        assertFalse(purgaFallida.get());
    }

    @Test
    @DisplayName("un fallo de escritura falla el future: sin acuse")
    void propagaElFalloDeEscritura() {
        IOException fallo = new IOException("disco lleno");

        CompletableFuture<Void> resultado = BackupPersistence.afterPurge(
                CompletableFuture.completedFuture(null),
                () -> {
                    CompletableFuture<Void> fallido = new CompletableFuture<>();
                    fallido.completeExceptionally(fallo);
                    return fallido;
                },
                error -> { });

        assertTrue(resultado.isCompletedExceptionally());
        CompletionException lanzada = assertThrows(CompletionException.class, resultado::join);
        assertSame(fallo, lanzada.getCause());
    }

    @Test
    @DisplayName("un fallo de purga se registra pero la copia nueva se escribe igual")
    void elFalloDePurgaNoCancelaLaEscritura() {
        RuntimeException fallo = new RuntimeException("no se pudo borrar la copia vieja");
        CompletableFuture<Void> purga = new CompletableFuture<>();
        purga.completeExceptionally(fallo);
        AtomicBoolean escrito = new AtomicBoolean(false);
        AtomicReference<Throwable> registrado = new AtomicReference<>();

        CompletableFuture<Void> resultado = BackupPersistence.afterPurge(
                purga,
                () -> {
                    escrito.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                registrado::set);

        assertTrue(escrito.get(), "la escritura debe ejecutarse pese al fallo de purga");
        assertFalse(resultado.isCompletedExceptionally());
        assertSame(fallo, registrado.get(), "el fallo de purga llega desenvuelto al registro");
    }

    @Test
    @DisplayName("un scheduler que rechaza la tarea falla el future en vez de dejarlo colgado")
    void cierraElFutureSiElSchedulerRechaza() {
        IllegalStateException rechazo = new IllegalStateException("plugin deshabilitado");
        CompletableFuture<Void> future = new CompletableFuture<>();

        BackupPersistence.submitOrFail(() -> { throw rechazo; }, future);

        assertTrue(future.isDone(), "el future no puede quedar pendiente: el llamador espera 180 s");
        assertTrue(future.isCompletedExceptionally());
        assertSame(rechazo, assertThrows(CompletionException.class, future::join).getCause());
    }

    @Test
    @DisplayName("un envio aceptado deja el future en manos de la tarea")
    void noTocaElFutureSiElEnvioTieneExito() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicBoolean enviado = new AtomicBoolean(false);

        BackupPersistence.submitOrFail(() -> enviado.set(true), future);

        assertTrue(enviado.get());
        assertFalse(future.isDone());
    }

    @Test
    @DisplayName("el fallo de purga envuelto en CompletionException llega desenvuelto")
    void desenvuelveLaCompletionException() {
        IOException raiz = new IOException("io");
        CompletableFuture<Void> purga = new CompletableFuture<>();
        purga.completeExceptionally(new CompletionException(raiz));
        AtomicReference<Throwable> registrado = new AtomicReference<>();

        BackupPersistence.afterPurge(purga, () -> CompletableFuture.completedFuture(null), registrado::set);

        assertNotNull(registrado.get());
        assertSame(raiz, registrado.get());
        assertEquals("io", registrado.get().getMessage());
    }
}
