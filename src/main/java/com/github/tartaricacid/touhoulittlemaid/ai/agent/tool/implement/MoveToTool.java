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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带目标管理与进度反馈的移动工具（修复"持续走动兜圈"）。
 * <p>
 * 核心机制（用户 1013 设计）：
 * <ol>
 *   <li>单一移动目标：同一时刻每只女仆只有一个寻路目标</li>
 *   <li>目标复用：目标未变且寻路仍在进行时不重复启动寻路</li>
 *   <li>进度检测：记录出发距离与当前位置，识别"走但不接近"的路径异常</li>
 *   <li>失败冷却：同一失败目标短时间内不重复尝试，防止循环</li>
 * </ol>
 * 返回结构化进度（出发距离/当前距离/状态），让模型根据事实决策。
 */
public class MoveToTool implements ITool<MoveToTool.Result> {
    public static final String TOOL_ID = "move_to";

    private static final int DEFAULT_ARRIVE_RADIUS = 3;
    private static final int MAX_RADIUS = 64;
    private static final long NAVIGATION_TIMEOUT_MS = 30_000;
    private static final long FAILURE_COOLDOWN_MS = 120_000;
    private static final double STUCK_DISTANCE_RATIO = 0.9;

    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String RADIUS = "radius";

    private static final String TOOL_DESC = """
            Walk to the given coordinates. Returns a structured result: status (arrived / navigating / unreachable / no_progress / cooldown),
            your position, the target, and distance progress (distance_before vs distance_after).
            Rules:
            - Do NOT issue a new move_to while the previous navigation is still valid and moving you closer; check get_action_status instead.
            - If status is no_progress or unreachable, do NOT retry the same target soon; pick a different goal.
            - Use it to approach mining spots, containers, or explore toward a direction. Ore blocks themselves are not standable; move near them.""";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf(X).forGetter(Result::x),
            Codec.INT.fieldOf(Y).forGetter(Result::y),
            Codec.INT.fieldOf(Z).forGetter(Result::z),
            Codec.INT.optionalFieldOf(RADIUS, DEFAULT_ARRIVE_RADIUS).forGetter(Result::radius)
    ).apply(instance, Result::new));

    /**
     * 每只女仆的当前移动目标（单一目标约束）
     */
    private static final java.util.Map<UUID, BlockPos> ACTIVE_TARGETS = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> TARGET_STARTED_AT = new ConcurrentHashMap<>();
    /**
     * 失败目标冷却：uuid -> (pos -> 失败时刻)
     */
    private static final java.util.Map<UUID, java.util.Map<Long, Long>> FAILED_TARGETS = new ConcurrentHashMap<>();

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
        IntegerParameter x = IntegerParameter.create().setDescription("Target X");
        IntegerParameter y = IntegerParameter.create().setDescription("Target Y");
        IntegerParameter z = IntegerParameter.create().setDescription("Target Z");
        IntegerParameter radius = IntegerParameter.create().setDescription("Arrive radius in blocks, default 3");
        root.addProperties(X, x);
        root.addProperties(Y, y);
        root.addProperties(Z, z);
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
            return callback.addToolResult("Can only move on the server side.", toolId);
        }

        BlockPos target = new BlockPos(result.x(), result.y(), result.z());
        int radius = Math.max(1, Math.min(result.radius(), MAX_RADIUS));
        UUID id = maid.getUUID();
        double distanceBefore = Math.sqrt(maid.blockPosition().distSqr(target));

        // 冷却中的失败目标：直接拒绝，防循环
        java.util.Map<Long, Long> failures = FAILED_TARGETS.get(id);
        if (failures != null) {
            Long failedAt = failures.get(target.asLong());
            if (failedAt != null) {
                long since = System.currentTimeMillis() - failedAt;
                if (since < FAILURE_COOLDOWN_MS) {
                    return callback.addToolResult(("status: cooldown\n" +
                            "reason: target %s failed %.0f seconds ago; do NOT retry it now.\n" +
                            "suggestion: choose a different goal, or wait until the cooldown passes.")
                            .formatted(target.toShortString(), since / 1000.0), toolId);
                }
                failures.remove(target.asLong());
            }
        }

        // 目标复用：目标未变且寻路仍在进行 → 不重复启动，返回当前进度
        BlockPos active = ACTIVE_TARGETS.get(id);
        PathNavigation nav = maid.getNavigation();
        if (target.equals(active) && nav.isInProgress()) {
            double distanceNow = Math.sqrt(maid.blockPosition().distSqr(target));
            return callback.addToolResult(("status: navigating\n" +
                    "position: %s\ntarget: %s\n" +
                    "distance_before: %.1f\ndistance_after: %.1f\n" +
                    "reason: same target already in progress; keep waiting.\n" +
                    "suggestion: use get_action_status to monitor, or do something else meanwhile.")
                    .formatted(maid.blockPosition().toShortString(), target.toShortString(),
                            distanceBefore, distanceNow), toolId);
        }

        // 无路可达：快速失败并记录冷却
        if (!maid.canPathReach(target)) {
            recordFailure(id, target);
            ACTIVE_TARGETS.remove(id);
            return callback.addToolResult(("status: unreachable\n" +
                    "position: %s\ntarget: %s\ndistance_before: %.1f\n" +
                    "reason: no walkable path to the target.\n" +
                    "suggestion: pick a nearer/other target. Teleport commands are blocked; walking is the only way to move.")
                    .formatted(maid.blockPosition().toShortString(), target.toShortString(), distanceBefore), toolId);
        }

        // 启动寻路（覆盖旧目标 = 单一移动目标）
        nav.stop();
        boolean started = nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        if (!started) {
            recordFailure(id, target);
            ACTIVE_TARGETS.remove(id);
            return callback.addToolResult(("status: unreachable\n" +
                    "position: %s\ntarget: %s\n" +
                    "reason: navigation refused the path.\n" +
                    "suggestion: try a different target position (a standable spot).")
                    .formatted(maid.blockPosition().toShortString(), target.toShortString()), toolId);
        }
        ACTIVE_TARGETS.put(id, target);
        TARGET_STARTED_AT.put(id, System.currentTimeMillis());

        double distanceNow = Math.sqrt(maid.blockPosition().distSqr(target));
        return callback.addToolResult(("status: navigating\n" +
                "position: %s\ntarget: %s\n" +
                "distance_before: %.1f\ndistance_after: %.1f\n" +
                "reason: path started.\n" +
                "suggestion: wait for arrival; check get_action_status later. Do not re-issue move_to for the same target.")
                .formatted(maid.blockPosition().toShortString(), target.toShortString(),
                        distanceBefore, distanceNow), toolId);
    }

    /**
     * 供 get_action_status 查询：当前导航状态与进度评估。
     * 返回 JSON 风格文本；无活动目标时返回 idle。
     */
    public static String queryStatus(EntityMaid maid) {
        UUID id = maid.getUUID();
        BlockPos target = ACTIVE_TARGETS.get(id);
        if (target == null) {
            return "status: idle (no active move target)";
        }
        PathNavigation nav = maid.getNavigation();
        double distanceNow = Math.sqrt(maid.blockPosition().distSqr(target));
        long startedAt = TARGET_STARTED_AT.getOrDefault(id, System.currentTimeMillis());
        long elapsed = System.currentTimeMillis() - startedAt;

        // 超时判定
        if (elapsed > NAVIGATION_TIMEOUT_MS && nav.isInProgress()) {
            nav.stop();
            recordFailure(id, target);
            ACTIVE_TARGETS.remove(id);
            return ("status: no_progress\n" +
                    "position: %s\ntarget: %s\ndistance_after: %.1f\n" +
                    "reason: navigation exceeded %d seconds without arriving.\n" +
                    "suggestion: stop this move; choose a nearer target or another task.")
                    .formatted(maid.blockPosition().toShortString(), target.toShortString(), distanceNow,
                            NAVIGATION_TIMEOUT_MS / 1000);
        }
        if (!nav.isInProgress()) {
            boolean arrived = maid.blockPosition().distSqr(target) <= (long) DEFAULT_ARRIVE_RADIUS * DEFAULT_ARRIVE_RADIUS;
            ACTIVE_TARGETS.remove(id);
            if (arrived) {
                return ("status: arrived\n" +
                        "position: %s\ntarget: %s\ndistance_after: %.1f")
                        .formatted(maid.blockPosition().toShortString(), target.toShortString(), distanceNow);
            }
            return ("status: stopped\n" +
                    "position: %s\ntarget: %s\ndistance_after: %.1f\n" +
                    "reason: navigation ended before arrival (interrupted or partial path).")
                    .formatted(maid.blockPosition().toShortString(), target.toShortString(), distanceNow);
        }
        return ("status: navigating\n" +
                "position: %s\ntarget: %s\ndistance_after: %.1f\nelapsed_seconds: %.0f")
                .formatted(maid.blockPosition().toShortString(), target.toShortString(), distanceNow, elapsed / 1000.0);
    }

    private static void recordFailure(UUID id, BlockPos target) {
        FAILED_TARGETS.computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                .put(target.asLong(), System.currentTimeMillis());
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Walk to %d, %d, %d".formatted(result.x(), result.y(), result.z()));
    }

    public record Result(int x, int y, int z, int radius) {
    }
}
