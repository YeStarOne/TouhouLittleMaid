package com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 让女仆以控制台权限（level 4）执行任意服务器命令，命令以女仆自身为执行者与坐标原点。
 * <p>
 * 输出捕获沿用 MineClawd 的成熟做法：自定义 CommandSource 收集 sendSystemMessage，
 * 另外读取命令执行期间的服务器日志增量（KubeJS 建造/验收脚本大多走 console.log）。
 * 服务器/管理员生命周期类命令（stop/op/ban 等）一律拉黑。
 */
public class ExecuteCommandTool implements ITool<String> {
    public static final String TOOL_ID = "execute_command";
    private static final String COMMAND_PARAMETER_ID = "command";

    private static final String TOOL_DESC = """
            Run a Minecraft server command as the console (permission level 4), positioned at yourself.
            - Write the command with or without a leading '/'. @s refers to you; ~ ~ ~ is relative to your position.
            - Returns the command output plus relevant server log lines.
            - You CAN: inspect the world (data get, execute if block), build/place blocks (setblock/fill with real blocks), copy structures (clone), teleport, summon animals, set time.
            - You CANNOT (blocked by safety guard): remove blocks (fill/setblock with air/water/lava/fire), clone move, kill/damage, WorldEdit commands, gamemode/gamerule/difficulty, forceload, dangerous summons, server admin commands.
            - Respect survival play: gather resources by mining/walking like a normal player; use commands mainly for building, inspecting, and getting around.""".trim();

    private static final int MAX_COMMAND_LENGTH = 512;
    private static final int MAX_OUTPUT_CHARS = 2400;
    private static final int MAX_LOG_LINES = 30;
    private static final int MAX_LOG_CHARS = 2000;
    private static final int MAX_CAPTURED_MESSAGES = 100;

    /**
     * 根命令黑名单：服务器/管理员生命周期类命令禁止女仆执行
     */
    private static final List<String> BLOCKED_ROOTS = List.of(
            "stop", "ban", "ban-ip", "pardon", "op", "deop", "kick", "whitelist",
            "reload", "kubejs", "datapack", "save-all", "save-off", "save-on",
            "gamemode", "defaultgamemode", "difficulty", "gamerule",
            "forceload", "kill", "damage", "worldborder",
            "function", "schedule", "clear", "tick", "jigsaw", "place"
    );

    /**
     * 自然地形方块：fill 的 replace 过滤器只允许这些（覆盖它们=在地形上施工）
     */
    private static final java.util.Set<String> NATURAL_REPLACEABLE = java.util.Set.of(
            "air", "cave_air", "void_air", "water", "lava", "fire", "soul_fire",
            "grass_block", "snow", "snow_block", "powder_snow", "dirt", "coarse_dirt",
            "rooted_dirt", "mud", "podzol", "mycelium", "gravel", "clay",
            "sand", "red_sand", "sandstone", "stone", "andesite", "diorite", "granite",
            "tuff", "deepslate", "calcite", "moss_block", "moss_carpet", "vine", "glow_lichen",
            "short_grass", "tall_grass", "fern", "large_fern",
            "dandelion", "poppy", "cornflower", "oxeye_daisy", "lily_of_the_valley",
            "lavender", "azure_bluet", "allium",
            "sweet_berry_bush", "sugar_cane", "wheat", "carrots", "potatoes", "beetroots",
            "bamboo", "kelp", "seagrass", "lily_pad", "brown_mushroom", "red_mushroom",
            "ice", "packed_ice", "blue_ice", "obsidian", "basalt", "blackstone",
            "netherrack", "soul_sand", "soul_soil", "end_stone", "magma_block",
            "farmland", "dirt_path", "sculk", "nether_wart_block", "warped_wart_block"
    );

    /**
     * 拆毁类目标方块：把空气/流体/火放进世界等于拆除或纵火
     */
    private static final java.util.Set<String> DESTRUCTIVE_BLOCKS = java.util.Set.of(
            "air", "cave_air", "void_air", "lava", "water", "fire", "soul_fire"
    );

