package dev.townhall.shop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Bounded simulation of the 36 carry slots. Armor, offhand, cursor and crafting slots are never taken. */
final class ShopInventory {
    private final ServerPlayer player;
    private final ItemStack[] slots = new ItemStack[Inventory.INVENTORY_SIZE];
    ShopInventory(ServerPlayer player) {
        this.player = player;
        for (int i = 0; i < slots.length; i++) slots[i] = player.getInventory().getItem(i).copy();
    }
    long count(ItemStack template) {
        long count = 0;
        for (ItemStack item : slots) if (ItemStack.isSameItemSameComponents(item, template)) count += item.getCount();
        return count;
    }
    boolean take(ItemStack template, long count) {
        if (count < 0 || count(template) < count) return false;
        for (int i = 0; i < slots.length && count > 0; i++) {
            ItemStack stack = slots[i];
            if (!ItemStack.isSameItemSameComponents(stack, template)) continue;
            int take = (int) Math.min(count, stack.getCount());
            stack.shrink(take);
            count -= take;
            if (stack.isEmpty()) slots[i] = ItemStack.EMPTY;
        }
        return true;
    }
    boolean give(ItemStack template, long count) {
        if (count < 0 || count > 0 && template.isEmpty()) return false;
        int maximum = Math.min(64, template.getMaxStackSize());
        // Fill matching stacks before empty slots, just like vanilla inventory insertion.
        for (ItemStack stack : slots) {
            if (count == 0) return true;
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                int add = (int) Math.min(count, Math.max(0, maximum - stack.getCount()));
                stack.grow(add);
                count -= add;
            }
        }
        for (int i = 0; i < slots.length && count > 0; i++) if (slots[i].isEmpty()) {
            int add = (int) Math.min(count, maximum);
            slots[i] = template.copyWithCount(add);
            count -= add;
        }
        return count == 0;
    }
    void commit() {
        for (int i = 0; i < slots.length; i++) player.getInventory().setItem(i, slots[i]);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}
