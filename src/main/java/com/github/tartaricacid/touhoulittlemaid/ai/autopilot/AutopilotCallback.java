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

    public AutopilotCallback(MaidAIChatManager chatManager, List<LLMMessage> messages) {
        super(chatManager, messages);
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
