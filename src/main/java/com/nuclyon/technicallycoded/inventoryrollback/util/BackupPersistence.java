package com.nuclyon.technicallycoded.inventoryrollback.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reglas de composicion entre la purga de copias sobrantes y la escritura de la copia nueva.
 *
 * <p>Ticket #373: el acuse de {@code /irp forcebackup} solo debe salir cuando la copia esta en el
 * almacenamiento, asi que el future que lo dispara tiene que reflejar el resultado de la escritura
 * y nada mas. La purga se ejecuta antes y borra respaldos viejos: si falla, la copia nueva sigue
 * siendo valida y cancelar la cadena perderia justo el respaldo que se pidio. Aqui vive esa
 * distincion, fuera de las clases que dependen del servidor, para poder probarla.
 */
public final class BackupPersistence {

    private BackupPersistence() {
    }

    /**
     * Encadena la escritura tras la purga degradando el fallo de esta ultima a aviso.
     *
     * @param purge          future de la purga previa
     * @param save           escritura de la copia nueva; se invoca siempre, haya fallado o no la purga
     * @param onPurgeFailure receptor del fallo de purga, para registrarlo
     * @return future que completa con el resultado de la escritura
     */
    public static CompletableFuture<Void> afterPurge(CompletableFuture<Void> purge,
                                                     Supplier<CompletableFuture<Void>> save,
                                                     Consumer<Throwable> onPurgeFailure) {
        return purge
                .handle((ignored, purgeError) -> {
                    if (purgeError != null) onPurgeFailure.accept(unwrap(purgeError));
                    return null;
                })
                .thenCompose(ignored -> save.get());
    }

    /**
     * Ejecuta el envio de una tarea al scheduler cerrando el future si el envio es rechazado.
     *
     * <p>Sin esto, un scheduler que rechaza la tarea -- lo normal cuando el plugin ya esta
     * deshabilitado durante el apagado -- dejaba el future pendiente para siempre, y quien lo
     * esperaba consumia su espera completa antes de rendirse en vez de recibir el fallo.
     */
    public static void submitOrFail(Runnable submit, CompletableFuture<Void> future) {
        try {
            submit.run();
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
