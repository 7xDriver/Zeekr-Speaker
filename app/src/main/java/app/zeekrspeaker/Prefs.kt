package app.zeekrspeaker

import android.content.Context

object Prefs {

    const val SLOT_COUNT = 10

    const val MODE_TALK = "talk"
    const val MODE_ACTIVATE = "play"
    const val MODE_PLAY = MODE_ACTIVATE

    const val FLOAT_EXPAND_RIGHT = "right"
    const val FLOAT_EXPAND_LEFT = "left"

    const val PRESET_COLOUR_BLACK = "black"
    const val PRESET_COLOUR_WHITE = "white"
    const val PRESET_COLOUR_SEMI_TRANSPARENT = "semi_transparent"

    const val DUCKING_OFF = "off"
    const val DUCKING_MICROPHONE_ONLY = "microphone_only"
    const val DUCKING_MICROPHONE_AND_PRESETS = "microphone_and_presets"

    val GRID_LAYOUTS = listOf(
        "1x1",
        "1x2", "2x1",
        "1x3", "3x1",
        "1x4", "4x1", "2x2",
        "1x5", "5x1",
        "1x6", "6x1", "2x3", "3x2",
        "1x7", "7x1",
        "1x8", "8x1", "2x4", "4x2",
        "1x9", "9x1", "3x3",
        "1x10", "10x1", "2x5", "5x2"
    )

    data class Slot(
        val label: String,
        val source: String,
        val sourceName: String
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun slotName(i: Int): String {
        require(i in 0 until SLOT_COUNT)
        return "Preset ${i + 1}"
    }

    private fun defaultLabel(i: Int): String =
        when (i) {
            0 -> "La Cucaracha"
            1 -> "Dukes of Hazzard"
            2 -> "Roadrunner"
            3 -> "Freddy"
            4 -> "Can't Park There"
            else -> slotName(i)
        }

    private fun defaultSource(i: Int): String =
        when (i) {
            0 -> "raw:la_cucaracha"
            1 -> "raw:dukes_of_hazzard"
            2 -> "raw:roadrunner"
            3 -> "raw:freddy"
            4 -> "raw:cantparkthere"
            else -> ""
        }

    private fun defaultSourceName(i: Int): String =
        when (i) {
            0 -> "La Cucaracha"
            1 -> "Dukes of Hazzard"
            2 -> "Roadrunner"
            3 -> "Freddy"
            4 -> "Can't Park There"
            else -> "No sound chosen"
        }

    fun slot(ctx: Context, i: Int): Slot {
        require(i in 0 until SLOT_COUNT)

        val p = prefs(ctx)
        val savedLabel = p.getString("label_$i", null)

        val label = if (
            savedLabel == null ||
            savedLabel == slotName(i) ||
            (i in 0..3 && savedLabel == "Horn ${i + 1}") ||
            (i in 4..7 && savedLabel == "Custom ${i - 3}")
        ) {
            defaultLabel(i)
        } else {
            savedLabel.take(26)
        }

        return Slot(
            label = label,
            source = p.getString("source_$i", defaultSource(i))
                ?: defaultSource(i),
            sourceName = p.getString(
                "sourcename_$i",
                defaultSourceName(i)
            ) ?: defaultSourceName(i)
        )
    }

    fun displayLabel(ctx: Context, i: Int): String {
        val label = slot(ctx, i).label.trim()
        return if (label.isEmpty()) defaultLabel(i) else label.take(26)
    }

    fun setLabel(ctx: Context, i: Int, label: String) {
        require(i in 0 until SLOT_COUNT)

        prefs(ctx).edit()
            .putString("label_$i", label.take(26))
            .apply()
    }

    fun setSource(ctx: Context, i: Int, source: String, name: String) {
        require(i in 0 until SLOT_COUNT)

        prefs(ctx).edit()
            .putString("source_$i", source)
            .putString("sourcename_$i", name)
            .apply()
    }

    fun resetSlot(ctx: Context, i: Int) {
        require(i in 0 until SLOT_COUNT)

        prefs(ctx).edit()
            .remove("label_$i")
            .remove("source_$i")
            .remove("sourcename_$i")
            .apply()
    }

    // Push to Activate is the default mode.
    fun mode(ctx: Context): String =
        prefs(ctx).getString("mode", MODE_ACTIVATE) ?: MODE_ACTIVATE

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("mode", mode).apply()
    }

