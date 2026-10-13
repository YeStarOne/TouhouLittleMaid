package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 在指定范围内搜索目标方块，返回最近的若干命中（含距离）。
 * 与 move_to 组成"找矿→抵达"流程；搜索走服务端方块注册表按名称匹配。
 */
public class FindBlocksTool implements ITool<FindBlocksTool.Result> {
    public static final String TOOL_ID = "find_blocks";

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 48;
    private static final int MAX_HITS = 12;
    /**
     * 垂直扫描范围（矿脉多在斜下方/洞穴）
     */
    private static final int V_RANGE = 12;

    private static final String BLOCK = "block";
    private static final String RADIUS = "radius";

    private static final String TOOL_DESC = """
            Search blocks by name within a radius around you and return the nearest hits with coordinates and distance.
            The block name is the registry id without namespace, e.g. "coal_ore", "iron_ore", "chest", "oak_log". Suffixes like "[axis=x]" are not allowed.
            Use this to locate ores, chests, water, or landmarks before moving. Combine with move_to to approach, then mine_block to dig.""";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(BLOCK).forGetter(Result::block),
            Codec.INT.optionalFieldOf(RADIUS, DEFAULT_RADIUS).forGetter(Result::radius)
    ).apply(instance, Result::new));

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
        StringParameter block = StringParameter.create().setDescription("Block registry name without namespace, e.g. coal_ore");
        IntegerParameter radius = IntegerParameter.create().setDescription("Search radius, default 16, max 48");
        root.addProperties(BLOCK, block);
        root.addProperties(RADIUS, radius, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (!(maid.level instanceof ServerLevel serverLevel)) {
            return callback.addToolResult("Can only search on the server side.", toolId);
        }

        String blockId = result.block().toLowerCase().trim();
        // 容错：允许带 minecraft: 前缀或误带的方括号
        blockId = blockId.replace("minecraft:", "");
        int bracket = blockId.indexOf('[');
        if (bracket > 0) {
            blockId = blockId.substring(0, bracket);
        }

        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(blockId));
        if (block.defaultBlockState().isAir()) {
            return callback.addToolResult(
                    "Unknown block '%s'. Use a vanilla registry name like coal_ore, iron_ore, deepslate_diamond_ore, chest."
                            .formatted(result.block()), toolId);
        }

        int radius = Math.max(1, Math.min(result.radius(), MAX_RADIUS));
        BlockPos center = maid.blockPosition();

        List<BlockPos> hits = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -V_RANGE, -radius),
                center.offset(radius, V_RANGE, radius))) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(block)) {
                hits.add(pos.immutable());
                if (hits.size() >= 256) {
                    break;
                }
            }
        }

        if (hits.isEmpty()) {
            return callback.addToolResult(("found: 0\n" +
                    "block: %s\nradius: %d\n" +
                    "reason: no hits in range. Move toward unexplored areas (bigger radius, or move_to a new spot and search again).")
                    .formatted(blockId, radius), toolId);
        }

        hits.sort(Comparator.comparingDouble(pos -> center.distSqr(pos)));
        StringBuilder sb = new StringBuilder();
        sb.append("found: ").append(hits.size()).append(" (showing nearest ").append(Math.min(MAX_HITS, hits.size())).append(")\n");
        sb.append("block: ").append(blockId).append("\n");
        for (int i = 0; i < Math.min(MAX_HITS, hits.size()); i++) {
            BlockPos pos = hits.get(i);
            double dist = Math.sqrt(center.distSqr(pos));
            // 附带该方块下方的方块名，判断是否悬空矿脉（悬崖/洞顶）
            String below = serverLevel.getBlockState(pos.below()).getBlock() == block
                    ? "same" : BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(pos.below()).getBlock()).getPath();
            sb.append("- %s dist=%.1f (below: %s)\n".formatted(pos.toShortString(), dist, below));
        }
        sb.append("suggestion: move_to the nearest standable spot (usually beside/above the ore), then mine_block it.");

        String text = sb.toString().trim();
        return callback.addToolResult(text, toolId);
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Search %s nearby".formatted(result.block()));
    }

    public record Result(String block, int radius) {
    }
}
