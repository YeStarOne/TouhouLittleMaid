package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 单方块采集：检查距离/工具/安全性后挖掘一个方块，掉落物自动进女仆背包。
 * <p>
 * 安全约束（用户 1013 设计）：不挖流体上方悬空结构不做判定简化为——
 * 禁止挖头顶第二层以上、禁止挖流体正下方、工具不适合时拒绝并提示所需工具。
 * 挖掘走 {@link EntityMaid#destroyBlock}，天然复用农场任务的掉落入包逻辑。
 */
public class MineBlockTool implements ITool<MineBlockTool.Result> {
    public static final String TOOL_ID = "mine_block";

    /**
     * 挖掘射程（眼睛到方块中心的距离）。
     * <p>
     * 1.5.3.1-autopilot: 原先用"脚下方块坐标到目标方块坐标"的整格距离、上限 6.0，
     * 女仆按 move_to 停在 6.2~6.4 格处就被判 too_far，整条"找矿→移动→挖矿"链卡死
     * （她的历史里反复出现 too_far 6.2 / 6.4 / 8.2）。改为与玩家交互一致的
     * "眼睛到方块中心"测距，并放宽到 7.0——她不是玩家，允许一点寻路误差。
     */
    private static final double MAX_REACH = 7.0;

    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";

    private static final String TOOL_DESC = """
            Mine ONE block at the given position (must be within 7 blocks of you). Drops go straight into your backpack.
            The tool checks: reach, correct tool (pickaxe for stone-ores, axe for wood), fluid above/below, and head safety.
            Returns what you obtained, or a refusal with the reason (wrong tool, too far, unsafe).
            Workflow: find_blocks to locate ore -> move_to beside it (aim for a spot 2-3 blocks from the ore, not on top of it) -> mine_block each ore position.""";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf(X).forGetter(Result::x),
            Codec.INT.fieldOf(Y).forGetter(Result::y),
            Codec.INT.fieldOf(Z).forGetter(Result::z)
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
        IntegerParameter x = IntegerParameter.create().setDescription("Block X");
        IntegerParameter y = IntegerParameter.create().setDescription("Block Y");
        IntegerParameter z = IntegerParameter.create().setDescription("Block Z");
        root.addProperties(X, x);
        root.addProperties(Y, y);
        root.addProperties(Z, z);
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
            return callback.addToolResult("Can only mine on the server side.", toolId);
        }

        BlockPos pos = new BlockPos(result.x(), result.y(), result.z());
        // 眼睛到方块中心的距离：与玩家交互测距一致，避免"站在旁边但整格坐标差"误判
        double distance = maid.getEyePosition().distanceTo(Vec3.atCenterOf(pos));

        // 距离约束
        if (distance > MAX_REACH) {
            return callback.addToolResult(("refused: too_far\n" +
                    "target: %s\ndistance: %.1f (max %.0f)\n" +
                    "your_position: %s\n" +
                    "suggestion: move_to a standable spot 2-3 blocks away from the target, wait for arrival, then mine_block again.")
                    .formatted(pos.toShortString(), distance, MAX_REACH,
                            maid.blockPosition().toShortString()), toolId);
        }

        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            return callback.addToolResult("refused: no_block\n" +
                    "reason: target position is air (already mined?).", toolId);
        }
        Block block = state.getBlock();
        String blockName = BuiltInRegistries.BLOCK.getKey(block).getPath();

        // 安全：不挖流体正下方的支撑（防灌顶）、不挖自己头顶正上方两格内的支撑
        if (serverLevel.getFluidState(pos.above()).isSource() || serverLevel.getFluidState(pos.above()).isEmpty() == false) {
            return callback.addToolResult(("refused: fluid_above\n" +
                    "target: %s (%s)\nreason: fluid sits directly above; mining floods the area.\n" +
                    "suggestion: mine other blocks first or avoid this spot.")
                    .formatted(pos.toShortString(), blockName), toolId);
        }
        if (pos.getY() >= maid.blockPosition().getY() + 2 && serverLevel.getFluidState(pos.below()).isEmpty() == false) {
            return callback.addToolResult(("refused: unsafe_support\n" +
                    "target: %s (%s)\nreason: mining above your head with fluid below is unsafe.")
                    .formatted(pos.toShortString(), blockName), toolId);
        }

        // 工具检查：需要正确工具才有掉落的方块（矿石类）必须拿对镐
        ItemStack tool = maid.getMainHandItem();
        if (!tool.isCorrectToolForDrops(state)) {
            String required = state.requiresCorrectToolForDrops() ? requiredToolName(state) : "none";
            if (state.requiresCorrectToolForDrops()) {
                return callback.addToolResult(("refused: wrong_tool\n" +
                        "target: %s (%s)\nrequired_tool: %s\nyour_tool: %s\n" +
                        "suggestion: get the right tool first (e.g. iron pickaxe for iron_ore); mining without it yields nothing.")
                        .formatted(pos.toShortString(), blockName, required,
                                tool.isEmpty() ? "empty hand" : BuiltInRegistries.ITEM.getKey(tool.getItem()).getPath()), toolId);
            }
        }

        // 模组级安全检查（含事件钩子）
        if (!maid.canDestroyBlock(pos)) {
            return callback.addToolResult(("refused: protected\n" +
                    "target: %s (%s)\nreason: this block cannot be destroyed here (protection/event).")
                    .formatted(pos.toShortString(), blockName), toolId);
        }

        // 执行挖掘：掉落直接进背包（复用农场任务原语）
        boolean mined = maid.destroyBlock(pos, true);
        if (!mined) {
            return callback.addToolResult("refused: mine_failed\n" +
                    "reason: destroyBlock returned false (unexpected).", toolId);
        }

        String toolNote = tool.isEmpty() ? "bare hands" :
                BuiltInRegistries.ITEM.getKey(tool.getItem()).getPath();
        return callback.addToolResult(("mined: %s\n" +
                "target: %s\ntool: %s\n" +
                "drops went into your backpack. Continue with the next ore nearby, or check backpack with query_game_context(equipment).")
                .formatted(blockName, pos.toShortString(), toolNote), toolId);
    }

    private static String requiredToolName(BlockState state) {
        // 简化映射：按挖掘等级给人类可读提示
        net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase base = null;
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (name.contains("diamond") || name.contains("emerald") || name.contains("gold") || name.contains("redstone")) {
            return "iron pickaxe or better";
        }
        if (name.contains("iron") || name.contains("lapis") || name.contains("copper")) {
            return "stone pickaxe or better";
        }
        return "pickaxe";
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Mine block at %d %d %d".formatted(result.x(), result.y(), result.z()));
    }

    public record Result(int x, int y, int z) {
    }
}
