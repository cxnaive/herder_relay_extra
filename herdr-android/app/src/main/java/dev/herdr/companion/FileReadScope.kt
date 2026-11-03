package dev.herdr.companion

import org.json.JSONObject

object FileReadScope {
    private val actions = setOf("workspace_file", "workspace_file_read", "workspace_file_download", "workspace_list")
    fun apply(command: JSONObject, unrestricted: Boolean): JSONObject = command.apply {
        if (optString("type") in actions) put("file_scope", if (unrestricted) "filesystem" else "workspace")
    }
}
