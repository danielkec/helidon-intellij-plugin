// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.starters.local.GeneratorAsset
import com.intellij.ide.starters.local.GeneratorEmptyDirectory
import com.intellij.ide.starters.local.GeneratorFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val HELIDON_STARTER_ROOT = "https://helidon.io/starter"
private const val HELIDON_STARTER_API = "https://helidon.io/api/starter"
internal const val HELIDON_SE_FLAVOR = "se"
internal const val HELIDON_MP_FLAVOR = "mp"
internal const val HELIDON_QUICKSTART_APP_TYPE = "quickstart"
internal const val HELIDON_DATABASE_APP_TYPE = "database"
internal const val HELIDON_CUSTOM_APP_TYPE = "custom"
internal const val HELIDON_OCI_APP_TYPE = "oci"
private const val HELIDON_GOOGLE_AUTHENTICATION_PROVIDER = "google"

internal data class HelidonStarterOption(val value: String, val label: String)

internal fun wizardAuthenticationProviders(options: List<HelidonStarterOption>): List<HelidonStarterOption> =
  options.filterNot { it.value == HELIDON_GOOGLE_AUTHENTICATION_PROVIDER }

internal data class HelidonStarterMetadataModel(
  val buildSystems: List<HelidonStarterOption>,
  val flavors: List<HelidonStarterOption>,
  val appTypesByFlavor: Map<String, List<HelidonStarterOption>>,
  val mediaByFlavor: Map<String, List<HelidonStarterOption>>,
  val jsonLibrariesByFlavor: Map<String, List<HelidonStarterOption>>,
  val databaseServersByFlavor: Map<String, List<HelidonStarterOption>>,
  val jpaImplementations: List<HelidonStarterOption>,
  val connectionPools: List<HelidonStarterOption>,
  val authenticationProviders: List<HelidonStarterOption>,
  val authorizationProviders: List<HelidonStarterOption>,
  val extrasByFlavor: Map<String, List<HelidonStarterOption>>,
  val metricsProviders: List<HelidonStarterOption>,
  val tracingProviders: List<HelidonStarterOption>
) {
  fun appTypes(flavor: String): List<HelidonStarterOption> = appTypesByFlavor[flavor].orEmpty()
  fun media(flavor: String): List<HelidonStarterOption> = mediaByFlavor[flavor].orEmpty()
  fun jsonLibraries(flavor: String): List<HelidonStarterOption> = jsonLibrariesByFlavor[flavor].orEmpty()
  fun databaseServers(flavor: String): List<HelidonStarterOption> = databaseServersByFlavor[flavor].orEmpty()
  fun extras(flavor: String): List<HelidonStarterOption> = extrasByFlavor[flavor].orEmpty()

  fun supportsBuildSystem(buildSystem: String): Boolean = buildSystem in buildSystems.values()
  fun supportsFlavor(flavor: String): Boolean = flavor in flavors.values()
  fun supportsApplicationType(flavor: String, appType: String): Boolean = appType in appTypes(flavor).values()

  fun normalize(options: HelidonStarterOptions): HelidonStarterOptions {
    val normalizedFlavor = normalizeRequiredValue(options.flavor, flavors, HELIDON_SE_FLAVOR)
    val normalizedAppType = normalizeRequiredValue(options.appType, appTypes(normalizedFlavor), HELIDON_QUICKSTART_APP_TYPE)
    val base = options.copy(
      flavor = normalizedFlavor,
      appType = normalizedAppType,
      media = options.media.filterValues(media(normalizedFlavor)),
      jsonLibrary = normalizeValue(options.jsonLibrary, jsonLibraries(normalizedFlavor), helidonStarterDefaultJsonLibrary(normalizedFlavor, this)),
      databaseServer = normalizeValue(options.databaseServer, databaseServers(normalizedFlavor), "h2"),
      jpaImplementation = normalizeValue(options.jpaImplementation, jpaImplementations, "hibernate"),
      connectionPool = normalizeValue(options.connectionPool, connectionPools, "hikaricp"),
      authenticationProviders = options.authenticationProviders.filterValues(authenticationProviders),
      authorizationProviders = options.authorizationProviders.filterValues(authorizationProviders),
      extras = options.extras.filterValues(extras(normalizedFlavor)),
      metricsProvider = normalizeValue(options.metricsProvider, metricsProviders, "microprofile"),
      tracingProvider = normalizeValue(options.tracingProvider, tracingProviders, "jaeger")
    )

    val preset = when (normalizedAppType) {
      HELIDON_QUICKSTART_APP_TYPE -> base.starterSamplePreset(database = false)
      HELIDON_DATABASE_APP_TYPE -> base.starterSamplePreset(database = true)
      HELIDON_OCI_APP_TYPE -> base.copy(
        media = emptyList(),
        database = false,
        security = false,
        authenticationProviders = emptyList(),
        authorizationProviders = emptyList(),
        extras = emptyList(),
        metrics = true,
        metricsBuiltin = true,
        health = true,
        healthBuiltin = true,
        tracing = false,
        docker = true,
        dockerNativeImage = false,
        dockerJlinkImage = false,
        kubernetes = true,
        jpms = false
      )
      else -> base
    }

    return preset.copy(
      media = preset.media.filterValues(media(normalizedFlavor)),
      jsonLibrary = normalizeValue(preset.jsonLibrary, jsonLibraries(normalizedFlavor), helidonStarterDefaultJsonLibrary(normalizedFlavor, this)),
      databaseServer = normalizeValue(preset.databaseServer, databaseServers(normalizedFlavor), "h2"),
      jpaImplementation = normalizeValue(preset.jpaImplementation, jpaImplementations, "hibernate"),
      connectionPool = normalizeValue(preset.connectionPool, connectionPools, "hikaricp"),
      authenticationProviders = preset.authenticationProviders.filterValues(authenticationProviders),
      authorizationProviders = preset.authorizationProviders.filterValues(authorizationProviders),
      extras = preset.extras.filterValues(extras(normalizedFlavor)),
      metricsProvider = normalizeValue(preset.metricsProvider, metricsProviders, "microprofile"),
      tracingProvider = normalizeValue(preset.tracingProvider, tracingProviders, "jaeger")
    )
  }

  private fun normalizeValue(value: String, options: List<HelidonStarterOption>, preferred: String): String {
    val values = options.values()
    return when {
      value in values -> value
      preferred in values -> preferred
      else -> values.firstOrNull() ?: value
    }
  }

  private fun normalizeRequiredValue(value: String, options: List<HelidonStarterOption>, preferred: String): String {
    val values = options.values()
    return when {
      value in values -> value
      preferred in values -> preferred
      else -> value
    }
  }

  private fun List<String>.filterValues(options: List<HelidonStarterOption>): List<String> {
    val values = options.values()
    if (values.isEmpty()) {
      return emptyList()
    }
    return filter { it in values }
  }
}

