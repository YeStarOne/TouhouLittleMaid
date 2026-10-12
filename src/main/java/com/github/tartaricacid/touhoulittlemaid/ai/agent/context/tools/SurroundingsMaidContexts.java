package com.github.tartaricacid.touhoulittlemaid.ai.agent.context.tools;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 女仆的"方块视野"：让她看见脚下的地面、周围的方块构成、成熟作物和容器位置。
 * <p>
 * 原版 TLM 的上下文只有实体/装备/位置，LLM 看不见任何方块——所以女仆会"盲走"
 * （嘴上说去收菜，其实不知道田在哪）。本分类把环境压缩成聚合文本，
 * 作为 prompt context 每轮自动注入，让女仆决策时"睁着眼"。
 * <p>
 * 设计取舍：不逐块列出（token 爆炸），而是聚合统计 + 关键目标（成熟作物/容器）
 * 带坐标。扫描半径 8、垂直 ±2，约 1.4k 格读取，全部是内存操作，成本可忽略。
 */
public final class SurroundingsMaidContexts {
    public static final String CATEGORY = "surroundings";
    private static final String SUMMARY = "Block-level vision: ground under feet, nearby block composition, mature crops and containers";

    private static final int H_RADIUS = 8;
    private static final int V_RADIUS = 2;
    private static final int TOP_BLOCKS = 10;

    private SurroundingsMaidContexts() {
    }

    public static void registerAll(GameContextRegister register) {
        // prompt context = true：每轮对话自动注入，让女仆持续"睁眼"
        register.registerCategory(CATEGORY, SUMMARY, true);
        register.registerContext(CATEGORY, new StandingOnContext());
        register.registerContext(CATEGORY, new BlocksAroundContext());
        register.registerContext(CATEGORY, new MatureCropsContext());
        register.registerContext(CATEGORY, new NearestContainerContext());
    }

    /**
     * 脚下的方块（判断她站在耕地/草/雪/水上）
     */
    private static final class StandingOnContext extends AbstractMaidContext {
        private StandingOnContext() {
            super("standing_on", "Block under feet");
        }

        @Override
        public String getValue(EntityMaid maid) {
            BlockPos below = maid.blockPosition().below();
            BlockState state = maid.level.getBlockState(below);
            return blockName(state);
        }
    }

    /**
     * 周围方块聚合统计（按数量降序取前 N 类），如 "farmland×24, wheat×8, grass_block×15, chest×1"
     */
    private static final class BlocksAroundContext extends AbstractMaidContext {
        private BlocksAroundContext() {
            super("blocks_around", "Nearby block composition (aggregated)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            BlockPos center = maid.blockPosition();
            Map<String, Integer> counts = new TreeMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-H_RADIUS, -V_RADIUS, -H_RADIUS),
                    center.offset(H_RADIUS, V_RADIUS, H_RADIUS))) {
                BlockState state = maid.level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                String name = blockName(state);
                counts.merge(name, 1, Integer::sum);
            }
            if (counts.isEmpty()) {
                return "all air";
            }
            List<Map.Entry<String, Integer>> sorted = Lists.newArrayList(counts.entrySet());
            sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            List<String> parts = Lists.newArrayList();
            for (int i = 0; i < Math.min(TOP_BLOCKS, sorted.size()); i++) {
                Map.Entry<String, Integer> entry = sorted.get(i);
                parts.add("%s×%d".formatted(entry.getKey(), entry.getValue()));
            }
            return String.join(", ", parts);
        }
    }

    /**
     * 附近成熟作物数量 + 最近一株的位置与距离（farm 任务的实时进度反馈）
     */
    private static final class MatureCropsContext extends AbstractMaidContext {
        private MatureCropsContext() {
            super("mature_crops", "Mature crops nearby (count + nearest position)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            BlockPos center = maid.blockPosition();
            int matureCount = 0;
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-H_RADIUS, -V_RADIUS, -H_RADIUS),
                    center.offset(H_RADIUS, V_RADIUS, H_RADIUS))) {
                BlockState state = maid.level.getBlockState(pos);
                Block block = state.getBlock();
                if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                    matureCount++;
                    double dist = center.distSqr(pos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = pos.immutable();
                    }
                }
            }
            if (matureCount == 0) {
                return "none (nothing ready to harvest in range)";
            }
            String nearestText = nearest == null ? "" :
                    ", nearest %s (%.1f blocks away)".formatted(nearest.toShortString(), Math.sqrt(nearestDist));
            return "%d mature crop block(s)%s".formatted(matureCount, nearestText);
        }
    }

    /**
     * 最近的容器（箱子/木桶等），带坐标与距离——配合 store_loot 使用
     */
    private static final class NearestContainerContext extends AbstractMaidContext {
        private NearestContainerContext() {
            super("nearest_container", "Nearest container (chest/barrel) with position and distance");
        }

        @Override
        public String getValue(EntityMaid maid) {
            BlockPos center = maid.blockPosition();
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-H_RADIUS, -V_RADIUS, -H_RADIUS),
                    center.offset(H_RADIUS, V_RADIUS, H_RADIUS))) {
                if (maid.level.getBlockEntity(pos) instanceof Container) {
                    double dist = center.distSqr(pos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = pos.immutable();
                    }
                }
            }
            if (nearest == null) {
                return "none within %d blocks".formatted(H_RADIUS);
            }
            return "%s at %s (%.1f blocks away)".formatted(
                    blockName(maid.level.getBlockState(nearest)),
                    nearest.toShortString(),
                    Math.sqrt(nearestDist));
        }
    }

    private static String blockName(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = key.getPath();
        // 带状态的关键方块补充状态信息（作物成熟度、耕地湿润等）
        if (state.getBlock() instanceof CropBlock crop) {
            int age = crop.getAge(state);
            return path + (crop.isMaxAge(state) ? "[mature]" : "[age=" + age + "]");
        }
        return path;
    }
}
