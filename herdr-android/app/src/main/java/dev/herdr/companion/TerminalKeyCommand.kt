package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

/** Herdr 0.9.5 accepts named chords, but not tmux's BSpace/PPage/Home/Delete names. */
internal data class TerminalKeyCommand(val type: String, val payload: JSONObject) {
    companion object {
        fun from(value: String): TerminalKeyCommand? {
            var rest = value.trim()
            val modifiers = linkedSetOf<String>()
            while (rest.length > 2 && rest[1] == '-' && rest[0] in "CMS") {
                modifiers += when(rest[0]) { 'C'->"ctrl"; 'M'->"alt"; else->"shift" }; rest=rest.drop(2)
            }
            val parts = rest.split('+')
            for (part in parts.dropLast(1)) {
                val modifier=part.lowercase(); if(modifier !in listOf("ctrl","alt","shift")) return null
                modifiers += modifier
            }
            val base = when(val key=parts.last().lowercase()) {
                "bspace","bs","backspace"->"Backspace"; "escape","esc"->"Esc"
                "enter"->"Enter"; "tab"->"Tab"; "left"->"Left"; "right"->"Right"; "up"->"Up"; "down"->"Down"
                "home"->"Home"; "end"->"End"; "dc","delete"->"Delete"; "ppage","pageup"->"PageUp"; "npage","pagedown"->"PageDown"
                else -> if(key.matches(Regex("[a-z0-9]|f(?:[1-9]|1[0-2])"))) key else return null
            }
            if(base in listOf("Home","End","Delete","PageUp","PageDown")) {
                val modifier=1+(if("shift" in modifiers) 1 else 0)+(if("alt" in modifiers) 2 else 0)+(if("ctrl" in modifiers) 4 else 0)
                val sequence=when(base) {
                    "Home" -> "\u001b["+(if(modifier==1) "" else "1;$modifier")+"H"
                    "End" -> "\u001b["+(if(modifier==1) "" else "1;$modifier")+"F"
                    else -> "\u001b["+when(base){"Delete"->"3";"PageUp"->"5";else->"6"}+(if(modifier==1) "" else ";$modifier")+"~"
                }
                return TerminalKeyCommand("send_text",JSONObject().put("text",sequence).put("activity_label","Terminal key $value"))
            }
            val key=(listOf("ctrl","alt","shift").filter { it in modifiers }+base).joinToString("+")
            return TerminalKeyCommand("send_keys",JSONObject().put("keys",JSONArray().put(key)).put("activity_label","Terminal key $value"))
        }
    }
}
