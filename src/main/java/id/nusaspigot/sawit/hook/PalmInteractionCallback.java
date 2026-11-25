package id.nusaspigot.sawit.hook;

import id.nusaspigot.sawit.model.PalmBreakOutcome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Provider-agnostic callback used by both NexoBridge and ItemsAdderBridge
 * to forward palm right-click and break interactions to PalmFarmManager.
 */
public interface PalmInteractionCallback {

    boolean onPalmRightClick(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand);

    PalmBreakOutcome onPalmBreak(Player player, Block block);
}
