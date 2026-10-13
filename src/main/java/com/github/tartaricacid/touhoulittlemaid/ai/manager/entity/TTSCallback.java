package com.github.tartaricacid.touhoulittlemaid.ai.manager.entity;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSAudioToClientPackage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.http.HttpRequest;

public class TTSCallback implements ResponseCallback<byte[]> {
    /** 附近玩家可听见的距离（1.5.3.1-autopilot：让自主女仆在主人不在时也能被听见）。 */
    private static final double AUDIBLE_RANGE = 32.0;

    private final EntityMaid maid;
    private final String chatText;
    private final long waitingChatBubbleId;

    public TTSCallback(EntityMaid maid, String chatText, long waitingChatBubbleId) {
        this.maid = maid;
        this.chatText = chatText;
        this.waitingChatBubbleId = waitingChatBubbleId;
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        if (maid.level instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            server.submit(() -> {
                if (maid.getOwner() instanceof ServerPlayer player) {
                    String cause = throwable.getLocalizedMessage();
                    MutableComponent errorMessage = ErrorCode.getErrorMessage(ServiceType.TTS, errorCode, cause);
                    player.sendSystemMessage(errorMessage.withStyle(ChatFormatting.RED));
                }
                maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId);
            });
        }
        TouhouLittleMaid.LOGGER.error("LLM request failed: {}, error is {}", request, throwable.getMessage());
    }

    @Override
    public void onSuccess(byte[] data) {
        if (!(maid.level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        server.submit(() -> {
            // 1.5.3.1-autopilot: 原版只把音频发给主人的实现，会让没有主人在线的自主女仆永远没有声音
            // （野生女仆的 getOwner() 返回 null，这里直接 return，连聊天气泡都不会挂）。
            // 改为：主人（无论多远）+ 同维度 32 格内玩家都能听到。
            TTSAudioToClientPackage packet = new TTSAudioToClientPackage(maid.getId(), data);
            if (maid.getOwner() instanceof ServerPlayer owner) {
                PacketDistributor.sendToPlayer(owner, packet);
            }
            double rangeSqr = AUDIBLE_RANGE * AUDIBLE_RANGE;
            for (ServerPlayer player : serverLevel.players()) {
                if (!player.equals(maid.getOwner()) && player.distanceToSqr(maid) <= rangeSqr) {
                    PacketDistributor.sendToPlayer(player, packet);
                }
            }
            maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId);
        });
    }

    public EntityMaid getMaid() {
        return maid;
    }
}
