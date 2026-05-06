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
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.lang.JavaVersion
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal val NEW_HELIDON_PROJECT_KEY: Key<Boolean> = Key.create("helidon.new.project")

private const val HELIDON_STARTER_OPTIONS_MIN_HEIGHT = 220
private const val HELIDON_STARTER_OPTIONS_VISIBLE_HEIGHT = 420

private typealias StarterOption = HelidonStarterOption

internal class HelidonModuleBuilder : StarterModuleBuilder() {
  private var generatedStarterFilesToOpen: List<String>? = null
  private var starterOptions: HelidonStarterOptions = HelidonStarterOptions()

  override fun getBuilderId(): String = "helidon"
  override fun getNodeIcon(): Icon = HelidonIcons.Helidon
  override fun getPresentableName(): String = HelidonBundle.HELIDON_LIBRARY
  override fun getDescription(): String = HelidonBundle.message("description.for.helidon.project.starter")
  override fun getHelpId(): String = "helidon.project"
  override fun getProjectTypes(): List<StarterProjectType> = emptyList()

  override fun getLanguages(): List<StarterLanguage> {
    return listOf(JAVA_STARTER_LANGUAGE)
  }

  override fun isShowProjectTypes(): Boolean = false

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
    starterContext.projectType = MAVEN_PROJECT

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
    private var metadataModel = HelidonStarterMetadataModelProvider.current()
    private var currentOptions = options().normalizedForStarter(metadataModel)
    private var syncing = false

    private val appTypeModel = DefaultComboBoxModel(appTypes(currentOptions.flavor).toTypedArray())
    private val jsonLibraryModel = DefaultComboBoxModel(jsonLibraries(currentOptions.flavor).toTypedArray())
    private val databaseServerModel = DefaultComboBoxModel(databaseServers(currentOptions.flavor).toTypedArray())
    private val jpaModel = DefaultComboBoxModel(jpaImplementations().toTypedArray())
    private val connectionPoolModel = DefaultComboBoxModel(connectionPools().toTypedArray())
    private val metricsProviderModel = DefaultComboBoxModel(metricsProviders().toTypedArray())
    private val tracingProviderModel = DefaultComboBoxModel(tracingProviders().toTypedArray())

    private var flavorSegmentedButton: SegmentedButton<StarterOption>? = null
    private var appTypeCombo: ComboBox<StarterOption>? = null
    private var jsonCheckBox: JCheckBox? = null
    private var multipartCheckBox: JCheckBox? = null
    private var jsonLibraryCombo: ComboBox<StarterOption>? = null
    private var databaseCheckBox: JCheckBox? = null
    private var databaseServerCombo: ComboBox<StarterOption>? = null
    private var mpDatabaseRows: RowsRange? = null
    private var jpaCombo: ComboBox<StarterOption>? = null
    private var connectionPoolCombo: ComboBox<StarterOption>? = null
    private var autoDdlCheckBox: JCheckBox? = null
    private var persistenceUnitField: JTextField? = null
    private var dataSourceField: JTextField? = null
    private var secureCheckBox: JCheckBox? = null
    private var oidcCheckBox: JCheckBox? = null
    private var jwtCheckBox: JCheckBox? = null
    private var googleCheckBox: JCheckBox? = null
    private var httpSignatureCheckBox: JCheckBox? = null
    private var abacCheckBox: JCheckBox? = null
    private var seExtrasRow: Row? = null
    private var webClientCheckBox: JCheckBox? = null
    private var faultToleranceCheckBox: JCheckBox? = null
    private var corsCheckBox: JCheckBox? = null
    private var coherenceCheckBox: JCheckBox? = null
    private var metricsCheckBox: JCheckBox? = null
    private var mpMetricsProviderRow: Row? = null
    private var metricsProviderCombo: ComboBox<StarterOption>? = null
    private var metricsBuiltinCheckBox: JCheckBox? = null
    private var healthCheckBox: JCheckBox? = null
    private var healthBuiltinCheckBox: JCheckBox? = null
    private var tracingCheckBox: JCheckBox? = null
    private var tracingProviderCombo: ComboBox<StarterOption>? = null
    private var dockerCheckBox: JCheckBox? = null
    private var nativeImageCheckBox: JCheckBox? = null
    private var jlinkCheckBox: JCheckBox? = null
    private var kubernetesCheckBox: JCheckBox? = null
    private var jpmsCheckBox: JCheckBox? = null

    init {
      updateOptions(currentOptions)
    }

