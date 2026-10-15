package dev.townhall.shop.sign;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Simulated copy of a container; nothing changes until commit(), so a failed purchase touches nothing. */
final class SlotSim {
    private final Container container;
    private final ItemStack[] slots;
    SlotSim(Container container) {
        this.container = container;
        slots = new ItemStack[container.getContainerSize()];
        for (int i = 0; i < slots.length; i++) slots[i] = container.getItem(i).copy();
    }
    long count(ItemStack template) {
        long count = 0;
        for (ItemStack item : slots) if (ItemStack.isSameItemSameComponents(item, template)) count += item.getCount();
        return count;
    }
    boolean take(ItemStack template, long count) {
        if (count(template) < count) return false;
        for (int i = 0; i < slots.length && count > 0; i++) {
            if (!ItemStack.isSameItemSameComponents(slots[i], template)) continue;
            int take = (int) Math.min(count, slots[i].getCount());
            slots[i].shrink(take);
            count -= take;
            if (slots[i].isEmpty()) slots[i] = ItemStack.EMPTY;
        }
        return true;
    }
    boolean give(ItemStack template, long count) {
        int max = Math.min(64, template.getMaxStackSize());
        for (ItemStack stack : slots) {
            if (count == 0) return true;
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                int add = (int) Math.min(count, Math.max(0, max - stack.getCount()));
                stack.grow(add);
                count -= add;
            }
        }
        for (int i = 0; i < slots.length && count > 0; i++) if (slots[i].isEmpty()) {
            int add = (int) Math.min(count, max);
            slots[i] = template.copyWithCount(add);
            count -= add;
        }
        return count == 0;
    }
    ItemStack firstNonEmpty() {
        for (ItemStack s : slots) if (!s.isEmpty()) return s;
        return ItemStack.EMPTY;
    }
    void commit() {
        for (int i = 0; i < slots.length; i++) container.setItem(i, slots[i]);
        container.setChanged();
    }
}
