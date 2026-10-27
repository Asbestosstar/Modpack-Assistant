package com.breakinblocks.modpackassistant.commands.world;

import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.jobs.ChunkAccessor;
import com.breakinblocks.modpackassistant.jobs.RegionGeometry;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

public final class ChunkThroughputCommand {
    public static final int MAX_RADIUS = 5000;
    private static final int IN_FLIGHT = 1024;
    private static final int SCAN_PER_STEP = 4096;
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.00");

    private ChunkThroughputCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunkthroughput")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .executes(context -> throughput(context, Integer.MIN_VALUE, Integer.MIN_VALUE))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .executes(context -> throughput(context, IntegerArgumentType.getInteger(context, "x"), Integer.MIN_VALUE))
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> throughput(context,
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z"))))));
    }

    private static int throughput(CommandContext<CommandSourceStack> context, int xArg, int zArg) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int radius = IntegerArgumentType.getInteger(context, "radius");

        ChunkPos center;
        if (xArg != Integer.MIN_VALUE && zArg != Integer.MIN_VALUE) {
            center = new ChunkPos(xArg >> 4, zArg >> 4);
        } else if (source.getEntity() instanceof ServerPlayer player) {
            center = player.chunkPosition();
        } else {
            BlockPos spawn = level.getSharedSpawnPos();
            center = new ChunkPos(spawn);
        }

        RegionGeometry region = new RegionGeometry(center, radius);
        Generation generation = new Generation(level, region);

        Run run = new Run(source, "chunk throughput test", level.dimension());
        run.repeat(generation::step);
        run.onComplete(finished -> printSummary(finished, region, generation));
        run.onCancel(finished -> generation.releaseAll());

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.THROUGHPUT_START.get(region.spanText(), region.chunkCount(), center.x, center.z));
        return region.chunkCount();
    }

    private static void printSummary(Run run, RegionGeometry region, Generation generation) {
        double wallSeconds = generation.wallNanos() / 1_000_000_000.0;

        run.message(Component.empty());
        run.message(Messages.THROUGHPUT_HEADER.get(region.chunkCount(), generation.generated, generation.alreadyLoaded));

        if (generation.generated > 0 && wallSeconds > 0) {
            double chunksPerSec = generation.generated / wallSeconds;
            int threads = workerThreads();
            run.message(Messages.THROUGHPUT_GENERATED.get(DECIMAL.format(chunksPerSec)));
            run.message(Messages.THROUGHPUT_PER_THREAD.get(DECIMAL.format(chunksPerSec / threads), threads));
        }

        run.message(Messages.THROUGHPUT_ELAPSED.get(DECIMAL.format(wallSeconds)));
    }

    private static int workerThreads() {
        ExecutorService executor = Util.backgroundExecutor();
        return executor instanceof ForkJoinPool pool ? pool.getParallelism() : Runtime.getRuntime().availableProcessors();
    }

    private static final class Generation {
        private final ServerLevel level;
        private final RegionGeometry region;
        private final ArrayDeque<ChunkPos> inFlight = new ArrayDeque<>();
        private int cursorX;
        private int cursorZ;
        private boolean exhausted;
        private int generated;
        private int alreadyLoaded;
        private long startNanos = -1;
        private long endNanos;

        Generation(ServerLevel level, RegionGeometry region) {
            this.level = level;
            this.region = region;
            this.cursorX = region.center().x - region.radius();
            this.cursorZ = region.center().z - region.radius();
        }

        boolean step() {
            long now = System.nanoTime();
            if (startNanos < 0) {
                startNanos = now;
            }

            for (int pending = inFlight.size(); pending > 0; pending--) {
                ChunkPos pos = inFlight.poll();
                if (ChunkAccessor.isLoaded(level, pos)) {
                    ChunkAccessor.releaseGeneration(level, pos);
                    generated++;
                } else {
                    inFlight.add(pos);
                }
            }

            for (int scanned = 0; !exhausted && inFlight.size() < IN_FLIGHT && scanned < SCAN_PER_STEP; scanned++) {
                ChunkPos pos = new ChunkPos(cursorX, cursorZ);
                advance();
                if (ChunkAccessor.isLoaded(level, pos)) {
                    alreadyLoaded++;
                } else {
                    ChunkAccessor.requestGeneration(level, pos);
                    inFlight.add(pos);
                }
            }

            if (exhausted && inFlight.isEmpty()) {
                endNanos = System.nanoTime();
                return true;
            }
            return false;
        }

        void releaseAll() {
            for (ChunkPos pos : inFlight) {
                ChunkAccessor.releaseGeneration(level, pos);
            }
            inFlight.clear();
        }

        long wallNanos() {
            return endNanos - startNanos;
        }

        private void advance() {
            int max = region.center().x + region.radius();
            cursorZ++;
            if (cursorZ > region.center().z + region.radius()) {
                cursorZ = region.center().z - region.radius();
                cursorX++;
                if (cursorX > max) {
                    exhausted = true;
                }
            }
        }
    }
}
