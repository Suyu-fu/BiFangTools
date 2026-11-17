package dev.waythread;

import net.neoforged.neoforge.common.ModConfigSpec;

final class WaythreadConfig {
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue LOAD_DISTANCE;
    static final ModConfigSpec.DoubleValue RECORD_POINT_SPACING;
    static final ModConfigSpec.DoubleValue RETURN_ERASE_DISTANCE;
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
                .defineInRange("recordPointSpacing", 0.45, 0.1, 2.0);
        RETURN_ERASE_DISTANCE = builder
                .comment("Distance in blocks for erasing the next trail point and reaching the start in return mode.")
                .translation("waythread.configuration.returnEraseDistance")
                .defineInRange("returnEraseDistance", 2.0, 0.5, 6.0);
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
}
