package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a [KeyStep] is stored, for the key bar and the gesture bindings alike.
 *
 * One shape for both, so a sequence built in either editor is written the same way and a step
 * kind added later is read back in both places or in neither.
 */
internal object KeyStepJson {
    fun encode(step: KeyStep): JSONObject = when (step) {
        is KeyStep.Literal -> JSONObject().put("text", step.text)
        is KeyStep.Press -> JSONObject()
            .put("key", step.key?.name ?: JSONObject.NULL)
            .put("codePoint", step.codePoint)
            .put("modifiers", step.modifiers.bits)
    }

    fun encodeAll(steps: List<KeyStep>): JSONArray = JSONArray().also { array -> steps.forEach { array.put(encode(it)) } }

    /** Throws on a key this build does not know, so the caller decides what an unreadable step costs. */
    fun decode(item: JSONObject): KeyStep {
        if (item.has("text")) return KeyStep.Literal(item.getString("text"))
        return KeyStep.Press(
            key = item.optString("key").takeIf { it.isNotEmpty() && it != "null" }
                ?.let(TerminalKey::valueOf),
            codePoint = item.optInt("codePoint"),
            modifiers = Modifiers(item.optInt("modifiers")),
        )
    }

    fun decodeAll(array: JSONArray): List<KeyStep> = (0 until array.length()).map { decode(array.getJSONObject(it)) }
}
