package com.bogdanbujor.azuretemplates.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent plugin settings stored at the application level.
 *
 * Port of VS Code contributes.configuration in package.json.
 */
@State(
    name = "AzureTemplatesNavigatorSettings",
    storages = [Storage("AzureTemplatesNavigator.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var requiredParameterColor: String = "#E06C75",
        var diagnosticsEnabled: Boolean = true,
        var diagnosticsDebounceMs: Int = 500,
        var graphRootPath: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val requiredParameterColor: String get() = myState.requiredParameterColor
    val diagnosticsEnabled: Boolean get() = myState.diagnosticsEnabled
    val diagnosticsDebounceMs: Int get() = myState.diagnosticsDebounceMs
    val graphRootPath: String get() = myState.graphRootPath

    companion object {
        fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