private val DEFAULT_OPTION_LABELS = mapOf(
  HELIDON_SE_FLAVOR to "Helidon SE",
  HELIDON_MP_FLAVOR to "Helidon MP",
  HELIDON_QUICKSTART_APP_TYPE to "Quickstart",
  HELIDON_DATABASE_APP_TYPE to "Database",
  HELIDON_CUSTOM_APP_TYPE to "Custom",
  HELIDON_OCI_APP_TYPE to "OCI",
  "json" to "JSON",
  "multipart" to "Multipart",
  "jsonp" to "JSON-P",
  "jackson" to "Jackson",
  "jsonb" to "JSON-B",
  "h2" to "H2",
  "mysql" to "MySQL",
  "oracledb" to "Oracle DB",
  "mongodb" to "MongoDB",
  "hibernate" to "Hibernate",
  "eclipselink" to "EclipseLink",
  "hikaricp" to "HikariCP",
  "ucp" to "UCP",
  "oidc" to "OIDC",
  "jwt" to "JWT",
  "google" to "Google Login",
  "http-signature" to "HTTP Signature",
  "abac" to "ABAC",
  "webclient" to "WebClient",
  "fault-tolerance" to "Fault Tolerance",
  "cors" to "CORS",
  "coherence" to "Coherence",
  "microprofile" to "MicroProfile",
  "micrometer" to "Micrometer",
  "jaeger" to "Jaeger",
  "zipkin" to "Zipkin"
)