    // ---------------------------------------------------------
    // NUMBER OF PRESETS
    // ---------------------------------------------------------

    // Default is 5 presets.
    fun floatPresetCount(ctx: Context): Int {
        return prefs(ctx)
            .getInt("float_preset_count", 5)
            .coerceIn(1, SLOT_COUNT)
    }

    fun setFloatPresetCount(ctx: Context, count: Int) {
        val adjusted = count.coerceIn(1, SLOT_COUNT)

        val p = prefs(ctx)
        val savedLayout = p.getString("float_grid_layout", "5x1")
            ?: "5x1"

        val parts = savedLayout.split("x")
        val rows = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val columns = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val validLayout =
            savedLayout in GRID_LAYOUTS &&
                    rows * columns == adjusted

        p.edit()
            .putInt("float_preset_count", adjusted)
            .putString(
                "float_grid_layout",
                if (validLayout) savedLayout else if (adjusted == 5) "5x1" else "1x$adjusted"
            )
            .apply()
    }

    // Only layouts that fit the selected number of presets.
    fun gridLayoutsForCount(count: Int): List<String> {
        val adjusted = count.coerceIn(1, SLOT_COUNT)

        return GRID_LAYOUTS.filter { layout ->
            val parts = layout.split("x")
            val rows = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val columns = parts.getOrNull(1)?.toIntOrNull() ?: 0

            rows * columns == adjusted
        }
    }

    // Human-friendly preset configuration label.
    fun gridLayoutLabel(layout: String): String {
        val parts = layout.split("x")
        val rows = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val columns = parts.getOrNull(1)?.toIntOrNull() ?: 4
        val count = rows * columns

        return "$count presets (${rows}×${columns})"
    }

    // Returns a layout valid for the selected preset count.
    fun floatGridLayout(ctx: Context): String {
        val count = floatPresetCount(ctx)

        val saved = prefs(ctx).getString("float_grid_layout", "5x1")
            ?: "5x1"

        val migrated = if (count == 5 && (saved == "1x5" || saved == "1x4")) "5x1" else saved
        if (migrated != saved) {
            prefs(ctx).edit().putString("float_grid_layout", migrated).apply()
        }
        return if (migrated in gridLayoutsForCount(count)) migrated else if (count == 5) "5x1" else "1x$count"
    }

    fun setFloatGridLayout(ctx: Context, layout: String) {
        val count = floatPresetCount(ctx)
        val validLayouts = gridLayoutsForCount(count)

        val adjusted = if (layout in validLayouts) {
            layout
        } else {
            if (count == 5) "5x1" else "1x$count"
        }

        prefs(ctx).edit()
            .putString("float_grid_layout", adjusted)
            .apply()
    }

    // ---------------------------------------------------------
    // FLOATING BUTTON EXPANSION DIRECTION
    // ---------------------------------------------------------

    fun floatExpandDirection(ctx: Context): String {
        val saved = prefs(ctx).getString(
            "float_expand_direction",
            FLOAT_EXPAND_RIGHT
        ) ?: FLOAT_EXPAND_RIGHT

        return if (
            saved == FLOAT_EXPAND_LEFT ||
            saved == FLOAT_EXPAND_RIGHT
        ) {
            saved
        } else {
            FLOAT_EXPAND_RIGHT
        }
    }

