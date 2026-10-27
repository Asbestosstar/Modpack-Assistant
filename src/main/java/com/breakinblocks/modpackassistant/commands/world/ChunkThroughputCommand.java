package com.breakinblocks.modpackassistant.commands.world;

import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
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
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkThroughputCommand {
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.00");

    private ChunkThroughputCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunkthroughput")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("radius", IntegerArgumentType.integer(0))
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

        if (radius > MAConfig.maxScanRadius()) {
            int span = 2 * radius + 1;
            CommandResults.fail(source, Messages.RADIUS_TOO_LARGE.get(radius, MAConfig.maxScanRadius(), span * span, "max_scan_radius"));
            return 0;
        }

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

        AtomicInteger generated = new AtomicInteger();
        AtomicInteger alreadyLoaded = new AtomicInteger();
        AtomicLong totalGenNanos = new AtomicLong();

        Run run = new Run(source, "chunk throughput test", level.dimension());
        for (ChunkPos chunk : region.chunks()) {
            run.job(() -> {
                long startNanos = System.nanoTime();
                if (ChunkAccessor.isLoaded(level, chunk)) {
                    alreadyLoaded.incrementAndGet();
                } else {
                    ChunkAccessor.withChunk(level, chunk, loaded -> {
                        generated.incrementAndGet();
                        return null;
                    });
                }
                long elapsedNanos = System.nanoTime() - startNanos;
                totalGenNanos.addAndGet(elapsedNanos);
            });
        }
        run.onComplete(finished -> {
            printSummary(finished, region, generated.get(), alreadyLoaded.get(), totalGenNanos.get());
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.THROUGHPUT_START.get(region.spanText(), region.chunkCount(), center.x, center.z));
        return run.total();
    }

    private static void printSummary(Run run, RegionGeometry region, int generated, int alreadyLoaded, long totalGenNanos) {
        double genSeconds = totalGenNanos / 1_000_000_000.0;

        run.message(Component.empty());
        run.message(Messages.THROUGHPUT_HEADER.get(region.chunkCount(), generated, alreadyLoaded));

        if (generated > 0) {
            double perChunk = genSeconds / generated;
            double chunksPerSec = genSeconds > 0 ? generated / genSeconds : 0;
            run.message(Messages.THROUGHPUT_GENERATED.get(
                    DECIMAL.format(perChunk),
                    DECIMAL.format(chunksPerSec)));
        }

        run.message(Messages.THROUGHPUT_TOTAL.get(Run.formatSeconds(totalGenNanos / 1_000_000_000L)));
        run.message(Messages.THROUGHPUT_ELAPSED.get(run.elapsed()));
    }
}