internal val DEFAULT_HELIDON_STARTER_METADATA_MODEL = HelidonStarterMetadataModel(
  buildSystems = listOf(option("maven")),
  flavors = listOf(option(HELIDON_SE_FLAVOR), option(HELIDON_MP_FLAVOR)),
  appTypesByFlavor = mapOf(
    HELIDON_SE_FLAVOR to listOf(option(HELIDON_QUICKSTART_APP_TYPE), option(HELIDON_DATABASE_APP_TYPE), option(HELIDON_CUSTOM_APP_TYPE)),
    HELIDON_MP_FLAVOR to listOf(
      option(HELIDON_QUICKSTART_APP_TYPE),
      option(HELIDON_DATABASE_APP_TYPE),
      option(HELIDON_CUSTOM_APP_TYPE),
      option(HELIDON_OCI_APP_TYPE)
    )
  ),
  mediaByFlavor = mapOf(
    HELIDON_SE_FLAVOR to listOf(option("json"), option("multipart")),
    HELIDON_MP_FLAVOR to listOf(option("json"), option("multipart"))
  ),
  jsonLibrariesByFlavor = mapOf(
    HELIDON_SE_FLAVOR to listOf(option("jsonp"), option("jackson"), option("jsonb")),
    HELIDON_MP_FLAVOR to listOf(option("jackson"), option("jsonb"))
  ),
  databaseServersByFlavor = mapOf(
    HELIDON_SE_FLAVOR to listOf(option("h2"), option("mysql"), option("oracledb"), option("mongodb")),
    HELIDON_MP_FLAVOR to listOf(option("h2"), option("mysql"), option("oracledb"))
  ),
  jpaImplementations = listOf(option("hibernate"), option("eclipselink")),
  connectionPools = listOf(option("hikaricp"), option("ucp")),
  authenticationProviders = listOf(option("oidc"), option("jwt"), option("google"), option("http-signature")),
  authorizationProviders = listOf(option("abac")),
  extrasByFlavor = mapOf(
    HELIDON_SE_FLAVOR to listOf(option("webclient"), option("fault-tolerance"), option("cors"), option("coherence")),
    HELIDON_MP_FLAVOR to listOf(option("fault-tolerance"), option("cors"), option("coherence"))
  ),
  metricsProviders = listOf(option("microprofile"), option("micrometer")),
  tracingProviders = listOf(option("jaeger"), option("zipkin"))
)

internal object HelidonStarterMetadataModelProvider {
  @Volatile
  private var cachedModel: HelidonStarterMetadataModel = DEFAULT_HELIDON_STARTER_METADATA_MODEL

  var provider: () -> HelidonStarterMetadataModel = { HelidonStarterClient().metadataModel() }
    set(value) {
      field = value
      cachedModel = DEFAULT_HELIDON_STARTER_METADATA_MODEL
    }

  fun current(): HelidonStarterMetadataModel = cachedModel

  fun refresh(onLoaded: (HelidonStarterMetadataModel) -> Unit) {
    AppExecutorUtil.getAppExecutorService().submit {
      val model = try {
        provider()
      }
      catch (_: Exception) {
        DEFAULT_HELIDON_STARTER_METADATA_MODEL
      }
      cachedModel = model
      ApplicationManager.getApplication().invokeLater({
        onLoaded(model)
      }, ModalityState.any())
    }
  }
}

private fun option(value: String, label: String = DEFAULT_OPTION_LABELS[value] ?: value): HelidonStarterOption =
  HelidonStarterOption(value, label)

private fun List<HelidonStarterOption>.values(): List<String> = map { it.value }

internal data class HelidonStarterRequest(
  val groupId: String,
  val artifactId: String,
  val projectVersion: String,
  val packageName: String,
  val options: HelidonStarterOptions = HelidonStarterOptions()
)

internal data class HelidonStarterOptions(
  val flavor: String = HELIDON_SE_FLAVOR,
  val appType: String = HELIDON_QUICKSTART_APP_TYPE,
  val media: List<String> = listOf("json"),
  val jsonLibrary: String = "jsonp",
  val database: Boolean = false,
  val databaseServer: String = "h2",
  val jpaImplementation: String = "hibernate",
  val connectionPool: String = "hikaricp",
  val autoDdl: Boolean = false,
  val persistenceUnitName: String = "pu1",
  val dataSourceName: String = "ds1",
  val security: Boolean = false,
  val authenticationProviders: List<String> = emptyList(),
  val authorizationProviders: List<String> = emptyList(),
  val extras: List<String> = emptyList(),
  val metrics: Boolean = true,
  val metricsProvider: String = "microprofile",
  val metricsBuiltin: Boolean = true,
  val health: Boolean = true,
  val healthBuiltin: Boolean = true,
  val tracing: Boolean = false,
  val tracingProvider: String = "jaeger",
  val docker: Boolean = true,
  val dockerNativeImage: Boolean = true,
  val dockerJlinkImage: Boolean = true,
  val kubernetes: Boolean = true,
  val jpms: Boolean = false
)

