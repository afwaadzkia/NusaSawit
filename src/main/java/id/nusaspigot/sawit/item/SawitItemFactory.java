package id.nusaspigot.sawit.item;

import id.nusaspigot.sawit.hook.CustomPackHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and identifies NusaSawit custom items.
 * Prefers Nexo or ItemsAdder custom items when active, while stamping a NusaSawit
 * PersistentDataContainer tag so item checks never break across reloads or providers.
 */
public final class SawitItemFactory {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final NamespacedKey itemTypeKey;
    private final CustomPackHook packHook;

    public SawitItemFactory(Plugin plugin, CustomPackHook packHook) {
        this.itemTypeKey = new NamespacedKey(plugin, "item_type");
        this.packHook = packHook;
    }

    public ItemStack create(SawitItemType type, int amount) {
        int safeAmount = Math.max(1, amount);
        ItemStack stack = packHook.buildCustomItem(type.nexoId());
        if (stack == null) {
            stack = new ItemStack(type.fallbackMaterial(), Math.min(64, safeAmount));
            ItemMeta fallbackMeta = stack.getItemMeta();
            if (fallbackMeta != null) {
                fallbackMeta.displayName(noItalic(MM.deserialize(type.displayMiniMessage())));
                List<Component> lore = new ArrayList<>(type.loreMiniMessage().size());
                for (String line : type.loreMiniMessage()) {
                    lore.add(noItalic(MM.deserialize(line)));
                }
                fallbackMeta.lore(lore);
                if (type.isUnstackableTool()) {
                    fallbackMeta.setMaxStackSize(1);
                }
                stack.setItemMeta(fallbackMeta);
            }
        }
        stack.setAmount(type.isUnstackableTool() ? 1 : Math.min(64, safeAmount));

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, type.nexoId());
            if (type.isUnstackableTool()) {
                meta.setMaxStackSize(1);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public SawitItemType identify(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String tagged = meta.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
            if (tagged != null) {
                SawitItemType byTag = SawitItemType.fromId(tagged);
                if (byTag != null) {
                    return byTag;
                }
            }
        }
        String customId = packHook.idFromItem(stack);
        if (customId != null) {
            return SawitItemType.fromId(customId);
        }
        return null;
    }

    public void giveOrDrop(Player player, SawitItemType type, int totalAmount) {
        int remaining = Math.max(0, totalAmount);
        while (remaining > 0) {
            int batch = type.isUnstackableTool() ? 1 : Math.min(64, remaining);
            ItemStack item = create(type, batch);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            remaining -= batch;
        }
    }

    public int countInInventory(Player player, SawitItemType targetType) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (identify(stack) == targetType) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public int removeFromInventory(Player player, SawitItemType targetType, int toRemove) {
        int remaining = toRemove;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (identify(stack) != targetType) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            int newAmt = stack.getAmount() - take;
            if (newAmt <= 0) {
                contents[i] = null;
            } else {
                stack.setAmount(newAmt);
            }
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
        return toRemove - remaining;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
