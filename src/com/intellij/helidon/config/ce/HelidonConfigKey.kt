// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

internal data class HelidonConfigKey(
  val name: String,
  val type: String,
  val description: String,
  val defaultValue: String?,
  val deprecated: Boolean,
)