internal fun helidonStarterAppTypes(flavor: String): List<String> =
  DEFAULT_HELIDON_STARTER_METADATA_MODEL.appTypes(flavor).values()

internal fun helidonStarterJsonLibraries(flavor: String): List<String> =
  DEFAULT_HELIDON_STARTER_METADATA_MODEL.jsonLibraries(flavor).values()

internal fun helidonStarterDefaultJsonLibrary(
  flavor: String,
  model: HelidonStarterMetadataModel = DEFAULT_HELIDON_STARTER_METADATA_MODEL
): String {
  val preferred = if (flavor == HELIDON_MP_FLAVOR) "jsonb" else "jsonp"
  val values = model.jsonLibraries(flavor).values()
  return when {
    preferred in values -> preferred
    values.isNotEmpty() -> values.first()
    else -> preferred
  }
}

internal fun helidonStarterDatabaseServers(flavor: String): List<String> =
  DEFAULT_HELIDON_STARTER_METADATA_MODEL.databaseServers(flavor).values()

internal fun helidonStarterExtras(flavor: String): List<String> =
  DEFAULT_HELIDON_STARTER_METADATA_MODEL.extras(flavor).values()

internal fun HelidonStarterOptions.withStarterPreset(
  selectedFlavor: String = flavor,
  selectedAppType: String = appType,
  model: HelidonStarterMetadataModel = DEFAULT_HELIDON_STARTER_METADATA_MODEL
): HelidonStarterOptions {
  val normalizedFlavor = selectedFlavor.takeIf { model.supportsFlavor(it) } ?: HELIDON_SE_FLAVOR
  val normalizedAppType = selectedAppType.takeIf { model.supportsApplicationType(normalizedFlavor, it) } ?: HELIDON_QUICKSTART_APP_TYPE
  val base = copy(
    flavor = normalizedFlavor,
    appType = normalizedAppType,
    jsonLibrary = jsonLibrary.takeIf { it in model.jsonLibraries(normalizedFlavor).values() }
      ?: helidonStarterDefaultJsonLibrary(normalizedFlavor, model),
    databaseServer = databaseServer.takeIf { it in model.databaseServers(normalizedFlavor).values() } ?: "h2",
    extras = extras.filter { it in model.extras(normalizedFlavor).values() }
  )

  return when (normalizedAppType) {
    HELIDON_QUICKSTART_APP_TYPE -> base.starterSamplePreset(database = false)
    HELIDON_DATABASE_APP_TYPE -> base.starterSamplePreset(database = true)
    HELIDON_CUSTOM_APP_TYPE -> base.copy(
      media = listOf("json"),
      database = false,
      security = false,
      authenticationProviders = emptyList(),
      authorizationProviders = emptyList(),
      extras = emptyList(),
      metrics = false,
      health = false,
      tracing = false,
      docker = false,
      dockerNativeImage = false,
      dockerJlinkImage = false,
      kubernetes = false,
      jpms = false
    )
    HELIDON_OCI_APP_TYPE -> base.copy(
      media = emptyList(),
      database = false,
      security = false,
      authenticationProviders = emptyList(),
      authorizationProviders = emptyList(),
      extras = emptyList(),
      metrics = true,
      metricsBuiltin = true,
      health = true,
      healthBuiltin = true,
      tracing = false,
      docker = true,
      dockerNativeImage = false,
      dockerJlinkImage = false,
      kubernetes = true,
      jpms = false
    )
    else -> base
  }.normalizedForStarter(model)
}

internal fun HelidonStarterOptions.normalizedForStarter(
  model: HelidonStarterMetadataModel = DEFAULT_HELIDON_STARTER_METADATA_MODEL
): HelidonStarterOptions = model.normalize(this)

private fun HelidonStarterOptions.starterSamplePreset(database: Boolean): HelidonStarterOptions =
  copy(
    media = listOf("json"),
    database = database,
    security = false,
    authenticationProviders = emptyList(),
    authorizationProviders = emptyList(),
    extras = emptyList(),
    metrics = true,
    metricsBuiltin = true,
    health = true,
    healthBuiltin = true,
    tracing = false,
    docker = true,
    dockerNativeImage = true,
    dockerJlinkImage = true,
    kubernetes = true,
    jpms = false
  )