    fun presetButtonColour(ctx: Context): String {
        val saved = prefs(ctx).getString(
            "preset_button_colour",
            PRESET_COLOUR_BLACK
        ) ?: PRESET_COLOUR_BLACK

        // Migrate the previous "transparent" setting to its actual appearance.
        val adjusted = when (saved) {
            // Older versions offered a more-transparent option. Retire it and
            // return those users to the supported semi-transparent appearance.
            "transparent", "more_transparent", PRESET_COLOUR_SEMI_TRANSPARENT -> PRESET_COLOUR_SEMI_TRANSPARENT
            PRESET_COLOUR_WHITE -> PRESET_COLOUR_WHITE
            PRESET_COLOUR_BLACK -> PRESET_COLOUR_BLACK
            else -> PRESET_COLOUR_BLACK
        }

        if (saved != adjusted) {
            prefs(ctx).edit().putString("preset_button_colour", adjusted).apply()
        }
        return adjusted
    }

    fun setPresetButtonColour(ctx: Context, colour: String) {
        val adjusted = if (colour in setOf(
                PRESET_COLOUR_BLACK,
                PRESET_COLOUR_WHITE,
                PRESET_COLOUR_SEMI_TRANSPARENT
            )) colour else PRESET_COLOUR_BLACK

        prefs(ctx).edit().putString("preset_button_colour", adjusted).apply()
    }

    fun avasWarningAccepted(ctx: Context): Boolean =
        prefs(ctx).getBoolean("avas_warning_accepted", false)

    fun setAvasWarningAccepted(ctx: Context, accepted: Boolean) {
        prefs(ctx).edit().putBoolean("avas_warning_accepted", accepted).apply()
    }

    fun setFloatExpandDirection(ctx: Context, direction: String) {
        val adjusted = if (
            direction == FLOAT_EXPAND_LEFT
        ) {
            FLOAT_EXPAND_LEFT
        } else {
            FLOAT_EXPAND_RIGHT
        }

        prefs(ctx).edit()
            .putString("float_expand_direction", adjusted)
            .apply()
    }

    // ---------------------------------------------------------
    // ACTIVATION TIME
    // ---------------------------------------------------------

    // 3–60 seconds, in 1-second increments.
    // Default is 3 seconds.
    fun offSeconds(ctx: Context): Int {
        return prefs(ctx)
            .getInt("off_seconds", 3)
            .coerceIn(3, 60)
    }

    fun setOffSeconds(ctx: Context, seconds: Int) {
        val adjusted = seconds.coerceIn(3, 60)

        prefs(ctx).edit()
            .putInt("off_seconds", adjusted)
            .apply()
    }

    // ---------------------------------------------------------
    // FLOATING BUTTON SIZE
    // ---------------------------------------------------------

    // 50–300%, default 100%.
    fun floatButtonSize(ctx: Context): Int {
        val saved = prefs(ctx)
            .getInt("float_button_size", 100)
            .coerceIn(50, 300)

        return ((saved + 2) / 5) * 5
    }

    fun setFloatButtonSize(ctx: Context, percentage: Int) {
        val adjusted = percentage.coerceIn(50, 300)
        val rounded = ((adjusted + 2) / 5) * 5

        prefs(ctx).edit()
            .putInt("float_button_size", rounded)
            .apply()
    }

    // ---------------------------------------------------------
    // PRESET BUTTON SIZE
    // ---------------------------------------------------------

    // 50–300%, default 100%.
    fun presetButtonSize(ctx: Context): Int {
        val saved = prefs(ctx)
            .getInt("preset_button_size", 100)
            .coerceIn(50, 300)

        return ((saved + 2) / 5) * 5
    }

    fun setPresetButtonSize(ctx: Context, percentage: Int) {
        val adjusted = percentage.coerceIn(50, 300)
        val rounded = ((adjusted + 2) / 5) * 5

        prefs(ctx).edit()
            .putInt("preset_button_size", rounded)
            .apply()
    }

