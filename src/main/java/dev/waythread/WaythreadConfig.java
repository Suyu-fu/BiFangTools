package dev.waythread;

import java.util.regex.Pattern;
import net.neoforged.neoforge.common.ModConfigSpec;

final class WaythreadConfig {
    static final String DEFAULT_TRAIL_COLOR = "#38E3FF";
    static final String DEFAULT_ANCHOR_COLOR = "#FFB036";
    private static final Pattern RGB_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue LOAD_DISTANCE;
    static final ModConfigSpec.DoubleValue RECORD_POINT_SPACING;
    static final ModConfigSpec.IntValue MAX_RECORDED_POINTS;
    static final ModConfigSpec.DoubleValue RETURN_ERASE_DISTANCE;
    static final ModConfigSpec.EnumValue<TrailParticles.DisplayMode> DISPLAY_MODE;
    static final ModConfigSpec.EnumValue<TrailParticles.Style> PARTICLE_STYLE;
    static final ModConfigSpec.EnumValue<TrailParticles.Density> PARTICLE_DENSITY;
    static final ModConfigSpec.ConfigValue<String> TRAIL_COLOR;
    static final ModConfigSpec.ConfigValue<String> ANCHOR_COLOR;
    static final ModConfigSpec.BooleanValue SHOW_RECORDING_DIRECTION;
    static final ModConfigSpec.BooleanValue SHOW_ARRIVAL_MESSAGE;
    static final ModConfigSpec.BooleanValue CLEAR_ON_ARRIVAL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        LOAD_DISTANCE = builder
                .comment("Distance in blocks from the player at which nearby trail segments are generated and displayed.")
                .translation("waythread.configuration.loadDistance")
                .defineInRange("loadDistance", 18, 4, 64);
        RECORD_POINT_SPACING = builder
                .comment("Travel distance in blocks before recording another position.")
                .translation("waythread.configuration.recordPointSpacing")
                .defineInRange("recordPointSpacing", 5.0, 2.0, 10.0);
        MAX_RECORDED_POINTS = builder
                .comment("Maximum number of recorded points, including the starting point.")
                .translation("waythread.configuration.maxRecordedPoints")
                .defineInRange("maxRecordedPoints", 20_000, 2, 200_000);
        RETURN_ERASE_DISTANCE = builder
                .comment("Distance in blocks for matching a recorded point and reaching the start in return mode.")
                .translation("waythread.configuration.returnEraseDistance")
                .defineInRange("returnEraseDistance", 2.0, 0.5, 6.0);
        DISPLAY_MODE = builder
                .comment("Show a connected trail line or just the recorded waypoints.")
                .translation("waythread.configuration.displayMode")
                .defineEnum("displayMode", TrailParticles.DisplayMode.LINE);
        PARTICLE_STYLE = builder
                .comment("Visual preset for the trail line and start marker.")
                .translation("waythread.configuration.particleStyle")
                .defineEnum("particleStyle", TrailParticles.Style.DUST);
        PARTICLE_DENSITY = builder
                .comment("Spacing and count of particles in Trail Line mode.")
                .translation("waythread.configuration.particleDensity")
                .defineEnum("particleDensity", TrailParticles.Density.STANDARD);
        TRAIL_COLOR = builder
                .comment("RGB color of the trail particles in #RRGGBB format.")
                .translation("waythread.configuration.trailColor")
                .define("trailColor", DEFAULT_TRAIL_COLOR, WaythreadConfig::isRgbColor);
        ANCHOR_COLOR = builder
                .comment("RGB color of the start marker particles in #RRGGBB format.")
                .translation("waythread.configuration.anchorColor")
                .define("anchorColor", DEFAULT_ANCHOR_COLOR, WaythreadConfig::isRgbColor);
        SHOW_RECORDING_DIRECTION = builder
                .comment("Show a moving highlight toward the end of the trail while recording.")
                .translation("waythread.configuration.showRecordingDirection")
                .define("showRecordingDirection", true);
        SHOW_ARRIVAL_MESSAGE = builder
                .comment("Show a message and play a chime when you return to the trail's start.")
                .translation("waythread.configuration.showArrivalMessage")
                .define("showArrivalMessage", true);
        CLEAR_ON_ARRIVAL = builder
                .comment("Clear the start marker when you finish returning.")
                .translation("waythread.configuration.clearOnArrival")
                .define("clearOnArrival", true);
        SPEC = builder.build();
    }

    private WaythreadConfig() {
    }

    private static boolean isRgbColor(Object value) {
        return value instanceof String color && RGB_COLOR.matcher(color).matches();
    }
}