internal data class HelidonStarterProject(
  val assets: List<GeneratorAsset>,
  val filesToOpen: List<String>
)

internal fun interface HelidonStarterProjectGenerator {
  fun generate(request: HelidonStarterRequest): HelidonStarterProject
}

internal object HelidonStarterProjectGeneratorProvider {
  var generator: HelidonStarterProjectGenerator = HelidonStarterClient()
}

internal class HelidonStarterClient(
  private val starterRoot: String = HELIDON_STARTER_ROOT,
  private val apiRoot: String = HELIDON_STARTER_API,
  private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : HelidonStarterProjectGenerator {

  override fun generate(request: HelidonStarterRequest): HelidonStarterProject {
    val starterVersion = resolveStarterVersion()
    val metadata = readText(metadataUrl(starterVersion))
    val starterMetadata = HelidonStarterMetadata(metadata)
    val model = starterMetadata.model()
    val normalizedRequest = request.copy(options = request.options.normalizedForStarter(model))
    starterMetadata.validate(normalizedRequest.options, starterVersion, model)

    val zipBytes = readBytes(generateUrl(starterVersion, normalizedRequest))
    val assets = HelidonStarterZipAssets.toAssets(zipBytes)
    return HelidonStarterProject(assets, filesToOpen(assets))
  }

  fun metadataModel(): HelidonStarterMetadataModel {
    val starterVersion = resolveStarterVersion()
    val metadata = readText(metadataUrl(starterVersion))
    return HelidonStarterMetadata(metadata).model()
  }

  private fun resolveStarterVersion(): String {
    val url = URI(starterRoot.trimEnd('/')).toURL()
    val connection = openConnection(url, followRedirects = false)
    try {
      val responseCode = connection.responseCode
      if (responseCode in 300..399) {
        val location = connection.getHeaderField("Location")
          ?: throw IOException("Helidon Starter redirect from $url did not include a Location header")
        return extractStarterVersion(url, location)
      }
      throw IOException("Helidon Starter did not redirect $url to a versioned starter; HTTP $responseCode")
    }
    finally {
      connection.disconnect()
    }
  }

  private fun extractStarterVersion(baseUrl: URL, location: String): String {
    val resolved = URI(baseUrl.toString()).resolve(location)
    val segments = resolved.path.trim('/').split('/').filter(String::isNotBlank)
    if (segments.size < 2 || segments[segments.lastIndex - 1] != "starter") {
      throw IOException("Helidon Starter redirect target $resolved is not a versioned starter URL")
    }
    return segments.last()
  }

  private fun metadataUrl(helidonVersion: String): URL =
    URI("${apiRoot.trimEnd('/')}/${encode(helidonVersion)}").toURL()

  private fun generateUrl(helidonVersion: String, request: HelidonStarterRequest): URL {
    val query = request.parameters().joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    return URI("${apiRoot.trimEnd('/')}/${encode(helidonVersion)}/generate?$query").toURL()
  }

  private fun HelidonStarterRequest.parameters(): List<Pair<String, String>> =
    buildList {
      add("flavor" to options.flavor)
      add("app-type" to options.appType)
      add("build-system" to "maven")
      add("groupId" to groupId)
      add("artifactId" to artifactId)
      add("version" to projectVersion)
      add("package" to packageName)
      addAll(options.parameters())
    }

  private fun HelidonStarterOptions.parameters(): List<Pair<String, String>> =
    buildList {
      if (appType == HELIDON_OCI_APP_TYPE) {
        add("db" to database.toString())
        add("docker" to docker.toString())
        add("docker.native-image" to dockerNativeImage.toString())
        add("docker.jlink-image" to dockerJlinkImage.toString())
        add("k8s" to kubernetes.toString())
        add("jpms" to jpms.toString())
      }
      else {
        addList("media", media)
        if ("json" in media) {
          add("media.json-lib" to jsonLibrary)
        }

        add("db" to database.toString())
        if (database) {
          if (flavor == HELIDON_MP_FLAVOR) {
            add("db.jpa-impl" to jpaImplementation)
            add("db.cp" to connectionPool)
            add("db.server" to databaseServer)
            add("db.auto-ddl" to autoDdl.toString())
            add("db.pu-name" to persistenceUnitName)
            add("db.ds-name" to dataSourceName)
          }
          else {
            add("db.server" to databaseServer)
          }
        }

        add("security" to security.toString())
        if (security) {
          addList("security.atn", authenticationProviders)
          addList("security.atz", authorizationProviders)
        }

        addList("extra", extras)
        add("metrics" to metrics.toString())
        if (metrics) {
          if (flavor == HELIDON_MP_FLAVOR) {
            add("metrics.provider" to metricsProvider)
          }
          add("metrics.builtin" to metricsBuiltin.toString())
        }
        add("health" to health.toString())
        if (health) {
          add("health.builtin" to healthBuiltin.toString())
        }
        add("tracing" to tracing.toString())
        if (tracing) {
          add("tracing.provider" to tracingProvider)
        }
        add("docker" to docker.toString())
        if (docker) {
          add("docker.native-image" to dockerNativeImage.toString())
          add("docker.jlink-image" to dockerJlinkImage.toString())
        }
        add("k8s" to kubernetes.toString())
        add("jpms" to jpms.toString())
      }
    }

  private fun MutableList<Pair<String, String>>.addList(name: String, values: List<String>) {
    values.forEach { add(name to it) }
  }

  private fun readText(url: URL): String =
    readBytes(url).toString(StandardCharsets.UTF_8)

  private fun readBytes(url: URL): ByteArray {
    val connection = openConnection(url, followRedirects = true)
    try {
      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        val error = connection.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
        throw IOException("Helidon Starter returned HTTP $responseCode for $url${if (error.isBlank()) "" else ": $error"}")
      }
      return connection.inputStream.use { it.readBytes() }
    }
    finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: URL, followRedirects: Boolean): HttpURLConnection {
    val connection = connectionFactory(url)
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = followRedirects
    return connection
  }

  private fun filesToOpen(assets: List<GeneratorAsset>): List<String> {
    val paths = assets.map { it.relativePath }.toSet()
    val files = mutableListOf<String>()
    if ("pom.xml" in paths) {
      files.add("pom.xml")
    }
    val sourceFile = paths.firstOrNull { it.endsWith("/GreetResource.java") }
      ?: paths.firstOrNull { it.endsWith("/Main.java") }
      ?: paths.firstOrNull { it.startsWith("src/main/java/") && it.endsWith(".java") }
    sourceFile?.let { files.add(it) }
    return files
  }

  private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
}