    override fun addFieldsBefore(layout: Panel) {
      layout.row("Flavor:") {
        val flavorButton = segmentedButton(flavorOptions()) { option ->
          text = option.label
        }
        flavorSegmentedButton = flavorButton
        selectedOption(flavorOptions(), currentOptions.flavor)?.let { flavorButton.selectedItem = it }
        flavorButton.whenItemSelectedFromUi { selectedFlavorOption ->
          if (syncing) {
            return@whenItemSelectedFromUi
          }
          val selectedFlavor = selectedFlavorOption.value
          val selectedAppType = currentOptions.appType.takeIf { appTypes(selectedFlavor).hasValue(it) }
            ?: HELIDON_QUICKSTART_APP_TYPE
          setOptions(currentOptions.withStarterPreset(
            selectedFlavor = selectedFlavor,
            selectedAppType = selectedAppType,
            model = metadataModel
          ))
        }
      }
      layout.row("Application type:") {
        val appTypeCell = comboBox(appTypeModel, starterOptionRenderer())
        appTypeCombo = appTypeCell.component
        appTypeCell.component.addActionListener {
          if (syncing) return@addActionListener
          val selectedAppType = appTypeCell.component.selectedStarterValue() ?: return@addActionListener
          setOptions(currentOptions.withStarterPreset(selectedAppType = selectedAppType, model = metadataModel))
        }
      }
    }

