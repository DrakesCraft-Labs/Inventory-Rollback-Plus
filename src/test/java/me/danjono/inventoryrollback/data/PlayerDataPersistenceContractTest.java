package me.danjono.inventoryrollback.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato del ticket #373 en las clases de almacenamiento.
 *
 * <p>{@code YAML} y {@code PlayerData} solo son instanciables con un servidor detras -- la carpeta
 * de respaldo sale de la instancia del plugin --, asi que aqui se fija lo que si puede comprobarse
 * sin arrancarlo: que existe una variante de escritura que declara el fallo de E/S en vez de
 * tragarselo, que la variante muda se conserva para los llamadores antiguos, y que las operaciones
 * que alimentan el acuse devuelven un future. El comportamiento de esos futures se prueba en
 * {@code BackupPersistenceTest}.
 */
class PlayerDataPersistenceContractTest {

    @Test
    @DisplayName("YAML expone una escritura que declara IOException")
    void laEscrituraVerificadaPropagaElFalloDeEs() throws NoSuchMethodException {
        Method verificada = YAML.class.getMethod("saveDataChecked");

        assertTrue(Arrays.asList(verificada.getExceptionTypes()).contains(IOException.class),
                "sin IOException declarada, el future volveria a completarse como exito sin fichero");
        assertEquals(void.class, verificada.getReturnType());
    }

    @Test
    @DisplayName("YAML conserva la escritura muda para los llamadores antiguos")
    void laEscrituraMudaSigueExistiendo() throws NoSuchMethodException {
        Method muda = YAML.class.getMethod("saveData");

        assertEquals(0, muda.getExceptionTypes().length);
        assertEquals(void.class, muda.getReturnType());
    }

    @Test
    @DisplayName("MySQL sigue declarando SQLException en su escritura")
    void mysqlDeclaraSuFallo() throws NoSuchMethodException {
        Method escritura = MySQL.class.getMethod("saveData");

        assertTrue(Arrays.asList(escritura.getExceptionTypes()).contains(SQLException.class));
    }

    @Test
    @DisplayName("guardado y purga devuelven un future esperable")
    void lasOperacionesDevuelvenFuture() throws NoSuchMethodException {
        assertEquals(CompletableFuture.class,
                PlayerData.class.getMethod("saveData", boolean.class).getReturnType());
        assertEquals(CompletableFuture.class,
                PlayerData.class.getMethod("purgeExcessSaves", boolean.class).getReturnType());
    }
}
