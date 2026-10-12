package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 让女仆把背包里的物品存入附近（8 格内）的容器（箱子/木桶等）。
 * 用于收成后清空背包、把战利品归仓。
 */
public class StoreLootTool implements ITool<StoreLootTool.Result> {
    public static final String TOOL_ID = "store_loot";

    private static final int SEARCH_RADIUS = 8;

    private static final String TOOL_DESC = """
            Deposit items from your backpack into the nearest container (chest, barrel, etc.) within 8 blocks.
            Use this after harvesting or collecting loot, so your backpack stays empty for the next job.
            The tool moves every item it can; anything the container cannot accept stays in your backpack.""";

    private static final Codec<Result> CODEC = Codec.unit(new Result());

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!(callback.getMaid().level instanceof ServerLevel serverLevel)) {
            return callback.addToolResult("Can only store items on the server side.", toolId);
        }
        EntityMaid maid = callback.getMaid();

        BlockPos containerPos = findNearestContainer(serverLevel, maid);
        if (containerPos == null) {
            return callback.addToolResult(
                    "No container (chest/barrel) found within %d blocks. Build or find a chest first, or carry the items back to base."
                            .formatted(SEARCH_RADIUS), toolId);
        }
        IItemHandler chestInv = serverLevel.getCapability(Capabilities.ItemHandler.BLOCK, containerPos, null);
        if (chestInv == null) {
            return callback.addToolResult("Found a block at %s but it has no inventory.".formatted(containerPos.toShortString()), toolId);
        }

        IItemHandler backpackInv = maid.getAvailableBackpackInv();
        int movedItems = 0;
        int movedStacks = 0;
        for (int i = 0; i < backpackInv.getSlots(); i++) {
            ItemStack stack = backpackInv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            ItemStack after = ItemHandlerHelper.insertItemStacked(chestInv, stack.copy(), false);
            int moved = before - after.getCount();
            if (moved > 0) {
                backpackInv.extractItem(i, moved, false);
                movedItems += moved;
                movedStacks++;
            }
        }

        if (movedItems == 0) {
            return callback.addToolResult(
                    "The container at %s is full or accepts none of your items. Nothing was stored."
                            .formatted(containerPos.toShortString()), toolId);
        }
        return callback.addToolResult(
                "Stored %d items (%d stacks) into the container at %s. Backpack now holds %d stacks."
                        .formatted(movedItems, movedStacks, containerPos.toShortString(), countNonEmpty(backpackInv)), toolId);
    }

    /**
     * 从近到远扫描女仆周围，返回第一个带物品容器的方块位置
     */
    private BlockPos findNearestContainer(ServerLevel level, EntityMaid maid) {
        BlockPos center = maid.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
            double dist = center.distSqr(pos);
            if (dist >= bestDist) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) {
                best = pos.immutable();
                bestDist = dist;
            }
        }
        return best;
    }

    private int countNonEmpty(IItemHandler inv) {
        int count = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Store loot into nearby container");
    }

    public record Result() {
    }
}
