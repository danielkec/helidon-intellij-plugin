// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.module.Module;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public interface HelidonServiceClassLineMarkerTargetProvider {
  ExtensionPointName<HelidonServiceClassLineMarkerTargetProvider> EP_NAME =
    ExtensionPointName.create("com.intellij.helidon.serviceClassLineMarkerTargetProvider");

  @NotNull Collection<PsiElement> getTargets(@NotNull Module module, @NotNull PsiClass serviceClass);
}
