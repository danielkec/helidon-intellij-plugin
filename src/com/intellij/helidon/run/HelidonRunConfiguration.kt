// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.run

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project

class HelidonRunConfiguration(name: String,
                              project: Project,
                              factory: ConfigurationFactory) : ApplicationConfiguration(name, project, factory)
