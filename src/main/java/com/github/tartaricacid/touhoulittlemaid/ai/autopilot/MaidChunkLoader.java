package com.github.tartaricacid.touhoulittlemaid.ai.autopilot;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 让自主行动的女仆保持自己周围的区块加载。
 * <p>
 * 借鉴 aiplayermod 的 AnchorManager（自定义 TicketType + addRegionTicket + 周期重定位），
 * 但做了三处改良：① 不用 setChunkForced（那会把强载写进存档 forceload 表，重启不自清）；
 * ② region ticket 数据不持久化，只有锚点坐标写 SavedData 用于重启自举；
 * ③ ticket radius=3（ticket level 30 ≤ 31 = ENTITY_TICKING），保证女仆所在 3×3 区块真正实体 tick。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link MaidTickEvent}：女仆每 tick 检查，跨区块时把票从旧位置迁到新位置（位置未变零开销）。</li>
 *   <li>{@link ServerTickEvent.Post}：① 启动后第一次自举——按 SavedData 锚点先把区块加载起来，
 *       否则重启后女仆所在区块无人加载，她永远不 tick、票永远挂不上（鸡生蛋死锁）；
 *       ② 每 100 tick 清扫一次失效锚点（女仆已死/卸载/换维度）并回收票。</li>
 *   <li>LLM 或 Autopilot 关闭时整体失效，并清空所有票。</li>
 * </ul>
 */
@EventBusSubscriber(modid = TouhouLittleMaid.MOD_ID)
public final class MaidChunkLoader {
    /** region ticket 半径 3 → ticket level 30，3×3 核心实体 tick（5×5 方块 tick / 7×7 加载）。 */
    private static final int RADIUS = 3;
    private static final int SWEEP_INTERVAL_TICKS = 100;

    public static final TicketType<UUID> MAID_TICKET = TicketType.create(
            "touhou_little_maid_autopilot",
            Comparator.comparingLong(UUID::getMostSignificantBits).thenComparingLong(UUID::getLeastSignificantBits));