    override fun addFieldsAfter(layout: Panel) {
      val starterOptionsPanel = panel {
        groupRowsRange("Media") {
          row("Media:") {
            checkBox("JSON").component.apply {
              jsonCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(media = currentOptions.media.toggle("json", isSelected)))
              }
            }
            checkBox("Multipart").component.apply {
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
        }

        groupRowsRange("Data") {
          row("Database:") {
            checkBox("Database").component.apply {
              databaseCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(database = isSelected))
              }
            }
            label("Server")
            val databaseCell = comboBox(databaseServerModel, starterOptionRenderer())
            databaseServerCombo = databaseCell.component
            databaseCell.component.addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(databaseServer = databaseCell.component.selectedStarterValue() ?: currentOptions.databaseServer))
            }
          }
          mpDatabaseRows = rowsRange {
            row("JPA:") {
              val jpaCell = comboBox(jpaModel, starterOptionRenderer())
              jpaCombo = jpaCell.component
              jpaCell.component.addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(jpaImplementation = jpaCell.component.selectedStarterValue() ?: currentOptions.jpaImplementation))
              }
              label("Connection pool")
              val cpCell = comboBox(connectionPoolModel, starterOptionRenderer())
              connectionPoolCombo = cpCell.component
              cpCell.component.addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(connectionPool = cpCell.component.selectedStarterValue() ?: currentOptions.connectionPool))
              }
              checkBox("Auto DDL").component.apply {
                autoDdlCheckBox = this
                addActionListener {
                  if (syncing) return@addActionListener
                  setOptions(currentOptions.copy(autoDdl = isSelected))
                }
              }
            }
            row("Persistence unit:") {
              val puCell = textField()
              persistenceUnitField = puCell.component
              puCell.component.addTextChangeListener {
                if (!syncing) {
                  setOptions(currentOptions.copy(persistenceUnitName = puCell.component.text.ifBlank { "pu1" }))
                }
              }
            }
            row("Data source:") {
              val dsCell = textField()
              dataSourceField = dsCell.component
              dsCell.component.addTextChangeListener {
                if (!syncing) {
                  setOptions(currentOptions.copy(dataSourceName = dsCell.component.text.ifBlank { "ds1" }))
                }
              }
            }
          }
        }

        groupRowsRange("Security") {
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
          row("Providers:") {
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
        }

        groupRowsRange("Extras") {
          seExtrasRow = row("SE extras:") {
            checkBox("WebClient").component.apply {
              webClientCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("webclient", isSelected)))
              }
            }
          }
          row("Common extras:") {
            checkBox("Fault Tolerance").component.apply {
              faultToleranceCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(extras = currentOptions.extras.toggle("fault-tolerance", isSelected)))
              }
            }
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
        }

        groupRowsRange("Observability") {
          row("Metrics:") {
            checkBox("Metrics").component.apply {
              metricsCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(metrics = isSelected))
              }
            }
            checkBox("Built-in Metrics").component.apply {
              metricsBuiltinCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(metricsBuiltin = isSelected))
              }
            }
          }
          mpMetricsProviderRow = row("Metrics provider:") {
            val metricsCell = comboBox(metricsProviderModel, starterOptionRenderer())
            metricsProviderCombo = metricsCell.component
            metricsCell.component.addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(metricsProvider = metricsCell.component.selectedStarterValue() ?: currentOptions.metricsProvider))
            }
          }
          row("Health:") {
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
          }
          row("Tracing:") {
            checkBox("Tracing").component.apply {
              tracingCheckBox = this
              addActionListener {
                if (syncing) return@addActionListener
                setOptions(currentOptions.copy(tracing = isSelected))
              }
            }
            val tracingCell = comboBox(tracingProviderModel, starterOptionRenderer())
            tracingProviderCombo = tracingCell.component
            tracingCell.component.addActionListener {
              if (syncing) return@addActionListener
              setOptions(currentOptions.copy(tracingProvider = tracingCell.component.selectedStarterValue() ?: currentOptions.tracingProvider))
            }
          }
        }

        groupRowsRange("Packaging") {
          row("Container:") {
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
          row("Deployment:") {
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
      }
      layout.group("Features") {
        row {
          cell(starterOptionsScrollPane(starterOptionsPanel)).align(Align.FILL).resizableColumn()
        }.resizableRow()
      }
      layout.row {
        hyperLink(HelidonBundle.message("helidon.se.overview"), "https://helidon.io/docs/v4/#/se/introduction")
      }
      refreshControls()
      HelidonStarterMetadataModelProvider.refresh { refreshedMetadataModel ->
        if (metadataModel == refreshedMetadataModel) {
          return@refresh
        }
        metadataModel = refreshedMetadataModel
        setOptions(currentOptions.normalizedForStarter(metadataModel))
      }
    }

    private fun refreshControls() {
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
        val availableFlavorOptions = flavorOptions()
        val availableAppTypeOptions = appTypes(flavor)
        val availableMediaOptions = metadataMediaOptions(flavor)
        val availableJsonLibraryOptions = jsonLibraries(flavor)
        val availableDatabaseServerOptions = databaseServers(flavor)
        val availableJpaOptions = jpaImplementations()
        val availableConnectionPoolOptions = connectionPools()
        val availableAuthenticationProviderOptions = metadataModel.authenticationProviders
        val availableAuthorizationProviderOptions = metadataModel.authorizationProviders
        val availableExtraOptions = extras(flavor)
        val availableMetricsProviderOptions = metricsProviders()
        val availableTracingProviderOptions = tracingProviders()

        flavorSegmentedButton?.items = availableFlavorOptions
        appTypeModel.replaceWith(availableAppTypeOptions)
        jsonLibraryModel.replaceWith(availableJsonLibraryOptions)
        databaseServerModel.replaceWith(availableDatabaseServerOptions)
        jpaModel.replaceWith(availableJpaOptions)
        connectionPoolModel.replaceWith(availableConnectionPoolOptions)
        metricsProviderModel.replaceWith(availableMetricsProviderOptions)
        tracingProviderModel.replaceWith(availableTracingProviderOptions)

        selectedOption(availableFlavorOptions, flavor)?.let { flavorSegmentedButton?.selectedItem = it }
        appTypeCombo?.selectedItem = selectedOption(availableAppTypeOptions, appType)
        jsonCheckBox?.isSelected = "json" in currentOptions.media
        multipartCheckBox?.isSelected = "multipart" in currentOptions.media
        jsonLibraryCombo?.selectedItem = selectedOption(availableJsonLibraryOptions, currentOptions.jsonLibrary)
        databaseCheckBox?.isSelected = currentOptions.database
        databaseServerCombo?.selectedItem = selectedOption(availableDatabaseServerOptions, currentOptions.databaseServer)
        jpaCombo?.selectedItem = selectedOption(availableJpaOptions, currentOptions.jpaImplementation)
        connectionPoolCombo?.selectedItem = selectedOption(availableConnectionPoolOptions, currentOptions.connectionPool)
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
        metricsProviderCombo?.selectedItem = selectedOption(availableMetricsProviderOptions, currentOptions.metricsProvider)
        metricsBuiltinCheckBox?.isSelected = currentOptions.metricsBuiltin
        healthCheckBox?.isSelected = currentOptions.health
        healthBuiltinCheckBox?.isSelected = currentOptions.healthBuiltin
        tracingCheckBox?.isSelected = currentOptions.tracing
        tracingProviderCombo?.selectedItem = selectedOption(availableTracingProviderOptions, currentOptions.tracingProvider)
        dockerCheckBox?.isSelected = currentOptions.docker
        nativeImageCheckBox?.isSelected = currentOptions.dockerNativeImage
        jlinkCheckBox?.isSelected = currentOptions.dockerJlinkImage
        kubernetesCheckBox?.isSelected = currentOptions.kubernetes
        jpmsCheckBox?.isSelected = currentOptions.jpms

        mpDatabaseRows?.visible(mpDatabaseOptionsVisible)
        seExtrasRow?.visible(custom && flavor == HELIDON_SE_FLAVOR)
        mpMetricsProviderRow?.visible(custom && currentOptions.metrics && flavor == HELIDON_MP_FLAVOR)

        jsonCheckBox?.isVisible = availableMediaOptions.hasValue("json")
        multipartCheckBox?.isVisible = availableMediaOptions.hasValue("multipart")
        jsonLibraryCombo?.isVisible = availableJsonLibraryOptions.isNotEmpty()
        databaseServerCombo?.isVisible = availableDatabaseServerOptions.isNotEmpty()
        jpaCombo?.isVisible = availableJpaOptions.isNotEmpty()
        connectionPoolCombo?.isVisible = availableConnectionPoolOptions.isNotEmpty()
        oidcCheckBox?.isVisible = availableAuthenticationProviderOptions.hasValue("oidc")
        jwtCheckBox?.isVisible = availableAuthenticationProviderOptions.hasValue("jwt")
        googleCheckBox?.isVisible = availableAuthenticationProviderOptions.hasValue("google")
        httpSignatureCheckBox?.isVisible = availableAuthenticationProviderOptions.hasValue("http-signature")
        abacCheckBox?.isVisible = availableAuthorizationProviderOptions.hasValue("abac")
        webClientCheckBox?.isVisible = availableExtraOptions.hasValue("webclient")
        faultToleranceCheckBox?.isVisible = availableExtraOptions.hasValue("fault-tolerance")
        corsCheckBox?.isVisible = availableExtraOptions.hasValue("cors")
        coherenceCheckBox?.isVisible = availableExtraOptions.hasValue("coherence")
        metricsProviderCombo?.isVisible = availableMetricsProviderOptions.isNotEmpty()
        tracingProviderCombo?.isVisible = availableTracingProviderOptions.isNotEmpty()

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

    private fun setOptions(newOptions: HelidonStarterOptions) {
      currentOptions = newOptions.normalizedForStarter(metadataModel)
      updateOptions(currentOptions)
      refreshControls()
    }

    private fun flavorOptions(): List<StarterOption> =
      metadataModel.flavors

    private fun metricsProviders(): List<StarterOption> =
      metadataModel.metricsProviders

    private fun tracingProviders(): List<StarterOption> =
      metadataModel.tracingProviders

    private fun jpaImplementations(): List<StarterOption> =
      metadataModel.jpaImplementations

    private fun connectionPools(): List<StarterOption> =
      metadataModel.connectionPools

    private fun appTypes(flavor: String): List<StarterOption> =
      metadataModel.appTypes(flavor)

    private fun metadataMediaOptions(flavor: String): List<StarterOption> =
      metadataModel.media(flavor)

    private fun jsonLibraries(flavor: String): List<StarterOption> =
      metadataModel.jsonLibraries(flavor)

    private fun databaseServers(flavor: String): List<StarterOption> =
      metadataModel.databaseServers(flavor)

    private fun extras(flavor: String): List<StarterOption> =
      metadataModel.extras(flavor)

    private fun starterOptionsScrollPane(content: JComponent) =
      ScrollPaneFactory.createScrollPane(content, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = null
        viewport.border = null
        viewport.isOpaque = false
        isOpaque = false
        preferredSize = Dimension(content.preferredSize.width, JBUI.scale(HELIDON_STARTER_OPTIONS_VISIBLE_HEIGHT))
        minimumSize = Dimension(0, JBUI.scale(HELIDON_STARTER_OPTIONS_MIN_HEIGHT))
      }

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

    private fun List<StarterOption>.hasValue(value: String): Boolean =
      any { it.value == value }

    private fun JTextField.addTextChangeListener(listener: () -> Unit) {
      document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener()
        override fun removeUpdate(e: DocumentEvent) = listener()
        override fun changedUpdate(e: DocumentEvent) = listener()
      })
    }
  }
}
