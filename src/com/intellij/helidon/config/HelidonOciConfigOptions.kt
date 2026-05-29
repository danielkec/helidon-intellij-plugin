// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

internal object HelidonOciConfigOptions {
  private val KEY_NAMES = listOf(
    "helidon.oci.authentication-method",
    "helidon.oci.region",
    "helidon.oci.allowed-authentication-methods",
    "helidon.oci.imds-base-uri",
    "helidon.oci.imds-timeout",
    "helidon.oci.imds-detect-retries",
    "helidon.oci.authentication-timeout",
    "helidon.oci.federation-endpoint",
    "helidon.oci.tenant-id",
    "helidon.oci.authentication.config-file.profile",
    "helidon.oci.authentication.config-file.path",
    "helidon.oci.authentication.config.region",
    "helidon.oci.authentication.config.fingerprint",
    "helidon.oci.authentication.config.tenant-id",
    "helidon.oci.authentication.config.user-id",
    "helidon.oci.authentication.config.passphrase",
    "helidon.oci.authentication.config.private-key.resource-path",
    "helidon.oci.authentication.config.private-key.path",
    "helidon.oci.authentication.config.private-key.uri",
    "helidon.oci.authentication.config.private-key.content-plain",
    "helidon.oci.authentication.config.private-key.content",
    "helidon.oci.authentication.config.private-key.proxy-host",
    "helidon.oci.authentication.config.private-key.proxy-port",
    "helidon.oci.authentication.config.private-key.use-proxy",
    "helidon.oci.authentication.config.private-key.description",
    "helidon.oci.authentication.session-token.region",
    "helidon.oci.authentication.session-token.fingerprint",
    "helidon.oci.authentication.session-token.tenant-id",
    "helidon.oci.authentication.session-token.user-id",
    "helidon.oci.authentication.session-token.passphrase",
    "helidon.oci.authentication.session-token.private-key-path",
    "helidon.oci.authentication.session-token.session-token-path",
    "helidon.oci.authentication.session-token.session-token",
    "helidon.oci.authentication.session-token.initial-refresh-delay",
    "helidon.oci.authentication.session-token.refresh-period",
    "helidon.oci.authentication.session-token.session-lifetime-hours",
  )

  private val KEY_NAME_SET = KEY_NAMES.toHashSet()

  fun isBuiltInKeyName(keyName: String): Boolean = keyName in KEY_NAME_SET

  fun childLookupNames(parentQualifiedName: String): List<String> {
    val parentParts = split(parentQualifiedName)
    val result = LinkedHashSet<String>()
    for (keyName in KEY_NAMES) {
      val keyParts = split(keyName)
      if (parentParts.size >= keyParts.size) continue
      if (!parentPartsMatches(keyParts, parentParts)) continue
      result.add(keyParts[parentParts.size])
    }
    return result.toList()
  }

  private fun parentPartsMatches(keyParts: List<String>, parentParts: List<String>): Boolean {
    for (index in parentParts.indices) {
      if (!keyParts[index].equals(parentParts[index], ignoreCase = true)) return false
    }
    return true
  }

  private fun split(keyName: String): List<String> = keyName.split('.').filter { it.isNotBlank() }
}
