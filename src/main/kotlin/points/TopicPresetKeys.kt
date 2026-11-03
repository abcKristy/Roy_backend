package com.example.points

import java.util.Locale

/** Stable keys from the V001 topic_presets catalog. */
object TopicPresetKeys {
    val allowed: Set<String> = setOf(
        "football", "volleyball", "basketball", "tennis", "running", "workout",
        "table_tennis", "badminton", "board_games", "mafia", "other",
    )
}

/** An advisory JVM key; PostgreSQL's lower(btrim) unique index decides Unicode collisions. */
data class PointTopicKey(val presetKey: String, val customLabelKey: String)

data class NormalizedPointTopic(val input: PointTopicInput, val key: PointTopicKey)

/** Keeps the user's display label while deriving a case-insensitive lookup key. */
fun PointTopicInput.normalizeTopic(): NormalizedPointTopic {
    require(presetKey in TopicPresetKeys.allowed) { "Invalid topic preset" }
    val displayLabel = customLabel?.trim()
    if (presetKey == "other") {
        require(displayLabel != null && displayLabel.codePointCount(0, displayLabel.length) in 1..40) {
            "Invalid custom topic"
        }
    } else {
        require(displayLabel == null) { "Custom topic requires other preset" }
    }
    return NormalizedPointTopic(
        copy(customLabel = displayLabel),
        PointTopicKey(presetKey, displayLabel?.lowercase(Locale.ROOT).orEmpty()),
    )
}
