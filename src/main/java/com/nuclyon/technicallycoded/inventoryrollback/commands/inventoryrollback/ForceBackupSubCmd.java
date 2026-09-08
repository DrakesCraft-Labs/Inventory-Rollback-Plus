package com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import com.nuclyon.technicallycoded.inventoryrollback.commands.IRPCommand;
import com.nuclyon.technicallycoded.inventoryrollback.util.ForceBackupAcknowledgement;
import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ForceBackupSubCmd extends IRPCommand {

    public ForceBackupSubCmd(InventoryRollbackPlus mainIn) {
        super(mainIn);
    }

    @Override
    public void onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender.hasPermission("inventoryrollbackplus.forcebackup")) {
            if (args.length == 1 || args.length > 3) {
                sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
                return;
            }

            if (args[1].equalsIgnoreCase("all")) {
                forceBackupAll(sender);
            } else if (args[1].equalsIgnoreCase("player")) {
                forceBackupPlayer(sender, args);
            } else {
                sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
            }
        } else {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNoPermission());
        }
    }

    private void forceBackupAll(CommandSender sender) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            pending.add(new SaveInventory(player, LogType.FORCE, null, null)
                    .snapshotAndSave(player.getInventory(), player.getEnderChest(), true));
        }

        acusarCuandoEsteEnDisco(sender, pending, MessageData.getForceBackupAll());
    }

    private void forceBackupPlayer(CommandSender sender, String[] args) {
        if (args.length == 2) {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getPlayer(args[2]);

        if (offlinePlayer == null) {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNotOnlineError(args[2]));
            return;
        }

        if (!offlinePlayer.isOnline()) {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNotOnlineError(offlinePlayer.getName()));
            return;
        }

        Player player = (Player) offlinePlayer;
        CompletableFuture<Void> pending = new SaveInventory(player, LogType.FORCE, null, null)
                .snapshotAndSave(player.getInventory(), player.getEnderChest(), true);

        acusarCuandoEsteEnDisco(sender, java.util.Collections.singletonList(pending),
                MessageData.getForceBackupPlayer(offlinePlayer.getName()));
    }

    /**
     * Emite el acuse solo cuando todas las copias estan escritas (ticket #373).
     *
     * <p>Antes el mensaje salia justo despues de encolar las tareas asincronas, asi que quien lo
     * leia -- notablemente la sincronizacion previa al reinicio de {@code reinicio_seguro.py}, que
     * espera la linea "force saved" en la consola -- creia tener una barrera cuando solo tenia un
     * acuse de encolado. Ahora la linea aparece cuando el YAML esta en disco; si alguna escritura
     * falla no se emite el acuse, de modo que el llamador falla cerrado en vez de reiniciar sobre
     * una copia inexistente.
     *
     * <p>QA descarto la bandera {@code --sync} que acompanaba a este cambio: escribia en el hilo
     * llamante, que para la consola es el {@code Server thread}, asi que como barrera de preapagado
     * habria bloqueado el tick en vez de protegerlo. El acuse asincrono posterior a la persistencia
     * da la misma garantia sin tocar el hilo principal.
     */
    private void acusarCuandoEsteEnDisco(CommandSender sender, List<CompletableFuture<Void>> pending, String mensaje) {
        ForceBackupAcknowledgement.onAllPersisted(pending,
                () -> enviar(sender, MessageData.getPluginPrefix() + mensaje),
                error -> {
                    main.getLogger().warning("Force backup failed, no acknowledgement sent: " + error.getMessage());
                    enviar(sender, MessageData.getPluginPrefix() + MessageData.getError());
                });
    }

    /** Devuelve el mensaje al hilo principal cuando la escritura termino en un hilo asincrono. */
    private void enviar(CommandSender sender, String mensaje) {
        if (Bukkit.isPrimaryThread() || !main.isEnabled()) {
            sender.sendMessage(mensaje);
            return;
        }

        Bukkit.getScheduler().runTask(main, () -> sender.sendMessage(mensaje));
    }

}
