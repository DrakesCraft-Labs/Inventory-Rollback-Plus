package com.nuclyon.technicallycoded.inventoryrollback.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Agrupa las escrituras de un force backup y decide cuando puede emitirse el acuse.
 *
 * <p>Ticket #373: el acuse de {@code /irp forcebackup} se emitia justo despues de encolar las
 * tareas asincronas de guardado, no cuando el YAML estaba en disco. Quien lee ese acuse como
 * barrera -- la sincronizacion previa al reinicio de {@code reinicio_seguro.py}, que espera la
 * linea "force saved" en la consola -- creia tener una garantia que no existia. Esta clase
 * concentra la regla en un punto sin dependencias de Bukkit para poder probarla.
 */
public final class ForceBackupAcknowledgement {

    private ForceBackupAcknowledgement() {
    }

    /**
     * Ejecuta {@code onSuccess} cuando TODAS las copias esten escritas, o {@code onFailure} si
     * alguna falla. Con una lista vacia el acuse es inmediato: no habia nada que escribir.
     *
     * @return el future agregado, util para pruebas y para encadenar
     */
    public static CompletableFuture<Void> onAllPersisted(List<CompletableFuture<Void>> pending,
                                                         Runnable onSuccess,
                                                         Consumer<Throwable> onFailure) {
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> {
                    if (error != null) onFailure.accept(error);
                    else onSuccess.run();
                });
    }

}
