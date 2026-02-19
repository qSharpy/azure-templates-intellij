package com.bogdanbujor.azuretemplates.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings UI panel under Settings > Tools > Azure Templates Navigator.
 */
class PluginSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var colorField: JBTextField? = null
    private var diagnosticsEnabledCheckbox: JBCheckBox? = null
    private var debounceSpinner: JSpinner? = null
    private var graphRootPathField: JBTextField? = null

    override fun getDisplayName(): String = "Azure Templates Navigator"

    override fun createComponent(): JComponent {
        colorField = JBTextField()
        diagnosticsEnabledCheckbox = JBCheckBox("Enable parameter validation diagnostics")
        debounceSpinner = JSpinner(SpinnerNumberModel(500, 100, 5000, 100))
        graphRootPathField = JBTextField()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Required parameter color (hex):"), colorField!!, 1, false)
            .addComponent(diagnosticsEnabledCheckbox!!, 1)
            .addLabeledComponent(JBLabel("Diagnostics debounce (ms):"), debounceSpinner!!, 1, false)
            .addLabeledComponent(JBLabel("Graph root path (sub-directory):"), graphRootPathField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance()
        return colorField?.text != settings.requiredParameterColor ||
                diagnosticsEnabledCheckbox?.isSelected != settings.diagnosticsEnabled ||
                (debounceSpinner?.value as? Int) != settings.diagnosticsDebounceMs ||
                graphRootPathField?.text != settings.graphRootPath
    }

    override fun apply() {
        val settings = PluginSettings.getInstance()
        val state = settings.state
        state.requiredParameterColor = colorField?.text ?: "#c92d35"
        state.diagnosticsEnabled = diagnosticsEnabledCheckbox?.isSelected ?: true
        state.diagnosticsDebounceMs = (debounceSpinner?.value as? Int) ?: 500
        state.graphRootPath = graphRootPathField?.text ?: ""
        settings.loadState(state)
    }

    override fun reset() {
        val settings = PluginSettings.getInstance()
        colorField?.text = settings.requiredParameterColor
        diagnosticsEnabledCheckbox?.isSelected = settings.diagnosticsEnabled
        debounceSpinner?.value = settings.diagnosticsDebounceMs
        graphRootPathField?.text = settings.graphRootPath
    }

    override fun disposeUIResources() {
        panel = null
        colorField = null
        diagnosticsEnabledCheckbox = null
        debounceSpinner = null
        graphRootPathField = null
    }
}
