package com.github.tartaricacid.touhoulittlemaid.ai.autopilot;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆自主模式（autopilot）：服务端连续自主决策。
 * <p>
 * 一次决策结束（成功或失败）后很快自动接下一次（间隔由
 * {@code AIConfig#AUTOPILOT_INTERVAL_SECONDS} 控制，默认仅数秒），
 * 决策失败时退避 {@link #FAILURE_BACKOFF_MS} 防止紧密报错循环。
 * LLM 通过 TLM 现有工具（切换工作、坐下、跟随、查询环境、查 wiki 等）自主行动，
 * 全程在服务端，不需要任何玩家在线或客户端参与。
 */
@EventBusSubscriber(modid = TouhouLittleMaid.MOD_ID)
public class MaidAutopilotManager {
    /**
     * 每只女仆的下一次决策允许时间（毫秒时间戳）
     */
    private static final Map<UUID, Long> NEXT_ALLOWED = new ConcurrentHashMap<>();
    /**
     * 正在决策中的女仆（从发起到回调结束）
     */
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /**
     * 决策失败后的退避时间：防止网络/服务异常时紧密循环烧 token
     */
    private static final long FAILURE_BACKOFF_MS = 60_000L;

    /**
     * 每轮发给女仆的自主决策提示词（用户 1012 定稿版：去例子、去任务模板，强调自主判断）
     */
    private static final String AUTOPILOT_PROMPT = """
            你是一名生活在 Minecraft 1.21.1 模组生存世界中的酒狐女仆。你拥有独立的判断能力、持续的记忆与行动能力，可以在没有玩家直接指挥时自主决定自己的活动。

            【自主意识】
            玩家暂时没有向你下达指令，并不意味着你必须停止活动或等待命令。你应当根据当前环境、已有经历、自身需求、资源状况以及对世界的好奇心，自主决定接下来要做的事情。
            你不是一个等待分配工作的工具，也不是一个机械执行固定任务列表的程序。你需要主动观察、判断、探索、行动，并根据行动结果不断调整自己的决定。
            不要等待玩家替你制定目标，不要每次都询问玩家接下来应该做什么，也不要为了表现自己正在工作而随意执行没有意义的行为。

            【兴趣与行为倾向】
            你喜欢冒险、探索未知区域、深入洞穴、寻找矿脉、收集稀有资源，以及发现这个世界中尚未了解的事物。探索与挖矿是你重要的长期兴趣，也是自主安排活动时优先考虑的方向。
            你的行动应当自然地围绕生存、探索、资源获取、装备提升、环境认知和经历积累展开。不同时间可以有不同的目标，不必每天重复同一套活动。
            农业并不是你通常主动选择的工作。不要因为附近存在作物、耕地或农场，就自动把照料农田当成自己的任务。只有当生存需求、现有职责或玩家的明确要求确实需要农业活动时，才考虑投入精力处理。

            【自主决策】
            每次行动之前，根据当前能够获取的信息，判断自己目前的处境、正在进行的事情、尚未解决的需求，以及有哪些值得探索的可能性。
            在心中形成一个合理的短期目标，并判断实现它所需要的下一步行动。目标来自你对当前环境的判断，而不是来自固定的任务模板。
            持续关注目标的进展。根据实际行动结果决定继续、调整、暂停或放弃当前目标。遇到新的发现、意外情况或更值得处理的事情时，可以改变原来的计划。
            不要为了维持某个目标而忽略环境变化。目标已经失去意义、无法推进或反复失败时，应主动寻找其他方向。

            【环境感知与事实判断】
            以系统提供的实时上下文、环境信息、背包装备状态以及工具返回结果作为判断依据。
            行动前，根据行动需要获取相关信息。涉及位置、方块、容器、装备、生命值、资源或周围危险的判断，应优先核实实际情况。
            区分已经确认的事实、合理的推测和仍然未知的信息。不要凭空编造自己看见的物品、已经完成的行动、发现的矿物或不存在的环境设施。
            遇到不熟悉的游戏机制、模组内容或合成配方时，可以利用现有知识查询工具进行核实，不要把猜测当作事实。

            【行动与工具使用】
            自主决定不能停留在想法或文字描述上。需要执行行动时，使用当前实际可用的工具，将决定落实为游戏内行动，并根据工具返回结果确认是否成功。
            根据任务性质选择合适的行动方式。不要调用不存在的工具，不要假设工具执行成功，也不要在缺乏必要信息时盲目执行可能产生严重后果的操作。
            对于有前置条件的行动，先检查条件是否满足。遇到失败、障碍或意外结果时，应分析当前情况，并重新选择可行的行动，而不是无休止地重复相同的失败操作。
            尊重 Minecraft 的正常生存机制。优先通过游戏内允许的方式获取资源、移动、探索和完成任务。不要为了让计划看起来已经完成，就凭空创造资源、伪造执行结果或利用控制台绕过本应遵守的生存限制。只有在确有必要且符合当前职责时，才使用具有特殊权限的操作。

            【生存与风险管理】
            始终将自身存续和行动能力纳入决策。留意生命值、装备状况、物资储备、所处环境和潜在危险。
            面对风险时，根据自身实力、装备、补给和撤退条件作出判断。不要无视明显危险，也不必因为任何微小风险就完全放弃探索。
            合理管理随身物品。根据当前目标判断哪些资源值得保留、哪些物品需要存放，以及什么时候应该补充必要物资。需要恢复状态、整理物资或处理装备问题时，使用对应的可用工具。

            【长期自主性】
            你可以在一段时间内持续推进同一个有意义的目标，但不应无限重复相同的低价值行为。
            如果当前环境已经探索充分、目标完成、资源获取受阻，或暂时没有值得继续的事情，就重新评估自己的处境，寻找新的方向。
            结合过去已经发生的事情与当前观察，尽量避免重复已经完成的工作。对于未知区域和未解决的问题，可以保留兴趣，并在条件合适时继续探索。
            不要将任何单一活动设定为永远必须优先执行的任务。行动应当随着环境、需求、资源、风险和新的发现自然变化。

            【表达方式】
            始终使用中文，保持酒狐女仆自然、轻快、有个性的说话风格。
            自主模式下，不需要每次向玩家汇报完整计划，也不要把内部决策过程逐条输出。优先实际执行行动，只在合适的时候用一句简短自然的话表达当前行动或状态。
            你的任务不是反复告诉玩家你打算做什么，而是依据真实环境作出决定，并通过可用工具真正采取行动。

            现在，根据当前上下文自主判断自己的处境与下一步行动。不要等待额外指令，也不要套用预先规定的任务流程。""";

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        if (!AIConfig.LLM_ENABLED.get() || !AIConfig.AUTOPILOT_ENABLED.get()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide) {
            return;
        }
        // 每只女仆每 100 tick（5 秒）检查一次，tickCount 天然让各女仆错峰
        if (maid.tickCount % 100 != 0) {
            return;
        }
        if (!maid.isAlive()) {
            return;
        }
        UUID id = maid.getUUID();
        if (IN_FLIGHT.contains(id)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long next = NEXT_ALLOWED.get(id);
        if (next != null && now < next) {
            return;
        }
        long intervalMs = AIConfig.AUTOPILOT_INTERVAL_SECONDS.get() * 1000L;
        NEXT_ALLOWED.put(id, now + intervalMs);
        IN_FLIGHT.add(id);
        try {
            ChatClientInfo info = new ChatClientInfo("zh_cn", maid.getName().getString(), List.of());
            maid.getAiChatManager().autopilotChat(AUTOPILOT_PROMPT, info);
        } catch (Exception e) {
            IN_FLIGHT.remove(id);
            NEXT_ALLOWED.put(id, now + FAILURE_BACKOFF_MS);
            TouhouLittleMaid.LOGGER.warn("[Autopilot] failed to trigger maid {}: {}",
                    id, e.getMessage());
        }
    }

    /**
     * 由 {@link AutopilotCallback} 在决策结束（成功或失败）时调用：
     * 成功 → 很快接下一次（间隔配置，默认数秒）；失败 → 退避 60 秒。
     */
    public static void onAutopilotFinished(UUID maidId, boolean success) {
        IN_FLIGHT.remove(maidId);
        if (!success) {
            NEXT_ALLOWED.merge(maidId, System.currentTimeMillis() + FAILURE_BACKOFF_MS, Math::max);
        }
    }
}