internal class HelidonStarterUnsupportedException(message: String) : IOException(message)

internal class HelidonStarterMetadata(metadata: String) {
  private val root: JsonObject = JsonParser.parseString(metadata).asJsonObject

  fun model(): HelidonStarterMetadataModel {
    val flavors = optionsFor(root, "flavor", null)
    val flavorValues = flavors.values()
    val appTypesByFlavor = flavorValues.associateWith { flavor ->
      val flavorOption = findOptionObject(root, "flavor", flavor)
      optionsFor(flavorOption, "app-type", flavor)
    }
    val mediaByFlavor = flavorValues.associateWith { flavor ->
      optionsFor(findOptionObject(root, "flavor", flavor), "media", flavor)
    }
    val jsonLibrariesByFlavor = flavorValues.associateWith { flavor ->
      optionsFor(findOptionObject(root, "flavor", flavor), "json-lib", flavor)
    }
    val databaseServersByFlavor = flavorValues.associateWith { flavor ->
      optionsFor(findOptionObject(root, "flavor", flavor), "server", flavor)
    }
    val extrasByFlavor = flavorValues.associateWith { flavor ->
      optionsFor(findOptionObject(root, "flavor", flavor), "extra", flavor)
    }
    val mpRoot = findOptionObject(root, "flavor", HELIDON_MP_FLAVOR)
    return HelidonStarterMetadataModel(
      buildSystems = optionsFor(root, "build-system", null),
      flavors = flavors,
      appTypesByFlavor = appTypesByFlavor,
      mediaByFlavor = mediaByFlavor,
      jsonLibrariesByFlavor = jsonLibrariesByFlavor,
      databaseServersByFlavor = databaseServersByFlavor,
      jpaImplementations = optionsFor(mpRoot ?: root, "jpa-impl", HELIDON_MP_FLAVOR),
      connectionPools = optionsFor(mpRoot ?: root, "cp", HELIDON_MP_FLAVOR),
      authenticationProviders = optionsFor(root, "atn", null),
      authorizationProviders = optionsFor(root, "atz", null),
      extrasByFlavor = extrasByFlavor,
      metricsProviders = optionsForNestedInput(root, "metrics", "provider", HELIDON_MP_FLAVOR),
      tracingProviders = optionsForNestedInput(root, "tracing", "provider", null)
    )
  }

