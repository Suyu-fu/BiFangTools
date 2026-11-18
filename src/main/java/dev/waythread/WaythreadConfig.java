package dev.waythread;

import java.util.regex.Pattern;
import net.neoforged.neoforge.common.ModConfigSpec;

final class WaythreadConfig {
    static final String DEFAULT_TRAIL_COLOR = "#38E3FF";
    static final String DEFAULT_ANCHOR_COLOR = "#FFB036";
    static final String TRAIL_PARTICLE_LIMIT_KEY = "trailParticleLimit";
    static final int MAX_TRAIL_PARTICLE_LIMIT = 2048;
    private static final Pattern RGB_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue LOAD_DISTANCE;
    static final ModConfigSpec.DoubleValue RECORD_POINT_SPACING;
    static final ModConfigSpec.IntValue MAX_RECORDED_POINTS;
    static final ModConfigSpec.DoubleValue RETURN_ERASE_DISTANCE;
    static final ModConfigSpec.EnumValue<TrailParticles.DisplayMode> DISPLAY_MODE;
    static final ModConfigSpec.EnumValue<TrailParticles.Style> PARTICLE_STYLE;
    static final ModConfigSpec.DoubleValue TRAIL_PARTICLE_SPACING;
    static final ModConfigSpec.IntValue TRAIL_PARTICLE_LIMIT;
    static final ModConfigSpec.ConfigValue<String> TRAIL_COLOR;
    static final ModConfigSpec.BooleanValue ANCHOR_FOLLOWS_TRAIL_COLOR;
    static final ModConfigSpec.ConfigValue<String> ANCHOR_COLOR;
    static final ModConfigSpec.EnumValue<AnchorRenderer.Appearance> ANCHOR_APPEARANCE;
    static final ModConfigSpec.BooleanValue ANCHOR_SEE_THROUGH;
    static final ModConfigSpec.IntValue ANCHOR_SEE_THROUGH_DISTANCE;
    static final ModConfigSpec.BooleanValue SHOW_RECORDING_DIRECTION;
    static final ModConfigSpec.BooleanValue SHOW_RETURN_PROGRESS_BAR;
    static final ModConfigSpec.BooleanValue SHOW_RETURN_PERCENTAGE;
    static final ModConfigSpec.IntValue RETURN_PROGRESS_FONT_SIZE;
    static final ModConfigSpec.BooleanValue SHOW_ARRIVAL_MESSAGE;
    static final ModConfigSpec.BooleanValue CLEAR_ON_ARRIVAL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        LOAD_DISTANCE = builder
                .comment("Distance in blocks from the player at which nearby trail segments are generated and displayed.")
                .translation("waythread.configuration.loadDistance")
                .defineInRange("loadDistance", 40, 4, 64);
        RECORD_POINT_SPACING = builder
                .comment("Travel distance in blocks before recording another position.")
                .translation("waythread.configuration.recordPointSpacing")
                .defineInRange("recordPointSpacing", 5.0, 2.0, 10.0);
        MAX_RECORDED_POINTS = builder
                .comment("Maximum number of recorded points, including the starting point.")
                .translation("waythread.configuration.maxRecordedPoints")
                .defineInRange("maxRecordedPoints", 8_192, 1_024, 16_384);
        RETURN_ERASE_DISTANCE = builder
                .comment("Distance in blocks for matching a recorded point and reaching the start in return mode.")
                .translation("waythread.configuration.returnEraseDistance")
                .defineInRange("returnEraseDistance", 2.0, 1.0, 16.0);
        DISPLAY_MODE = builder
                .comment("Show a connected trail line or just the recorded waypoints.")
                .translation("waythread.configuration.displayMode")
                .defineEnum("displayMode", TrailParticles.DisplayMode.LINE);
        PARTICLE_STYLE = builder
                .comment("Visual preset for the trail line and start marker.")
                .translation("waythread.configuration.particleStyle")
                .defineEnum("particleStyle", TrailParticles.Style.GLOW);
        TRAIL_PARTICLE_SPACING = builder
                .comment("Distance in blocks between particles in Trail Line mode.")
                .translation("waythread.configuration.trailParticleSpacing")
                .defineInRange("trailParticleSpacing", 0.7, 0.1, 2.0);
        TRAIL_PARTICLE_LIMIT = builder
                .comment("Maximum trail particles per refresh. Zero means unlimited.")
                .translation("waythread.configuration.trailParticleLimit")
                .defineInRange(TRAIL_PARTICLE_LIMIT_KEY, 90, 0, MAX_TRAIL_PARTICLE_LIMIT);
        TRAIL_COLOR = builder
                .comment("RGB color of the trail particles in #RRGGBB format.")
                .translation("waythread.configuration.trailColor")
                .define("trailColor", DEFAULT_TRAIL_COLOR, WaythreadConfig::isRgbColor);
        ANCHOR_FOLLOWS_TRAIL_COLOR = builder
                .comment("Use the trail color for the start marker.")
                .translation("waythread.configuration.anchorFollowsTrailColor")
                .define("anchorFollowsTrailColor", true);
        ANCHOR_COLOR = builder
                .comment("Custom start marker color in #RRGGBB format, used when matching the trail color is disabled.")
                .translation("waythread.configuration.anchorColor")
                .define("anchorColor", DEFAULT_ANCHOR_COLOR, WaythreadConfig::isRgbColor);
        ANCHOR_APPEARANCE = builder
                .comment("Choose particles, a floating orb, or a floating cube for the start marker.")
                .translation("waythread.configuration.anchorAppearance")
                .defineEnum("anchorAppearance", AnchorRenderer.Appearance.CUBE);
        ANCHOR_SEE_THROUGH = builder
                .comment("Show the start marker itself through blocks.")
                .translation("waythread.configuration.anchorSeeThrough")
                .define("anchorSeeThrough", false);
        ANCHOR_SEE_THROUGH_DISTANCE = builder
                .comment("Maximum distance in blocks for the see-through start marker.")
                .translation("waythread.configuration.anchorSeeThroughDistance")
                .defineInRange("anchorSeeThroughDistance", 512, 4, 4096);
        SHOW_RECORDING_DIRECTION = builder
                .comment("Show a moving highlight toward the end of the trail while recording.")
                .translation("waythread.configuration.showRecordingDirection")
                .define("showRecordingDirection", true);
        SHOW_RETURN_PROGRESS_BAR = builder
                .comment("Show a progress bar while returning to the start.")
                .translation("waythread.configuration.showReturnProgress")
                .define("showReturnProgress", true);
        SHOW_RETURN_PERCENTAGE = builder
                .comment("Show the percentage of points cleared since switching to return mode.")
                .translation("waythread.configuration.showReturnPercentage")
                .define("showReturnPercentage", true);
        RETURN_PROGRESS_FONT_SIZE = builder
                .comment("Height of the return progress label in GUI pixels.")
                .translation("waythread.configuration.returnProgressFontSize")
                .defineInRange("returnProgressFontSize", 7, 5, 16);
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

    static String anchorColor() {
        return ANCHOR_FOLLOWS_TRAIL_COLOR.get() ? TRAIL_COLOR.get() : ANCHOR_COLOR.get();
    }

    private static boolean isRgbColor(Object value) {
        return value instanceof String color && RGB_COLOR.matcher(color).matches();
    }
}