    // ---------------------------------------------------------
    // PRESET TEXT SIZE
    // ---------------------------------------------------------

    // 50–200%, default 100%.
    fun presetTextSize(ctx: Context): Int {
        val saved = prefs(ctx)
            .getInt("preset_text_size", 100)
            .coerceIn(50, 200)

        return ((saved + 2) / 5) * 5
    }

    fun setPresetTextSize(ctx: Context, percentage: Int) {
        val adjusted = percentage.coerceIn(50, 200)
        val rounded = ((adjusted + 2) / 5) * 5

        prefs(ctx).edit()
            .putInt("preset_text_size", rounded)
            .apply()
    }

    // ---------------------------------------------------------
    // FLOATING BUTTON LOCK
    // ---------------------------------------------------------

    fun floatButtonLocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean("float_button_locked", false)

    fun setFloatButtonLocked(ctx: Context, locked: Boolean) {
        prefs(ctx).edit()
            .putBoolean("float_button_locked", locked)
            .apply()
    }

    // ---------------------------------------------------------
    // MAXIMUM VEHICLE SPEED
    // ---------------------------------------------------------

    const val DEFAULT_MAX_VEHICLE_SPEED = 0

    fun maxVehicleSpeed(ctx: Context): Int =
        (prefs(ctx).getInt("max_vehicle_speed_kmh", DEFAULT_MAX_VEHICLE_SPEED)
            .coerceIn(0, 100) / 10) * 10

    fun setMaxVehicleSpeed(ctx: Context, speed: Int) {
        val adjusted = ((speed.coerceIn(0, 100) + 5) / 10) * 10
        prefs(ctx).edit().putInt("max_vehicle_speed_kmh", adjusted).apply()
    }


    // Cabin audio muting uses transient MAY_DUCK focus. The default preserves
    // the successful behaviour for both microphone and preset playback.
    fun audioDuckingScope(ctx: Context): String =
        prefs(ctx).getString(
            "audio_ducking_scope",
            DUCKING_MICROPHONE_AND_PRESETS
        ) ?: DUCKING_MICROPHONE_AND_PRESETS

    fun setAudioDuckingScope(ctx: Context, scope: String) {
        val adjusted = when (scope) {
            DUCKING_OFF -> DUCKING_OFF
            DUCKING_MICROPHONE_ONLY -> DUCKING_MICROPHONE_ONLY
            else -> DUCKING_MICROPHONE_AND_PRESETS
        }
        prefs(ctx).edit().putString("audio_ducking_scope", adjusted).apply()
    }

    fun duckMicrophone(ctx: Context): Boolean =
        audioDuckingScope(ctx) != DUCKING_OFF

    fun duckPresets(ctx: Context): Boolean =
        audioDuckingScope(ctx) == DUCKING_MICROPHONE_AND_PRESETS

    // ---------------------------------------------------------
    // AUDIO OUTPUT VOLUME
    // ---------------------------------------------------------

    fun volumePercent(ctx: Context): Int =
        prefs(ctx).getInt("volume_percent", 100).coerceIn(0, 100)

    fun setVolumePercent(ctx: Context, percentage: Int) {
        prefs(ctx).edit()
            .putInt("volume_percent", percentage.coerceIn(0, 100))
            .apply()
    }

    // ---------------------------------------------------------
    // LIVE MICROPHONE GAIN
    // ---------------------------------------------------------

    // 0.5x–5.0x, default 4.0x. Stored independently of output volume.
    fun microphoneGain(ctx: Context): Float =
        prefs(ctx).getFloat("microphone_gain", 4.0f).coerceIn(0.5f, 5.0f)

    fun setMicrophoneGain(ctx: Context, gain: Float) {
        val adjusted = (gain.coerceIn(0.5f, 5.0f) * 10f).toInt() / 10f
        prefs(ctx).edit().putFloat("microphone_gain", adjusted).apply()
    }
}