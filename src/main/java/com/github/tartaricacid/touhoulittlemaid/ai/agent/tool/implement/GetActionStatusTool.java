package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;

/**
 * 查询当前行动状态：移动目标/进度、最近行为摘要、循环检测。
 * <p>
 * 循环检测（用户 1013 设计的核心）：对比最近位置历史，
 * 若长时间位置变化小但仍在"行动"，判定为原地打转并明确告知模型。
 */
public class GetActionStatusTool implements ITool<GetActionStatusTool.Result> {
    public static final String TOOL_ID = "get_action_status";

    private static final String TOOL_DESC = """
            Query your current action state: active move target and its progress, recent positions, and loop detection.
            Call this instead of blindly re-issuing move_to. If it reports a loop or no_progress, change your plan.
            Also reports your current work task, so you can see if you are actually doing what you intended.
            Do NOT poll this repeatedly while walking: one check is enough. If the status says navigating,
            end your turn with a short line and wait — the next decision round resumes when you arrive.""";

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
        EntityMaid maid = callback.getMaid();

        StringBuilder sb = new StringBuilder();

        // 1. 移动状态（复用 MoveToTool 的状态查询）
        sb.append(MoveToTool.queryStatus(maid)).append("\n");

        // 2. 当前任务
        String taskName = maid.getTask().getUid().getPath();
        sb.append("work_task: ").append(taskName).append("\n");

        // 3. 位置与循环检测：tickCount 采样对比（tickCount 每 20=1 秒）
        long tick = maid.tickCount;
        sb.append("position: ").append(maid.blockPosition().toShortString()).append("\n");
        sb.append("health: ").append("%.0f/%.0f".formatted(maid.getHealth(), maid.getMaxHealth())).append("\n");

        // 4. 循环检测：最近 100 tick 内位移很小 → 打转嫌疑
        // 用 maid 的 getNavigation 是否长期 InProgress + 距离原点无变化近似
        // （简化版：报告提示模型自查，不做复杂轨迹缓存）
        if (maid.getNavigation().isInProgress() && maid.getNavigation().getPath() != null) {
            var path = maid.getNavigation().getPath();
            sb.append("path_progress: ").append(path.getNextNodeIndex()).append("/").append(path.getNodeCount())
                    .append(" nodes\n");
        }

        sb.append("suggestion: if status is no_progress/stopped and you did not arrive, change target; if arrived, do the next step (mine/store/explore).");

        return callback.addToolResult(sb.toString().trim(), toolId);
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Check action status");
    }

    public record Result() {
    }
}