  fun validate(options: HelidonStarterOptions, starterVersion: String, model: HelidonStarterMetadataModel = model()) {
    if (!model.supportsBuildSystem("maven")) {
      throw HelidonStarterUnsupportedException("Helidon Starter $starterVersion does not expose Maven generation")
    }
    if (!model.supportsFlavor(options.flavor)) {
      throw HelidonStarterUnsupportedException("Helidon Starter $starterVersion does not expose Helidon ${options.flavor.uppercase()}")
    }
    if (!model.supportsApplicationType(options.flavor, options.appType)) {
      throw HelidonStarterUnsupportedException(
        "Helidon Starter $starterVersion does not expose ${options.appType} application type for ${options.flavor.uppercase()}"
      )
    }
  }

  fun supportsBuildSystem(buildSystem: String): Boolean =
    findOption(root, "build-system", buildSystem)

  private fun optionsFor(element: JsonElement?, id: String, flavor: String?): List<HelidonStarterOption> {
    val options = mutableListOf<HelidonStarterOption>()
    traverse(element, flavor) { obj ->
      if (obj.get("id")?.asStringSafe() == id) {
        obj.getAsJsonArray("children")?.forEach { child ->
          if (child.isJsonObject && child.asJsonObject.get("kind")?.asStringSafe() == "option" && isVisible(child.asJsonObject, flavor)) {
            val value = child.asJsonObject.get("value")?.asStringSafe() ?: return@forEach
            val label = child.asJsonObject.get("name")?.asStringSafe() ?: DEFAULT_OPTION_LABELS[value] ?: value
            options.add(HelidonStarterOption(value, label))
          }
        }
      }
    }
    return options.distinctBy { it.value }
  }

  private fun optionsForNestedInput(element: JsonElement?, parentId: String, id: String, flavor: String?): List<HelidonStarterOption> {
    val options = mutableListOf<HelidonStarterOption>()
    traverse(element, flavor) { obj ->
      if (obj.get("id")?.asStringSafe() == parentId) {
        options.addAll(optionsFor(obj, id, flavor))
      }
    }
    return options.distinctBy { it.value }
  }

  private fun traverse(element: JsonElement?, flavor: String?, visit: (JsonObject) -> Unit) {
    traverse(element, flavor, mutableSetOf(), visit)
  }

  private fun traverse(element: JsonElement?, flavor: String?, visitedMethods: MutableSet<String>, visit: (JsonObject) -> Unit) {
    if (element == null || element.isJsonNull) {
      return
    }
    if (element.isJsonArray) {
      element.asJsonArray.forEach { traverse(it, flavor, visitedMethods, visit) }
      return
    }
    if (!element.isJsonObject) {
      return
    }

    val obj = element.asJsonObject
    if (!isVisible(obj, flavor)) {
      return
    }
    if (obj.get("kind")?.asStringSafe() == "call") {
      val method = obj.get("method")?.asStringSafe() ?: return
      if (visitedMethods.add(method)) {
        root.getAsJsonObject("methods")?.getAsJsonArray(method)?.forEach {
          traverse(it, flavor, visitedMethods, visit)
        }
        visitedMethods.remove(method)
      }
      return
    }

    visit(obj)
    obj.getAsJsonArray("children")?.forEach { traverse(it, flavor, visitedMethods, visit) }
  }

  private fun isVisible(obj: JsonObject, flavor: String?): Boolean {
    val condition = obj.get("if")?.asStringSafe() ?: return true
    val conditionFlavor = conditionFlavor(condition) ?: return true
    return flavor == null || flavor == conditionFlavor
  }

  private fun conditionFlavor(condition: String): String? {
    val expression = root.getAsJsonObject("expressions")?.getAsJsonArray(condition) ?: return null
    val hasFlavorVariable = expression.any {
      it.isJsonObject &&
        it.asJsonObject.get("kind")?.asStringSafe() == "variable" &&
        it.asJsonObject.get("value")?.asStringSafe() == "flavor"
    }
    val hasEquals = expression.any {
      it.isJsonObject &&
        it.asJsonObject.get("kind")?.asStringSafe() == "operator" &&
        it.asJsonObject.get("value")?.asStringSafe() == "=="
    }
    if (!hasFlavorVariable || !hasEquals) {
      return null
    }
    return expression.firstNotNullOfOrNull {
      if (it.isJsonObject && it.asJsonObject.get("kind")?.asStringSafe() == "literal") {
        it.asJsonObject.get("value")?.asStringSafe()
      }
      else {
        null
      }
    }
  }

