// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.utils.HelidonBundle
import com.intellij.ide.starters.local.*
import com.intellij.ide.starters.local.wizard.StarterInitialStep
import com.intellij.ide.starters.shared.*
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Key
import com.intellij.pom.java.LanguageLevel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.lang.JavaVersion
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal val NEW_HELIDON_PROJECT_KEY: Key<Boolean> = Key.create("helidon.new.project")

internal class HelidonModuleBuilder : StarterModuleBuilder() {
  private var generatedStarterFilesToOpen: List<String>? = null
  private var starterOptions: HelidonStarterOptions = HelidonStarterOptions()

  override fun getBuilderId(): String = "helidon"
  override fun getNodeIcon(): Icon = HelidonIcons.Helidon
  override fun getPresentableName(): String = HelidonBundle.HELIDON_LIBRARY
  override fun getDescription(): String = HelidonBundle.message("description.for.helidon.project.starter")
  override fun getHelpId(): String = "helidon.project"
  override fun getProjectTypes(): List<StarterProjectType> = listOf(MAVEN_PROJECT)

  override fun getLanguages(): List<StarterLanguage> {
    return listOf(JAVA_STARTER_LANGUAGE)
  }

  // Helidon 4 requires at least Java 21
  override fun getMinJavaVersion(): JavaVersion = LanguageLevel.JDK_21.toJavaVersion()

  override fun createWizardSteps(context: WizardContext, modulesProvider: ModulesProvider): Array<ModuleWizardStep> {
    return emptyArray()
  }

  override fun createOptionsStep(contextProvider: StarterContextProvider): StarterInitialStep {
    return HelidonStarterInitialStep(contextProvider, { starterOptions }) { starterOptions = it }
  }

  override fun setupModule(module: Module) {
    // manually set, we do not show the second page with libraries
    starterContext.starter = starterContext.starterPack.starters.first()
    starterContext.starterDependencyConfig = loadDependencyConfig()[starterContext.starter?.id]

    if (starterContext.isCreatingNewProject) {
      module.project.putUserData(NEW_HELIDON_PROJECT_KEY, true)
    }

    super.setupModule(module)
  }

  override fun getStarterPack(): StarterPack {
    return StarterPack("helidon", listOf(
      Starter("helidon", "Helidon", getDependencyConfig("/starters/helidon.pom"), listOf())
    ))
  }

  override fun getAssets(starter: Starter): List<GeneratorAsset> {
    generatedStarterFilesToOpen = null
    val project = HelidonStarterProjectGeneratorProvider.generator.generate(
      HelidonStarterRequest(
        groupId = starterContext.group,
        artifactId = starterContext.artifact,
        projectVersion = starterContext.version,
        packageName = getPackagePath(starterContext.group, starterContext.artifact).replace('/', '.'),
        options = starterOptions
      )
    )
    generatedStarterFilesToOpen = project.filesToOpen
    return project.assets
  }

  override fun getFilePathsToOpen(): List<String> {
    generatedStarterFilesToOpen?.let { return it }
    return listOf("pom.xml")
  }