    /**
     * 危险召唤实体（爆炸/高破坏）
     */
    private static final java.util.Set<String> DANGEROUS_SUMMONS = java.util.Set.of(
            "tnt", "primed_tnt", "fireball", "small_fireball", "dragon_fireball",
            "wither_skull", "wither", "ender_dragon", "end_crystal", "lightning_bolt"
    );

    /**
     * WorldEdit 破坏性根命令（双斜杠命令另有前缀检查兜底）
     */
    private static final java.util.Set<String> WORLDEDIT_DESTRUCTIVE = java.util.Set.of(
            "set", "replace", "cut", "walls", "hollow", "sphere", "cyl", "hcyl",
            "pyramid", "undo", "redo", "naturalize", "smooth", "overlay",
            "removenear", "removeabove", "removebelow", "drain", "fixwater", "fixlava", "fillr"
    );

    private static final Codec<String> CODEC = Codec.STRING.fieldOf(COMMAND_PARAMETER_ID).codec();

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
        StringParameter command = StringParameter.create();
        command.setDescription("The command to run, e.g. \"execute if block ~ ~-1 ~ minecraft:farmland\".");
        root.addProperties(COMMAND_PARAMETER_ID, command);
        return root;
    }

    @Override
    public Codec<String> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, String result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (!(maid.level instanceof ServerLevel serverLevel)) {
            return callback.addToolResult("Can only execute commands on the server side.", toolId);
        }

        String command = result == null ? "" : result.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return callback.addToolResult("Command is empty. Provide a Minecraft command to run.", toolId);
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return callback.addToolResult("Command too long (max %d chars).".formatted(MAX_COMMAND_LENGTH), toolId);
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (BLOCKED_ROOTS.contains(root)) {
            return callback.addToolResult(
                    "Command '/%s' is blocked for safety (server/admin lifecycle).".formatted(root), toolId);
        }
        String guardMessage = checkDestructive(command, root);
        if (guardMessage != null) {
            return callback.addToolResult(guardMessage, toolId);
        }

        MinecraftServer server = serverLevel.getServer();
        CapturingCommandSource capture = new CapturingCommandSource();
        CommandSourceStack source = server.createCommandSourceStack()
                .withEntity(maid)
                .withPosition(maid.position())
                .withLevel(serverLevel)
                .withSource(capture);

        long logSizeBefore = currentLogSize();
        int commandResult;
        try {
            commandResult = server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            String message = "Command syntax error: " + e.getMessage();
            String captured = capture.joined();
            if (!captured.isEmpty()) {
                message += "\n" + captured;
            }
            return callback.addToolResult(message, toolId);
        } catch (Exception e) {
            return callback.addToolResult(
                    "Command execution failed: %s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()), toolId);
        }

        StringBuilder out = new StringBuilder();
        out.append("Command: /").append(command).append("\n");
        out.append("Result code: ").append(commandResult);
        out.append(commandResult > 0 ? " (success)" : " (0 - no direct output, check log below)").append("\n");
        String captured = capture.joined();
        if (!captured.isEmpty()) {
            out.append("Output:\n").append(captured).append("\n");
        }
        String logDelta = readLogDelta(logSizeBefore);
        if (!logDelta.isEmpty()) {
            out.append("Server log (new lines during execution):\n").append(logDelta).append("\n");
        }

        String text = out.toString().trim();
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS) + "\n...(truncated)";
        }
        TouhouLittleMaid.LOGGER.debug("[Tool] execute_command by maid {}: /{} -> code {}",
                maid.getUUID(), command, commandResult);
        return callback.addToolResult(text, toolId);
    }

    /**
     * 破坏性护栏：允许大创造（放实体方块/建造/复制），禁止拆除类操作。
     * <p>
     * 检查维度：
     * <ol>
     *   <li>fill/setblock 的目标方块是空气/流体/火 → 拦截（等于拆除/纵火）</li>
     *   <li>fill ... replace &lt;非空气方块&gt; 的大面积替换 → 拦截（覆盖他人建筑）</li>
     *   <li>clone 的 mode 是 move → 拦截（搬走即拆走）；force 且源区域大 → 拦截（跨区大量生成）</li>
     *   <li>summon 危险实体（tnt/凋灵/末影龙等）→ 拦截</li>
     *   <li>WorldEdit 破坏性命令（//set //cut 等）→ 拦截</li>
     * </ol>
     *
     * @return 拦截原因；允许执行时返回 null
     */
    private static String checkDestructive(String command, String root) {
        String lower = command.toLowerCase(Locale.ROOT);

        // WorldEdit 双斜杠命令：只放行无破坏的查询类
        if (lower.startsWith("//")) {
            String weRoot = lower.substring(2).split("\\s+", 2)[0];
            if (WORLDEDIT_DESTRUCTIVE.contains(weRoot)) {
                return "Blocked: WorldEdit '%s' can destroy builds. Use vanilla commands for building instead.".formatted(weRoot);
            }
            return null;
        }
        if ("/undo".equals(command.trim()) || "undo".equals(root)) {
            return "Blocked: undo can revert other people's recent builds.";
        }

        // fill：目标方块是空气/流体/火 → 拆除；replace 过滤器含建筑方块 → 覆盖他人建筑
        if ("fill".equals(root)) {
            String[] parts = lower.split("\\s+");
            String replaceBlock = null;
            for (int i = 0; i < parts.length; i++) {
                if ("replace".equals(parts[i]) && i + 1 < parts.length) {
                    replaceBlock = parts[i + 1];
                }
            }
            String fillBlock = extractFillBlock(parts);
            if (fillBlock != null && isDestructiveBlock(fillBlock)) {
                return "Blocked: filling with '%s' removes blocks or spreads fluid/fire. Placing air/water/lava/fire is not allowed.".formatted(fillBlock);
            }
            if (replaceBlock == null) {
                return "Blocked: fill without a 'replace <filter>' clause overwrites every block in the box, including builds. Add a filter, e.g. 'fill ... minecraft:stone replace minecraft:air' places blocks only on empty spots.";
            }
            String normalized = replaceBlock.replace("minecraft:", "").split("\\[")[0];
            if (!NATURAL_REPLACEABLE.contains(normalized)) {
                return "Blocked: 'fill ... replace %s' would overwrite built structures. Only natural terrain (grass/stone/dirt/water/air/farmland...) may be replaced. To build, place blocks on empty spots: 'fill ... replace air'.".formatted(replaceBlock);
            }
            return null;
        }

        // setblock：放空气/流体/火 → 拆除
        if ("setblock".equals(root) || "block".equals(root)) {
            String[] parts = lower.split("\\s+");
            if (parts.length >= 5) {
                String block = parts[4];
                if (isDestructiveBlock(block)) {
                    return "Blocked: setblock with '%s' removes blocks or spreads fluid/fire. Placing air/water/lava/fire is not allowed.".formatted(block);
                }
            }
            return null;
        }

        // clone：move 模式 = 搬走（拆走原件）；force 模式 = 强制加载远区块（可能压垮服务器）
        if ("clone".equals(root)) {
            String[] parts = lower.split("\\s+");
            boolean move = java.util.Arrays.asList(parts).contains("move");
            boolean force = java.util.Arrays.asList(parts).contains("force");
            if (move) {
                return "Blocked: 'clone ... move' relocates (destroys) the original structure. Use clone without move (copy) instead.";
            }
            if (force) {
                return "Blocked: 'clone ... force' force-loads far chunks and can crash the server. Clone only within currently loaded areas (omit force).";
            }
            return null;
        }

        // summon：危险实体
        if ("summon".equals(root)) {
            String[] parts = lower.split("\\s+");
            if (parts.length >= 2) {
                String entity = parts[1].replace("minecraft:", "");
                if (DANGEROUS_SUMMONS.contains(entity)) {
                    return "Blocked: summoning '%s' is dangerous (explosions/terrain damage).".formatted(entity);
                }
            }
            return null;
        }

        // setblock/fill 之外，execute ... run <破坏命令> 的嵌套：递归检查 run 之后的部分
        if ("execute".equals(root)) {
            int runIdx = lower.indexOf(" run ");
            if (runIdx >= 0) {
                String nested = command.substring(runIdx + 5).trim();
                String nestedRoot = nested.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                if (BLOCKED_ROOTS.contains(nestedRoot)) {
                    return "Blocked: nested '/%s' inside execute is not allowed.".formatted(nestedRoot);
                }
                return checkDestructive(nested, nestedRoot);
            }
            return null;
        }

        return null;
    }

    /**
     * 从 fill 命令参数中提取目标方块名（fill x1 y1 z1 x2 y2 z2 &lt;block&gt; [replace &lt;filter&gt;]）
     */
    private static String extractFillBlock(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("~") || part.matches("-?\\d+")) {
                continue;
            }
            if ("replace".equals(part)) {
                return null;
            }
            // 第一个非坐标参数就是目标方块
            return part;
        }
        return null;
    }

    private static boolean isDestructiveBlock(String block) {
        String normalized = block.replace("minecraft:", "").split("\\[")[0];
        return DESTRUCTIVE_BLOCKS.contains(normalized);
    }

    @Override
    public Component invocationSummaryComponent(String result) {
        String command = result == null ? "" : result.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.length() > 40) {
            command = command.substring(0, 40) + "...";
        }
        return Component.literal("Run command: /" + command);
    }

    /**
     * 收集命令执行期间的服务器日志增量（KubeJS 脚本大量输出走 console.log → 落 latest.log）
     */
    private static String readLogDelta(long startSize) {
        try {
            Path logPath = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
            if (!Files.exists(logPath)) {
                return "";
            }
            long size = Files.size(logPath);
            if (size <= startSize) {
                return "";
            }
            long toRead = Math.min(size - startSize, 16_384L);
            byte[] buf = new byte[(int) toRead];
            try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "r")) {
                raf.seek(Math.max(startSize, size - toRead));
                raf.readFully(buf);
            }
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = text.lines()
                    .map(ExecuteCommandTool::stripLogPrefix)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                return "";
            }
            List<String> tail = lines.size() > MAX_LOG_LINES
                    ? lines.subList(lines.size() - MAX_LOG_LINES, lines.size())
                    : lines;
            String joined = String.join("\n", tail);
            if (joined.length() > MAX_LOG_CHARS) {
                joined = joined.substring(joined.length() - MAX_LOG_CHARS);
            }
            return joined;
        } catch (Exception e) {
            return "";
        }
    }

    private static long currentLogSize() {
        try {
            Path logPath = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
            return Files.exists(logPath) ? Files.size(logPath) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 去掉 "[时间] [线程/级别] [logger/]: " 前缀，保留正文
     */
    private static String stripLogPrefix(String line) {
        int idx = -1;
        for (int i = 0; i < 3; i++) {
            int next = line.indexOf("] ", idx + 1);
            if (next < 0) {
                return line;
            }
            idx = next;
        }
        return line.substring(idx + 2);
    }

    /**
     * 捕获命令反馈的 CommandSource 实现（sendSuccess/sendFailure 最终都走 sendSystemMessage）
     */
    private static final class CapturingCommandSource implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            if (message != null && messages.size() < MAX_CAPTURED_MESSAGES) {
                messages.add(message);
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        public String joined() {
            return messages.stream()
                    .map(Component::getString)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("")
                    .trim();
        }
    }
}