  private fun supportsApplicationType(flavor: String, appType: String): Boolean {
    val flavorOption = findOptionObject(root, "flavor", flavor) ?: return false
    return findOption(flavorOption, "app-type", appType)
  }

  private fun findOption(element: JsonElement?, id: String, value: String): Boolean {
    return findOptionObject(element, id, value) != null
  }

  private fun findOptionObject(element: JsonElement?, id: String, value: String): JsonObject? {
    if (element == null || element.isJsonNull) {
      return null
    }
    if (element.isJsonObject) {
      val obj = element.asJsonObject
      if (obj.get("id")?.asStringSafe() == id) {
        val children = obj.getAsJsonArray("children") ?: return null
        children.forEach { child ->
          if (child.isJsonObject && child.asJsonObject.get("value")?.asStringSafe() == value) {
            return child.asJsonObject
          }
        }
      }
      obj.entrySet().forEach { entry ->
        findOptionObject(entry.value, id, value)?.let { return it }
      }
      return null
    }
    if (element.isJsonArray) {
      element.asJsonArray.forEach { child ->
        findOptionObject(child, id, value)?.let { return it }
      }
    }
    return null
  }

  private fun JsonElement.asStringSafe(): String? =
    takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

internal object HelidonStarterZipAssets {
  private val executablePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_EXECUTE
  )

  fun toAssets(zipBytes: ByteArray): List<GeneratorAsset> {
    val entries = readEntries(zipBytes)
    val root = commonRoot(entries.map { it.name })
    val assets = mutableListOf<GeneratorAsset>()
    val filePaths = mutableSetOf<String>()

    entries.filterNot { it.directory }.forEach { entry ->
      val relativePath = sanitize(entry.name.removeRoot(root)) ?: return@forEach
      if (isIdeaPath(relativePath)) {
        return@forEach
      }
      filePaths.add(relativePath)
      assets.add(GeneratorFile(relativePath, permissions(relativePath), entry.content))
    }

    entries.filter { it.directory }.forEach { entry ->
      val relativePath = sanitize(entry.name.removeRoot(root)) ?: return@forEach
      if (isIdeaPath(relativePath)) {
        return@forEach
      }
      if (filePaths.none { it.startsWith("$relativePath/") }) {
        assets.add(GeneratorEmptyDirectory(relativePath))
      }
    }

    return assets
  }

  private fun readEntries(zipBytes: ByteArray): List<ArchiveEntry> {
    val entries = mutableListOf<ArchiveEntry>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
      var entry: ZipEntry? = zip.nextEntry
      while (entry != null) {
        val name = entry.name
        if (!entry.isDirectory) {
          entries.add(ArchiveEntry(name, false, zip.readBytes()))
        }
        else {
          entries.add(ArchiveEntry(name.trimEnd('/'), true, ByteArray(0)))
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
    return entries
  }

  private fun commonRoot(paths: List<String>): String? {
    val firstSegments = paths.mapNotNull { it.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank) }.toSet()
    if (firstSegments.size != 1) {
      return null
    }
    val root = firstSegments.single()
    return if (paths.all { it == root || it.startsWith("$root/") }) root else null
  }

  private fun String.removeRoot(root: String?): String =
    if (root == null) this else removePrefix(root).removePrefix("/")

  private fun sanitize(path: String): String? {
    if (path.isBlank() || path.contains('\\')) {
      return null
    }
    val normalized = Paths.get(path).normalize()
    if (normalized.isAbsolute || normalized.startsWith("..")) {
      return null
    }
    return normalized.toString().replace(Path.of("").fileSystem.separator, "/").takeIf(String::isNotBlank)
  }

  private fun permissions(path: String): Set<PosixFilePermission> =
    if (path.endsWith("/mvnw") || path.endsWith("/gradlew") || path == "mvnw" || path == "gradlew") {
      executablePermissions
    }
    else {
      emptySet()
    }

  private fun isIdeaPath(path: String): Boolean =
    path == ".idea" || path.startsWith(".idea/")

  private data class ArchiveEntry(
    val name: String,
    val directory: Boolean,
    val content: ByteArray
  )
}
