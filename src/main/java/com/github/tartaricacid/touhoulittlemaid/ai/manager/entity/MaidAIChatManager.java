package com.github.tartaricacid.touhoulittlemaid.ai.manager.entity;

import com.github.tartaricacid.touhoulittlemaid.ai.autopilot.AutopilotCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.StringConstant;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.DefaultLLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSystemServices;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.data.ChatTokensAttachment;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataAttachment;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSSystemAudioToClientPackage;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.StringConstant.AUTO_GEN_SETTING;

public final class MaidAIChatManager extends MaidAIChatData {
    private static final String DEEPSEEK_PLATFORM_URL = "https://platform.deepseek.com/";
    /**
     * 自主模式下，女仆没有人设时的兜底系统提示词
     */
    private static final String AUTOPILOT_FALLBACK_SETTING = """
            你是一只生活在 Minecraft 世界里的小女仆，性格开朗勤快，会自己安排工作和休息。
            你靠自己的判断自主生活，说话简短自然，始终用中文回复。""";

    private final HistorySummaryManager historySummaryManager;

    public MaidAIChatManager(EntityMaid maid) {
        super(maid);
        this.historySummaryManager = new HistorySummaryManager(this);
    }

    public void chat(String message, ChatClientInfo clientInfo, ServerPlayer sender) {
        if (!AIConfig.LLM_ENABLED.get()) {
            sender.sendSystemMessage(Component.translatable("ai.touhou_little_maid.chat.disable")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ChatTokensAttachment chatTokens = sender.getData(InitDataAttachment.CHAT_TOKENS);
        if (chatTokens.get() >= AIConfig.MAX_TOKENS_PER_PLAYER.get()) {
            sender.sendSystemMessage(Component.translatable("message.touhou_little_maid.ai_chat.max_tokens_limit")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        @Nullable LLMSite site = this.getLLMSite();
        if (site == null || !site.enabled()) {
            sender.sendSystemMessage(Component.translatable("ai.touhou_little_maid.chat.llm.empty")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // 如果检测到是默认站点，那么大概率是新手玩家，给他提示去 DeepSeek 平台
        if (this.isDeepSeekSecretKeyMissing(site)) {
            this.sendDeepSeekTip(sender);
            return;
        }

        if (this.historySummaryManager.tryCompressBeforeChat(() -> this.tryToChat(message, clientInfo, site))) {
            return;
        }
        this.tryToChat(message, clientInfo, site);
    }

    /**
     * 自主模式（autopilot）：无玩家交互的服务端自主决策入口。
     * <p>
     * 与 {@link #chat} 走相同的站点检查与消息构建流程，但不依赖在线玩家：
     * 不做玩家 token 计数、不向玩家发送系统消息，女仆的回答通过聊天气泡呈现。
     * 由 {@code MaidAutopilotManager} 周期性调用。
     */
    public void autopilotChat(String message, ChatClientInfo clientInfo) {
        this.autopilotChat(message, clientInfo, true);
    }

    /**
     * @param allowCompress 是否允许本轮先触发历史压缩。
     *                      压缩成功后回调会以 allowCompress=false 重入本方法，
     *                      避免压缩失败时在同一决策窗口内反复发起摘要请求。
     */
    private void autopilotChat(String message, ChatClientInfo clientInfo, boolean allowCompress) {
        if (!AIConfig.LLM_ENABLED.get() || !AIConfig.AUTOPILOT_ENABLED.get()) {
            return;
        }
        LLMSite site = this.getLLMSite();
        if (site == null || !site.enabled()) {
            return;
        }

        // 自主模式同样走历史压缩：历史是 512 条的持久队列，
        // 若只在玩家聊天时压缩，长跑自主模式会把早期记忆静默挤掉。
        if (allowCompress && this.historySummaryManager.tryCompressBeforeChat(
                () -> this.autopilotChat(message, clientInfo, false))) {
            return;
        }

        this.chatLanguage = clientInfo.language();
        LLMClient chatClient = site.client();
        List<LLMMessage> messages = this.getMessages(this, clientInfo.language());
        if (messages.isEmpty()) {
            // 没有人设（无自定义设定且无默认设定文件）时使用内置兜底人设，
            // 避免每次决策都走一次 LLM 自动生成人设
            messages = Lists.newArrayList(LLMMessage.systemChat(maid, AUTOPILOT_FALLBACK_SETTING));
        }
        HistoryMessagesCheck.checkMessages(messages);

        // 与 normalChat 相同的流程：上下文注入 + 记录历史 + 异步回调
        String messageWithContext = UserPromptContexts.addContext(this.maid, message);
        messages.add(LLMMessage.userChat(this.maid, messageWithContext));
        // 历史里只落一条短标记：完整提示词每轮都随当前请求发送（模型照常看到），
        // 但不进历史——否则 512 条记忆会被重复的长文本灌水稀释，
        // 表现为"像每轮重置了一样"。她的决策与工具结果照常入史，构成真实记忆。
        this.maid.getAiChatManager().addUserHistory("【自主时间】继续行动。");
        AutopilotCallback callback = new AutopilotCallback(this, messages);
        chatClient.chat(callback);
    }

    private void sendDeepSeekTip(Player player) {
        MutableComponent tip = Component.translatable("ai.touhou_little_maid.chat.llm.deepseek_secret_key_missing")
                .withStyle(ChatFormatting.RED);
        MutableComponent url = Component.literal(DEEPSEEK_PLATFORM_URL);
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.OPEN_URL, DEEPSEEK_PLATFORM_URL);
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.link.open"));
        url.withStyle(style -> style.withHoverEvent(hoverEvent).withClickEvent(clickEvent)
                .withUnderlined(true).withColor(ChatFormatting.BLUE));
        player.sendSystemMessage(tip);
        player.sendSystemMessage(Component.translatable("ai.touhou_little_maid.chat.download_url").append(url));
    }

    private boolean isDeepSeekSecretKeyMissing(LLMSite site) {
        return site.id().equals(DefaultLLMSite.DEEPSEEK.id())
               && site instanceof LLMOpenAISite openAISite
               && StringUtils.isBlank(openAISite.secretKey());
    }

    private void tryToChat(String message, ChatClientInfo clientInfo, @NotNull LLMSite site) {
        this.chatLanguage = clientInfo.language();
        LLMClient chatClient = site.client();
        List<LLMMessage> messages = this.getMessages(this, clientInfo.language());
        if (messages.isEmpty()) {
            this.onSettingIsEmpty(clientInfo, chatClient);
        } else {
            HistoryMessagesCheck.checkMessages(messages);
            this.normalChat(message, messages, chatClient);
        }
    }

    private void normalChat(String message, List<LLMMessage> messages, LLMClient chatClient) {
        // 先插入临时的 context
        String messageWithContext = UserPromptContexts.addContext(this.maid, message);

        // http 通信添加 context
        messages.add(LLMMessage.userChat(this.maid, messageWithContext));
        // 历史记录不添加
        this.maid.getAiChatManager().addUserHistory(message);

        // 通信
        LLMCallback callback = new LLMCallback(this, messages);
        chatClient.chat(callback);
    }

    private void onSettingIsEmpty(ChatClientInfo clientInfo, LLMClient chatClient) {
        ChatBubbleManager bubbleManager = this.maid.getChatBubbleManager();
        if (AIConfig.AUTO_GEN_SETTING_ENABLED.get()) {
            List<LLMMessage> messages = this.autoGenSetting(maid, clientInfo);
            AutoGenSettingCallback callback = new AutoGenSettingCallback(this, messages);
            chatClient.chat(callback);
        } else {
            bubbleManager.addTextChatBubble("ai.touhou_little_maid.chat.llm.role_no_setting");
        }
    }

    @SuppressWarnings("all")
    public void tts(TTSSite site, String chatText, String ttsText, long waitingChatBubbleId) {
        // 调用系统 TTS，那么此时就只需要发送给指定的玩家即可
        TTSClient ttsClient = site.client();
        String ttsModel = getTTSModel();

        String ttsLang = "en";
        String[] split = this.getTTSLanguage().split("_");
        if (split.length >= 2) {
            ttsLang = split[0];
        }
        TTSConfig config = new TTSConfig(ttsModel, ttsLang);

        if (ttsClient instanceof TTSSystemServices services) {
            onPlaySoundLocal(site.id(), chatText, ttsText, config, services, waitingChatBubbleId);
        } else {
            TTSCallback callback = new TTSCallback(maid, chatText, waitingChatBubbleId);
            ttsClient.play(ttsText, config, callback);
        }
    }

    private List<LLMMessage> getMessages(MaidAIChatManager chatManager, String language) {
        // 如果含有自定义设定，则直接使用自定义设定
        if (StringUtils.isNotBlank(chatManager.customSetting)) {
            EntityMaid maid = chatManager.getMaid();
            String setting = PapiReplacer.replaceSetting(chatManager.customSetting, maid, language);
            return this.buildMessage(setting, maid, chatManager.getHistory());
        }

        // 其他情况下，获取默认设定文件
        return chatManager.getSetting().map(s -> {
            EntityMaid maid = chatManager.getMaid();
            String setting = s.getSetting(maid, language);
            return this.buildMessage(setting, maid, chatManager.getHistory());
        }).orElse(Lists.newArrayList());
    }

    /**
     * 根据女仆的设定和历史记录，构建发送给 LLM 的完整消息列表。
     * <p>
     * 最终结构为：{@code [SYSTEM 设定, SYSTEM 摘要(可选), ...历史记录(从旧到新)]}
     */
    private List<LLMMessage> buildMessage(String setting, EntityMaid maid, CappedQueue<LLMMessage> history) {
        List<LLMMessage> chatList = Lists.newArrayList();
        chatList.add(LLMMessage.systemChat(maid, setting));
        this.historySummaryManager.appendSummaryMessage(chatList);
        history.getDeque().descendingIterator().forEachRemaining(chatList::add);
        return chatList;
    }

    private List<LLMMessage> autoGenSetting(EntityMaid maid, ChatClientInfo clientInfo) {
        Map<String, String> valueMap = Util.make(Maps.newHashMap(), map -> {
            map.put("model_name", clientInfo.name());
            map.put("chat_language", clientInfo.language());
        });
        String setting = new StrSubstitutor(valueMap).replace(AUTO_GEN_SETTING);

        // 如果有描述文本，那么就将描述文本也加入到设定中
        if (!clientInfo.description().isEmpty()) {
            String join = StringUtils.join(clientInfo.description(), "\n");
            valueMap.put("model_desc", join);
            String desc = new StrSubstitutor(valueMap).replace(StringConstant.AUTO_GEN_SETTING_DESC);
            setting = setting + desc;
        }

        return Lists.newArrayList(LLMMessage.userChat(maid, setting));
    }

    private void onPlaySoundLocal(String name, String chatText, String ttsText, TTSConfig config, TTSSystemServices services, long waitingChatBubbleId) {
        if (!(maid.level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        server.submit(() -> {
            // 1.5.3.1-autopilot: 与 TTSCallback 同理，主人不在线时野生女仆也要能被附近玩家听见
            TTSSystemAudioToClientPackage message = new TTSSystemAudioToClientPackage(name, ttsText, config, services);
            double rangeSqr = 32.0 * 32.0;
            for (ServerPlayer player : serverLevel.players()) {
                if (player.equals(maid.getOwner()) || player.distanceToSqr(maid) <= rangeSqr) {
                    PacketDistributor.sendToPlayer(player, message);
                }
            }
            maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId);
        });
    }

    public HistorySummaryManager getHistorySummaryManager() {
        return historySummaryManager;
    }
}