  private class HelidonStarterInitialStep(
    contextProvider: StarterContextProvider,
    private val options: () -> HelidonStarterOptions,
    private val updateOptions: (HelidonStarterOptions) -> Unit
  ) : StarterInitialStep(contextProvider) {
    private val flavorOptions = listOf(
      StarterOption(HELIDON_SE_FLAVOR, "Helidon SE"),
      StarterOption(HELIDON_MP_FLAVOR, "Helidon MP")
    )
    private val metricsProviders = listOf(
      StarterOption("microprofile", "MicroProfile"),
      StarterOption("micrometer", "Micrometer")
    )
    private val tracingProviders = listOf(
      StarterOption("jaeger", "Jaeger"),
      StarterOption("zipkin", "Zipkin")
    )
    private val jpaImplementations = listOf(
      StarterOption("hibernate", "Hibernate"),
      StarterOption("eclipselink", "EclipseLink")
    )
    private val connectionPools = listOf(
      StarterOption("hikaricp", "HikariCP"),
      StarterOption("ucp", "UCP")
    )
    private val optionLabels = mapOf(
      HELIDON_QUICKSTART_APP_TYPE to "Quickstart",
      HELIDON_DATABASE_APP_TYPE to "Database",
      HELIDON_CUSTOM_APP_TYPE to "Custom",
      HELIDON_OCI_APP_TYPE to "OCI",
      "jsonp" to "JSON-P",
      "jackson" to "Jackson",
      "jsonb" to "JSON-B",
      "h2" to "H2",
      "mysql" to "MySQL",
      "oracledb" to "Oracle DB",
      "mongodb" to "MongoDB"
    )

    override fun addFieldsAfter(layout: Panel) {
      var currentOptions = options().normalizedForStarter()
      updateOptions(currentOptions)
      var syncing = false

      val appTypeModel = DefaultComboBoxModel(appTypes(currentOptions.flavor).toTypedArray())
      val jsonLibraryModel = DefaultComboBoxModel(jsonLibraries(currentOptions.flavor).toTypedArray())
      val databaseServerModel = DefaultComboBoxModel(databaseServers(currentOptions.flavor).toTypedArray())

      var flavorCombo: ComboBox<StarterOption>? = null
      var appTypeCombo: ComboBox<StarterOption>? = null
      var jsonCheckBox: JCheckBox? = null
      var multipartCheckBox: JCheckBox? = null
      var jsonLibraryCombo: ComboBox<StarterOption>? = null
      var databaseCheckBox: JCheckBox? = null
      var databaseServerCombo: ComboBox<StarterOption>? = null
      var jpaCombo: ComboBox<StarterOption>? = null
      var connectionPoolCombo: ComboBox<StarterOption>? = null
      var autoDdlCheckBox: JCheckBox? = null
      var persistenceUnitField: JTextField? = null
      var dataSourceField: JTextField? = null
      var secureCheckBox: JCheckBox? = null
      var oidcCheckBox: JCheckBox? = null
      var jwtCheckBox: JCheckBox? = null
      var googleCheckBox: JCheckBox? = null
      var httpSignatureCheckBox: JCheckBox? = null
      var abacCheckBox: JCheckBox? = null
      var webClientCheckBox: JCheckBox? = null
      var faultToleranceCheckBox: JCheckBox? = null
      var corsCheckBox: JCheckBox? = null
      var coherenceCheckBox: JCheckBox? = null
      var metricsCheckBox: JCheckBox? = null
      var metricsProviderCombo: ComboBox<StarterOption>? = null
      var metricsBuiltinCheckBox: JCheckBox? = null
      var healthCheckBox: JCheckBox? = null
      var healthBuiltinCheckBox: JCheckBox? = null
      var tracingCheckBox: JCheckBox? = null
      var tracingProviderCombo: ComboBox<StarterOption>? = null
      var dockerCheckBox: JCheckBox? = null
      var nativeImageCheckBox: JCheckBox? = null
      var jlinkCheckBox: JCheckBox? = null
      var kubernetesCheckBox: JCheckBox? = null
      var jpmsCheckBox: JCheckBox? = null

      fun refreshControls() {
        if (syncing) {
          return
        }
        syncing = true
        try {
          val flavor = currentOptions.flavor
          val appType = currentOptions.appType
          val custom = appType == HELIDON_CUSTOM_APP_TYPE
          val databaseOptionsVisible = appType == HELIDON_DATABASE_APP_TYPE || (custom && currentOptions.database)
          val mpDatabaseOptionsVisible = databaseOptionsVisible && flavor == HELIDON_MP_FLAVOR
          val oci = appType == HELIDON_OCI_APP_TYPE

          appTypeModel.replaceWith(appTypes(flavor))
          jsonLibraryModel.replaceWith(jsonLibraries(flavor))
          databaseServerModel.replaceWith(databaseServers(flavor))

          flavorCombo?.selectedItem = selectedOption(flavorOptions, flavor)
          appTypeCombo?.selectedItem = selectedOption(appTypes(flavor), appType)
          jsonCheckBox?.isSelected = "json" in currentOptions.media
          multipartCheckBox?.isSelected = "multipart" in currentOptions.media
          jsonLibraryCombo?.selectedItem = selectedOption(jsonLibraries(flavor), currentOptions.jsonLibrary)
          databaseCheckBox?.isSelected = currentOptions.database
          databaseServerCombo?.selectedItem = selectedOption(databaseServers(flavor), currentOptions.databaseServer)
          jpaCombo?.selectedItem = selectedOption(jpaImplementations, currentOptions.jpaImplementation)
          connectionPoolCombo?.selectedItem = selectedOption(connectionPools, currentOptions.connectionPool)
          autoDdlCheckBox?.isSelected = currentOptions.autoDdl
          persistenceUnitField?.setTextIfDifferent(currentOptions.persistenceUnitName)
          dataSourceField?.setTextIfDifferent(currentOptions.dataSourceName)
          secureCheckBox?.isSelected = currentOptions.security
          oidcCheckBox?.isSelected = "oidc" in currentOptions.authenticationProviders
          jwtCheckBox?.isSelected = "jwt" in currentOptions.authenticationProviders
          googleCheckBox?.isSelected = "google" in currentOptions.authenticationProviders
          httpSignatureCheckBox?.isSelected = "http-signature" in currentOptions.authenticationProviders
          abacCheckBox?.isSelected = "abac" in currentOptions.authorizationProviders
          webClientCheckBox?.isSelected = "webclient" in currentOptions.extras
          faultToleranceCheckBox?.isSelected = "fault-tolerance" in currentOptions.extras
          corsCheckBox?.isSelected = "cors" in currentOptions.extras
          coherenceCheckBox?.isSelected = "coherence" in currentOptions.extras
          metricsCheckBox?.isSelected = currentOptions.metrics
          metricsProviderCombo?.selectedItem = selectedOption(metricsProviders, currentOptions.metricsProvider)
          metricsBuiltinCheckBox?.isSelected = currentOptions.metricsBuiltin
          healthCheckBox?.isSelected = currentOptions.health
          healthBuiltinCheckBox?.isSelected = currentOptions.healthBuiltin
          tracingCheckBox?.isSelected = currentOptions.tracing
          tracingProviderCombo?.selectedItem = selectedOption(tracingProviders, currentOptions.tracingProvider)
          dockerCheckBox?.isSelected = currentOptions.docker
          nativeImageCheckBox?.isSelected = currentOptions.dockerNativeImage
          jlinkCheckBox?.isSelected = currentOptions.dockerJlinkImage
          kubernetesCheckBox?.isSelected = currentOptions.kubernetes
          jpmsCheckBox?.isSelected = currentOptions.jpms

          jsonCheckBox?.isEnabled = custom
          multipartCheckBox?.isEnabled = custom
          jsonLibraryCombo?.isEnabled = !oci && "json" in currentOptions.media
          databaseCheckBox?.isEnabled = custom
          databaseServerCombo?.isEnabled = databaseOptionsVisible
          jpaCombo?.isEnabled = mpDatabaseOptionsVisible
          connectionPoolCombo?.isEnabled = mpDatabaseOptionsVisible
          autoDdlCheckBox?.isEnabled = mpDatabaseOptionsVisible
          persistenceUnitField?.isEnabled = mpDatabaseOptionsVisible
          dataSourceField?.isEnabled = mpDatabaseOptionsVisible
          secureCheckBox?.isEnabled = custom
          listOf(oidcCheckBox, jwtCheckBox, googleCheckBox, httpSignatureCheckBox, abacCheckBox)
            .setEnabled(custom && currentOptions.security)
          webClientCheckBox?.isEnabled = custom && flavor == HELIDON_SE_FLAVOR
          listOf(faultToleranceCheckBox, corsCheckBox, coherenceCheckBox).setEnabled(custom)
          metricsCheckBox?.isEnabled = custom
          metricsProviderCombo?.isEnabled = custom && currentOptions.metrics && flavor == HELIDON_MP_FLAVOR
          metricsBuiltinCheckBox?.isEnabled = custom && currentOptions.metrics
          healthCheckBox?.isEnabled = custom
          healthBuiltinCheckBox?.isEnabled = custom && currentOptions.health
          tracingCheckBox?.isEnabled = custom
          tracingProviderCombo?.isEnabled = custom && currentOptions.tracing
          dockerCheckBox?.isEnabled = custom
          nativeImageCheckBox?.isEnabled = custom && currentOptions.docker
          jlinkCheckBox?.isEnabled = custom && currentOptions.docker
          kubernetesCheckBox?.isEnabled = custom
          jpmsCheckBox?.isEnabled = custom
        }
        finally {
          syncing = false
        }
      }

      fun setOptions(newOptions: HelidonStarterOptions) {
        currentOptions = newOptions.normalizedForStarter()
        updateOptions(currentOptions)
        refreshControls()
      }

      layout.group("Helidon Starter") {
        row("Flavor:") {
          val flavorCell = comboBox(flavorOptions, starterOptionRenderer())
          flavorCombo = flavorCell.component
          flavorCell.component.addActionListener {
            if (syncing) return@addActionListener
            val selectedFlavor = flavorCell.component.selectedStarterValue() ?: return@addActionListener
            val selectedAppType = currentOptions.appType.takeIf { it in helidonStarterAppTypes(selectedFlavor) }
              ?: HELIDON_QUICKSTART_APP_TYPE
            setOptions(currentOptions.withStarterPreset(selectedFlavor = selectedFlavor, selectedAppType = selectedAppType))
          }
        }
        row("Application type:") {
          val appTypeCell = comboBox(appTypeModel, starterOptionRenderer())
          appTypeCombo = appTypeCell.component
          appTypeCell.component.addActionListener {
            if (syncing) return@addActionListener
            val selectedAppType = appTypeCell.component.selectedStarterValue() ?: return@addActionListener
            setOptions(currentOptions.withStarterPreset(selectedAppType = selectedAppType))
          }
        }

        row("Media:") {
          checkBox("JSON").component.apply {
            jsonCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(media = currentOptions.media.toggle("json", isSelected)))
            }
          }
          checkBox("MultiPart").component.apply {
            multipartCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(media = currentOptions.media.toggle("multipart", isSelected)))
            }
          }
          val jsonCell = comboBox(jsonLibraryModel, starterOptionRenderer())
          jsonLibraryCombo = jsonCell.component
          jsonCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(jsonLibrary = jsonCell.component.selectedStarterValue() ?: currentOptions.jsonLibrary))
          }
        }

        row {
          checkBox("Database").component.apply {
            databaseCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(database = isSelected))
            }
          }
          val databaseCell = comboBox(databaseServerModel, starterOptionRenderer())
          databaseServerCombo = databaseCell.component
          databaseCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(databaseServer = databaseCell.component.selectedStarterValue() ?: currentOptions.databaseServer))
          }
          checkBox("Auto DDL").component.apply {
            autoDdlCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(autoDdl = isSelected))
            }
          }
        }
        row("MP Database:") {
          val jpaCell = comboBox(jpaImplementations, starterOptionRenderer())
          jpaCombo = jpaCell.component
          jpaCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(jpaImplementation = jpaCell.component.selectedStarterValue() ?: currentOptions.jpaImplementation))
          }
          val cpCell = comboBox(connectionPools, starterOptionRenderer())
          connectionPoolCombo = cpCell.component
          cpCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(connectionPool = cpCell.component.selectedStarterValue() ?: currentOptions.connectionPool))
          }
          val puCell = textField()
          persistenceUnitField = puCell.component
          puCell.component.addTextChangeListener {
            if (!syncing) {
              setOptions(currentOptions.copy(persistenceUnitName = puCell.component.text.ifBlank { "pu1" }))
            }
          }
          val dsCell = textField()
          dataSourceField = dsCell.component
          dsCell.component.addTextChangeListener {
            if (!syncing) {
              setOptions(currentOptions.copy(dataSourceName = dsCell.component.text.ifBlank { "ds1" }))
            }
          }
        }

        row("Security:") {
          checkBox("Secure").component.apply {
            secureCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(security = isSelected))
            }
          }
          checkBox("OIDC").component.apply {
            oidcCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(authenticationProviders = currentOptions.authenticationProviders.toggle("oidc", isSelected)))
            }
          }
          checkBox("JWT").component.apply {
            jwtCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(authenticationProviders = currentOptions.authenticationProviders.toggle("jwt", isSelected)))
            }
          }
        }
        row {
          checkBox("Google Login").component.apply {
            googleCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(authenticationProviders = currentOptions.authenticationProviders.toggle("google", isSelected)))
            }
          }
          checkBox("HTTP Signature").component.apply {
            httpSignatureCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(
                authenticationProviders = currentOptions.authenticationProviders.toggle("http-signature", isSelected)
              ))
            }
          }
          checkBox("ABAC").component.apply {
            abacCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(authorizationProviders = currentOptions.authorizationProviders.toggle("abac", isSelected)))
            }
          }
        }

        row("Extra:") {
          checkBox("WebClient").component.apply {
            webClientCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("webclient", isSelected)))
            }
          }
          checkBox("Fault Tolerance").component.apply {
            faultToleranceCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("fault-tolerance", isSelected)))
            }
          }
        }
        row {
          checkBox("CORS").component.apply {
            corsCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("cors", isSelected)))
            }
          }
          checkBox("Coherence").component.apply {
            coherenceCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("coherence", isSelected)))
            }
          }
        }

        row("Observability:") {
          checkBox("Metrics").component.apply {
            metricsCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(metrics = isSelected))
            }
          }
          val metricsCell = comboBox(metricsProviders, starterOptionRenderer())
          metricsProviderCombo = metricsCell.component
          metricsCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(metricsProvider = metricsCell.component.selectedStarterValue() ?: currentOptions.metricsProvider))
          }
          checkBox("Built-in Metrics").component.apply {
            metricsBuiltinCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(metricsBuiltin = isSelected))
            }
          }
        }
        row {
          checkBox("Health").component.apply {
            healthCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(health = isSelected))
            }
          }
          checkBox("Built-in Health").component.apply {
            healthBuiltinCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(healthBuiltin = isSelected))
            }
          }
          checkBox("Tracing").component.apply {
            tracingCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(tracing = isSelected))
            }
          }
          val tracingCell = comboBox(tracingProviders, starterOptionRenderer())
          tracingProviderCombo = tracingCell.component
          tracingCell.component.addActionListener {
            if (syncing) return@addActionListener
            setOptions(currentOptions.copy(tracingProvider = tracingCell.component.selectedStarterValue() ?: currentOptions.tracingProvider))
          }
        }

        row("Packaging:") {
          checkBox("Docker").component.apply {
            dockerCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(docker = isSelected))
            }
          }
          checkBox("Native Image").component.apply {
            nativeImageCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(dockerNativeImage = isSelected))
            }
          }
          checkBox("JLink").component.apply {
            jlinkCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(dockerJlinkImage = isSelected))
            }
          }
        }
        row {
          checkBox("Kubernetes").component.apply {
            kubernetesCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(kubernetes = isSelected))
            }
          }
          checkBox("JPMS").component.apply {
            jpmsCheckBox = this
            addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(jpms = isSelected))
            }
          }
        }
      }
      layout.row {
        hyperLink(HelidonBundle.message("helidon.se.overview"), "https://helidon.io/docs/v4/#/se/introduction")
      }
      refreshControls()
    }

    private fun appTypes(flavor: String): List<StarterOption> =
      helidonStarterAppTypes(flavor).map(::starterOption)

    private fun jsonLibraries(flavor: String): List<StarterOption> =
      helidonStarterJsonLibraries(flavor).map(::starterOption)

    private fun databaseServers(flavor: String): List<StarterOption> =
      helidonStarterDatabaseServers(flavor).map(::starterOption)

    private fun starterOption(value: String): StarterOption =
      StarterOption(value, optionLabels[value] ?: value)

    private fun selectedOption(options: List<StarterOption>, value: String): StarterOption? =
      options.firstOrNull { it.value == value }

    private fun starterOptionRenderer() =
      SimpleListCellRenderer.create<StarterOption>("") { it?.label.orEmpty() }

    private fun ComboBox<StarterOption>.selectedStarterValue(): String? =
      (selectedItem as? StarterOption)?.value

    private fun List<String>.toggle(value: String, selected: Boolean): List<String> =
      if (selected) {
        (this + value).distinct()
      }
      else {
        filterNot { it == value }
      }

    private fun DefaultComboBoxModel<StarterOption>.replaceWith(options: List<StarterOption>) {
      removeAllElements()
      options.forEach { addElement(it) }
    }

    private fun JTextField.setTextIfDifferent(value: String) {
      if (text != value) {
        text = value
      }
    }

    private fun Iterable<JComponent?>.setEnabled(enabled: Boolean) {
      forEach { it?.isEnabled = enabled }
    }

    private fun JTextField.addTextChangeListener(listener: () -> Unit) {
      document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener()
        override fun removeUpdate(e: DocumentEvent) = listener()
        override fun changedUpdate(e: DocumentEvent) = listener()
      })
    }
  }

  private data class StarterOption(val value: String, val label: String)
}