    /** 当前持有票的女仆：UUID → 锚点（维度 + 区块）。仅服务端线程访问。 */
    private static final Map<UUID, Anchor> HELD = new HashMap<>();
    private static MinecraftServer lastServer;
    private static boolean selfBootDone;
    private static int sweepTimer;

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel level)) {
            return;
        }
        if (!maid.isAlive()) {
            releaseFrom(maid.getUUID(), level.getServer(), true);
            return;
        }
        if (!isAutopilotActive()) {
            return;
        }
        UUID id = maid.getUUID();
        ChunkPos now = maid.chunkPosition();
        Anchor held = HELD.get(id);
        if (held != null && held.dimension == level.dimension() && held.pos.equals(now)) {
            return;
        }
        if (held != null) {
            removeTicket(level.getServer(), held, id);
        }
        level.getChunkSource().addRegionTicket(MAID_TICKET, now, RADIUS, id);
        HELD.put(id, new Anchor(level.dimension(), now));
        touchAnchor(level, id, new Anchor(level.dimension(), now));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (lastServer != server) {
            lastServer = server;
            HELD.clear();
            selfBootDone = false;
        }
        if (!isAutopilotActive()) {
            // 关闭状态：清残留（包括上次运行留下的）
            if (!HELD.isEmpty()) {
                HELD.forEach((id, anchor) -> removeTicket(server, anchor, id));
                HELD.clear();
            }
            return;
        }
        if (!selfBootDone) {
            selfBootDone = true;
            selfBoot(server);
        }
        if (++sweepTimer >= SWEEP_INTERVAL_TICKS) {
            sweepTimer = 0;
            sweep(server);
        }
    }

    /** 重启自举：按 SavedData 里的锚点先加载区块，女仆实体进入 tick 后由 onMaidTick 原样接管。 */
    private static void selfBoot(MinecraftServer server) {
        AnchorData data = anchorData(server);
        if (data.anchors.isEmpty()) {
            return;
        }
        int loaded = 0;
        for (Map.Entry<UUID, Anchor> e : data.anchors.entrySet()) {
            ServerLevel level = server.getLevel(e.getValue().dimension());
            if (level == null) {
                continue;
            }
            level.getChunkSource().addRegionTicket(MAID_TICKET, e.getValue().pos(), RADIUS, e.getKey());
            HELD.put(e.getKey(), e.getValue());
            loaded++;
        }
        TouhouLittleMaid.LOGGER.info("[MaidChunkLoader] self-boot: {} maid anchor(s) loading", loaded);
    }

    /**
     * 周期清扫：回收失效票（女仆死亡/卷回/维度消失）。
     * <p>
     * 实体"暂时查不到"（区块尚未异步加载完、或女仆被卸载进未加载区块）时<b>必须保留票</b>——
     * 票正是让区块保持加载的原因；贸然清票会形成"清票 → 区块卸载 → 实体更加载不出来"的死锁。
     * 真正的删除（/kill、女仆之珠收回）由 {@code EntityMaid.remove} 钩子调 {@link #releaseFrom}
     * 立即清理，不留残余。
     */
    private static void sweep(MinecraftServer server) {
        AnchorData data = null;
        boolean dataDirty = false;
        var it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Anchor> entry = it.next();
            Anchor anchor = entry.getValue();
            ServerLevel level = server.getLevel(anchor.dimension());
            boolean dead = false;
            if (level == null) {
                dead = true; // 维度没了，票随维度一起消失
            } else {
                Entity e = level.getEntity(entry.getKey());
                if (e instanceof EntityMaid maid && !maid.isAlive()) {
                    dead = true;
                    level.getChunkSource().removeRegionTicket(MAID_TICKET, anchor.pos(), RADIUS, entry.getKey());
                }
                // e == null：实体暂不可见（未加载/被卸载）→ 保留票和锚点
            }
            if (dead) {
                it.remove();
                if (data == null) {
                    data = anchorData(server);
                }
                if (data.anchors.remove(entry.getKey()) != null) {
                    dataDirty = true;
                }
            }
        }
        if (dataDirty) {
            data.setDirty();
        }
    }

    /**
     * 女仆从世界中移除时回收票。
     *
     * @param clearAnchor true 时连持久化锚点一起删除。区块卸载（UNLOADED_TO_CHUNK）和跨维度迁移
     *                    （CHANGED_DIMENSION）时传 false——那两种情况下女仆会立刻以新身份重新出现，
     *                    锚点留着让她重新 tick 后自行更新；击杀/收回传 true。
     */
    public static void releaseFrom(UUID id, MinecraftServer server, boolean clearAnchor) {
        Anchor held = HELD.remove(id);
        if (held != null) {
            removeTicket(server, held, id);
        }
        if (clearAnchor && server != null) {
            AnchorData data = anchorData(server);
            if (data.anchors.remove(id) != null) {
                data.setDirty();
            }
        }
    }

    private static void removeTicket(MinecraftServer server, Anchor anchor, UUID id) {
        ServerLevel level = server.getLevel(anchor.dimension());
        if (level != null) {
            level.getChunkSource().removeRegionTicket(MAID_TICKET, anchor.pos(), RADIUS, id);
        }
    }

    private static void touchAnchor(ServerLevel level, UUID id, Anchor anchor) {
        AnchorData data = anchorData(level.getServer());
        Anchor prev = data.anchors.get(id);
        if (prev != null && prev.equals(anchor)) {
            return;
        }
        data.anchors.put(id, anchor);
        data.setDirty();
    }

    private static boolean isAutopilotActive() {
        return AIConfig.LLM_ENABLED.get() && AIConfig.AUTOPILOT_ENABLED.get()
                && AIConfig.AUTOPILOT_CHUNK_LOADING.get();
    }

    private static AnchorData anchorData(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(AnchorData.factory(), AnchorData.FILE_ID);
    }

    /** 女仆锚点：登记维度 + 所在区块。 */
    public record Anchor(ResourceKey<Level> dimension, ChunkPos pos) {
    }

    /** 持久化锚点表（仅用于重启自举，ticket 本身不持久化）。 */
    public static class AnchorData extends SavedData {
        public static final String FILE_ID = "tlm_maid_anchors";
        public final Map<UUID, Anchor> anchors = new HashMap<>();

        public static SavedData.Factory<AnchorData> factory() {
            return new SavedData.Factory<>(AnchorData::new, AnchorData::load, DataFixTypes.SAVED_DATA_MAP_DATA);
        }

        public static AnchorData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
            AnchorData data = new AnchorData();
            CompoundTag list = tag.getCompound("Anchors");
            for (String key : list.getAllKeys()) {
                try {
                    UUID id = UUID.fromString(key);
                    CompoundTag a = list.getCompound(key);
                    if (a.isEmpty()) {
                        continue;
                    }
                    ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                            ResourceLocation.parse(a.getString("Dim")));
                    ChunkPos pos = new ChunkPos(a.getInt("X"), a.getInt("Z"));
                    data.anchors.put(id, new Anchor(dim, pos));
                } catch (Exception ignored) {
                    // 单条坏数据不影响其余
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
            CompoundTag list = new CompoundTag();
            anchors.forEach((id, anchor) -> {
                CompoundTag a = new CompoundTag();
                a.putString("Dim", anchor.dimension().location().toString());
                a.putInt("X", anchor.pos().x);
                a.putInt("Z", anchor.pos().z);
                list.put(id.toString(), a);
            });
            tag.put("Anchors", list);
            return tag;
        }
    }
}
