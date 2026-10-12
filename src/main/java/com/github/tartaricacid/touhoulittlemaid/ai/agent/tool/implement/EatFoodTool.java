package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.api.task.meal.IMaidMeal;
import com.github.tartaricacid.touhoulittlemaid.api.task.meal.MaidMealType;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.meal.MaidMealManager;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.SpawnParticlePackage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * 让女仆从背包里吃东西：优先吃手上的，否则从背包找可食用物品直接食用。
 * 效果与女仆自愈任务的进食一致（回复生命值，走饱食度加和计算）。
 */
public class EatFoodTool implements ITool<EatFoodTool.Result> {
    public static final String TOOL_ID = "eat_food";

    private static final String TOOL_DESC = """
            Eat one food item from your own backpack (or hand) to restore health.
            Use this when you are hurt, or when the player asks you to eat.""";

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
        // 无参数：直接返回空根对象
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!(callback.getMaid().level instanceof ServerLevel serverLevel)) {
            return callback.addToolResult("Can only eat on the server side.", toolId);
        }
        EntityMaid maid = callback.getMaid();
        List<IMaidMeal> meals = MaidMealManager.getMaidMeals(MaidMealType.HEAL_MEAL);

        // 1) 手上已有食物：直接吃
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack inHand = maid.getItemInHand(hand);
            if (inHand.isEmpty()) {
                continue;
            }
            for (IMaidMeal meal : meals) {
                if (meal.canMaidEat(maid, inHand, hand)) {
                    String name = eatOne(maid, serverLevel, inHand);
                    return callback.addToolResult("Ate %s from hand.".formatted(name), toolId);
                }
            }
        }

        // 2) 背包里找食物：直接消耗一份并回复生命
        IItemHandler backpackInv = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpackInv.getSlots(); i++) {
            ItemStack stack = backpackInv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties food = stack.getFoodProperties(maid);
            if (food == null) {
                continue;
            }
            for (IMaidMeal meal : meals) {
                if (meal.canMaidEat(maid, stack, InteractionHand.MAIN_HAND)) {
                    String name = stack.getHoverName().getString();
                    consumeOne(backpackInv, i, maid, serverLevel, stack, food);
                    return callback.addToolResult("Ate %s from backpack.".formatted(name), toolId);
                }
            }
        }

        return callback.addToolResult("No edible food found in hands or backpack. Try farming or fishing first.", toolId);
    }

    /**
     * 手持食物的进食：走原版 onMaidEat（会触发使用动画与饰品事件）
     */
    private String eatOne(EntityMaid maid, ServerLevel level, ItemStack inHand) {
        String name = inHand.getHoverName().getString();
        List<IMaidMeal> meals = MaidMealManager.getMaidMeals(MaidMealType.HEAL_MEAL);
        for (IMaidMeal meal : meals) {
            if (meal.canMaidEat(maid, inHand, InteractionHand.MAIN_HAND)) {
                meal.onMaidEat(maid, inHand, InteractionHand.MAIN_HAND);
                break;
            }
        }
        return name;
    }

    /**
     * 背包食物的进食：直接消耗一份并按营养值回血（与 DefaultMaidHealSelfMeal 的回血公式一致）
     */
    private void consumeOne(IItemHandler backpackInv, int slot, EntityMaid maid,
                            ServerLevel level, ItemStack stack, FoodProperties food) {
        maid.getMaidBauble().fireEvent((b, s) -> {
            b.onMaidEat(maid, s, stack, MaidMealType.HEAL_MEAL);
            return false;
        });
        float total = food.nutrition() + food.saturation();
        if (maid.getRandom().nextInt(5) < total) {
            maid.heal(Math.max(total / 5, 1));
        }
        NetworkHandler.sendToNearby(maid, new SpawnParticlePackage(maid.getId(),
                SpawnParticlePackage.Type.HEAL, 16));
        backpackInv.extractItem(slot, 1, false);
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Eat food from backpack");
    }

    public record Result() {
    }
}
