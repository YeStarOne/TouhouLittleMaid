package com.github.tartaricacid.touhoulittlemaid.ai.autopilot;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * 自主模式回调：在 {@link LLMCallback} 之上追踪一次自主决策的生命周期，
 * 无论成功或失败都通知 {@link MaidAutopilotManager} 清除 in-flight 标记，
 * 让该女仆能进入下一轮决策。
 */
public class AutopilotCallback extends LLMCallback {
    /**
     * 自主模式的工具轮上限（原版 16）：一次自主决策往往是一整条任务链
     * （找矿 → 移动 → 挖矿 → 移动 → 挖矿 → 存箱…），16 轮会在链中途
     * 被硬中断。中断即整轮失败，还会吃 60 秒失败退避，导致"干到一半
     * 被判失败"的死循环。放宽到 48 轮，允许一条完整任务链跑完。
     */
    private static final int AUTOPILOT_MAX_TOOL_TURN = 48;
    /**
     * 自主模式下连续重复同一批工具的容忍次数（原版 2）：
     * 边走边轮询 get_action_status 是合理行为（她在等寻路完成），
     * 放宽到 6，仍能兜住真正的死循环。
     */
    private static final int AUTOPILOT_MAX_REPEAT_BATCH = 6;

    public AutopilotCallback(MaidAIChatManager chatManager, List<LLMMessage> messages) {
        super(chatManager, messages);
    }

    @Override
    protected int maxToolTurnCount() {
        return AUTOPILOT_MAX_TOOL_TURN;
    }

    @Override
    protected int maxRepeatedToolBatchCount() {
        return AUTOPILOT_MAX_REPEAT_BATCH;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        super.onSuccess(responseChat);
        MaidAutopilotManager.onAutopilotFinished(this.maid.getUUID(), true);
        TouhouLittleMaid.LOGGER.info("[Autopilot] maid {} decided: {}",
                this.maid.getUUID(), responseChat.getChatText());
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        MaidAutopilotManager.onAutopilotFinished(this.maid.getUUID(), false);
        TouhouLittleMaid.LOGGER.warn("[Autopilot] maid {} decision failed: {}",
                this.maid.getUUID(), throwable == null ? "unknown" : throwable.getMessage());
    }
}
