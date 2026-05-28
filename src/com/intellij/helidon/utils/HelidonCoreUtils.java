// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.utils;

import com.intellij.helidon.constants.HelidonConstants;
import com.intellij.java.library.JavaLibraryModificationTracker;
import com.intellij.java.library.JavaLibraryUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.uast.UastModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HelidonCoreUtils {
  private static final Key<CachedValue<Map<PsiClass, Set<PsiElement>>>> SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY =
    Key.create("HELIDON_SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY");
  private static final Key<CachedValue<Map<ServiceUsageScopeKey, Set<PsiElement>>>> SCOPED_SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY =
    Key.create("SCOPED_HELIDON_SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY");
  private static final Set<String> HELIDON_SERVICE_SCOPE_ANNOTATIONS = Set.of(HelidonConstants.SERVICE_SINGLETON,
                                                                               HelidonConstants.SERVICE_PROVIDER,
                                                                               HelidonConstants.SERVICE_PER_LOOKUP,
                                                                               HelidonConstants.SERVICE_PER_REQUEST,
                                                                               HelidonConstants.REST_SERVER_ENDPOINT,
                                                                               HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_SERVICE,
                                                                               HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_AGENT,
                                                                               HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_SERVICE,
                                                                               HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_AGENT);
  private static final Set<String> SERVICE_LOOKUP_METHODS = Set.of("get",
                                                                   "getNamed",
                                                                   "first",
                                                                   "firstNamed",
                                                                   "all",
                                                                   "allNamed",
                                                                   "supply",
                                                                   "supplyNamed",
                                                                   "supplyFirst",
                                                                   "supplyFirstNamed",
                                                                   "supplyAll");

  private HelidonCoreUtils() {
  }

  public static boolean hasHelidonLibrary(@NotNull Project project) {
    return JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.CONFIG) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.CONFIG_SOURCE_PROVIDER) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.HTTP_ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.REST_SERVER_ENDPOINT) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.SERVICE_REGISTRY_SERVICE) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI);
  }

  public static boolean hasHelidonLibrary(@Nullable Module module) {
    return JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.CONFIG) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.CONFIG_SOURCE_PROVIDER) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.HTTP_ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.REST_SERVER_ENDPOINT) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.SERVICE_REGISTRY_SERVICE) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI);
  }

  public static boolean hasHelidonMPLibrary(@Nullable Module module) {
    return JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.MP_MAIN);
  }

  public static boolean isHelidonServiceRegistryClass(@NotNull PsiClass psiClass) {
    return hasAnnotationOrMetaAnnotation(psiClass, HELIDON_SERVICE_SCOPE_ANNOTATIONS, new HashSet<>());
  }

  public static @NotNull Set<PsiElement> getHelidonServiceUsageTargets(@NotNull Module module, @NotNull PsiClass serviceClass) {
    Map<PsiClass, Set<PsiElement>> usageTargetsByContract = CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY, () -> {
        return Result.create(new ConcurrentHashMap<>(),
                             UastModificationTracker.getInstance(module.getProject()),
                             JavaLibraryModificationTracker.getInstance(module.getProject()),
                             ProjectRootModificationTracker.getInstance(module.getProject()));
      }, false);

    Set<PsiElement> targets = new LinkedHashSet<>();
    for (PsiClass contract : getHelidonServiceContracts(serviceClass)) {
      Set<PsiElement> contractTargets = usageTargetsByContract.computeIfAbsent(contract, key ->
        Collections.unmodifiableSet(new LinkedHashSet<>(calculateHelidonServiceUsageTargets(module, key))));
      for (PsiElement target : contractTargets) {
        if (!PsiTreeUtil.isAncestor(serviceClass, target, false)) {
          targets.add(target);
        }
      }
    }
    return targets;
  }

  public static @NotNull Set<PsiElement> getHelidonServiceUsageTargets(@NotNull Module module,
                                                                       @NotNull PsiClass serviceClass,
                                                                       @NotNull SearchScope scope) {
    Map<ServiceUsageScopeKey, Set<PsiElement>> usageTargetsByContract = CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, SCOPED_SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY, () -> {
        return Result.create(new ConcurrentHashMap<>(),
                             UastModificationTracker.getInstance(module.getProject()),
                             JavaLibraryModificationTracker.getInstance(module.getProject()),
                             ProjectRootModificationTracker.getInstance(module.getProject()));
      }, false);

    Set<PsiElement> targets = new LinkedHashSet<>();
    for (PsiClass contract : getHelidonServiceContracts(serviceClass)) {
      ServiceUsageScopeKey key = new ServiceUsageScopeKey(contract, scope);
      Set<PsiElement> contractTargets = usageTargetsByContract.computeIfAbsent(key, ignored ->
        Collections.unmodifiableSet(new LinkedHashSet<>(calculateHelidonServiceUsageTargets(contract, scope))));
      for (PsiElement target : contractTargets) {
        if (!PsiTreeUtil.isAncestor(serviceClass, target, false)) {
          targets.add(target);
        }
      }
    }
    return targets;
  }

  private static @NotNull Set<PsiElement> calculateHelidonServiceUsageTargets(@NotNull Module module, @NotNull PsiClass contract) {
    return calculateHelidonServiceUsageTargets(contract, module.getModuleWithDependenciesScope());
  }

  private static @NotNull Set<PsiElement> calculateHelidonServiceUsageTargets(@NotNull PsiClass contract, @NotNull SearchScope scope) {
    Set<PsiElement> targets = new LinkedHashSet<>();
    ReferencesSearch.search(contract, scope).forEach(reference -> {
      PsiElement element = reference.getElement();
      PsiElement target = getServiceUsageTarget(element);
      if (target != null) {
        targets.add(target);
      }
      return true;
    });
    return targets;
  }

  public static @NotNull Set<PsiClass> getHelidonServiceContracts(@NotNull PsiClass serviceClass) {
    Set<PsiClass> contracts = new LinkedHashSet<>();
    contracts.add(serviceClass);
    collectAllInterfaces(serviceClass, contracts, new HashSet<>());
    PsiClass superClass = serviceClass.getSuperClass();
    while (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      contracts.add(superClass);
      superClass = superClass.getSuperClass();
    }
    collectExternalContractClasses(serviceClass, contracts);
    return contracts;
  }

  public static @Nullable String getHelidonServiceScopeAnnotationName(@NotNull PsiClass serviceClass) {
    return findAnnotationOrMetaAnnotation(serviceClass, HELIDON_SERVICE_SCOPE_ANNOTATIONS, new HashSet<>());
  }

  public static @NotNull Set<String> getHelidonServiceNames(@NotNull PsiClass serviceClass) {
    Set<String> names = new LinkedHashSet<>();
    PsiAnnotation named = findAnnotation(serviceClass, HelidonConstants.SERVICE_NAMED);
    if (named != null) {
      collectAnnotationStringValues(named.findDeclaredAttributeValue("value"), names);
    }
    return names;
  }

  private static void collectAllInterfaces(@NotNull PsiClass psiClass,
                                           @NotNull Set<? super PsiClass> result,
                                           @NotNull Set<? super PsiClass> visited) {
    if (!visited.add(psiClass)) return;
    for (PsiClass anInterface : psiClass.getInterfaces()) {
      if (result.add(anInterface)) {
        collectAllInterfaces(anInterface, result, visited);
      }
    }
    PsiClass superClass = psiClass.getSuperClass();
    if (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      collectAllInterfaces(superClass, result, visited);
    }
  }

  private static void collectExternalContractClasses(@NotNull PsiClass serviceClass, @NotNull Set<? super PsiClass> contracts) {
    PsiAnnotation annotation = findAnnotation(serviceClass, HelidonConstants.SERVICE_EXTERNAL_CONTRACTS);
    if (annotation == null) return;
    collectClassLiterals(annotation.findDeclaredAttributeValue("value"), contracts);
  }

  private static void collectClassLiterals(@Nullable PsiAnnotationMemberValue value, @NotNull Set<? super PsiClass> result) {
    if (value instanceof PsiArrayInitializerMemberValue) {
      for (PsiAnnotationMemberValue initializer : ((PsiArrayInitializerMemberValue)value).getInitializers()) {
        collectClassLiterals(initializer, result);
      }
      return;
    }
    if (value instanceof PsiClassObjectAccessExpression) {
      PsiTypeElement operand = ((PsiClassObjectAccessExpression)value).getOperand();
      PsiType type = operand.getType();
      if (type instanceof PsiClassType) {
        PsiClass resolved = ((PsiClassType)type).resolve();
        if (resolved != null) {
          result.add(resolved);
        }
      }
    }
  }

  private static @Nullable PsiElement getServiceUsageTarget(@NotNull PsiElement referenceElement) {
    PsiClassObjectAccessExpression classObjectAccess =
      PsiTreeUtil.getParentOfType(referenceElement, PsiClassObjectAccessExpression.class, false);
    if (classObjectAccess != null && isServiceLookupClassLiteral(classObjectAccess)) {
      return classObjectAccess;
    }

    PsiTypeElement typeElement = PsiTreeUtil.getParentOfType(referenceElement, PsiTypeElement.class, false);
    if (typeElement == null) return null;

    PsiField field = PsiTreeUtil.getParentOfType(typeElement, PsiField.class);
    if (field != null && hasAnnotation(field, HelidonConstants.SERVICE_INJECT)) {
      return field.getNameIdentifier() != null ? field.getNameIdentifier() : field;
    }

    PsiParameter parameter = PsiTreeUtil.getParentOfType(typeElement, PsiParameter.class);
    if (parameter != null && isInjectedParameter(parameter)) {
      return parameter.getNameIdentifier() != null ? parameter.getNameIdentifier() : parameter;
    }
    return null;
  }

  private static boolean isServiceLookupClassLiteral(@NotNull PsiClassObjectAccessExpression classObjectAccess) {
    PsiExpressionList expressionList = PsiTreeUtil.getParentOfType(classObjectAccess, PsiExpressionList.class);
    PsiMethodCallExpression methodCall = expressionList == null ? null : PsiTreeUtil.getParentOfType(expressionList, PsiMethodCallExpression.class);
    if (methodCall == null || expressionList.getParent() != methodCall) return false;

    PsiExpression[] expressions = expressionList.getExpressions();
    if (expressions.length == 0 || expressions[0] != classObjectAccess) return false;

    PsiMethod method = methodCall.resolveMethod();
    if (method == null || !SERVICE_LOOKUP_METHODS.contains(method.getName())) return false;

    PsiClass containingClass = method.getContainingClass();
    String containingClassName = containingClass == null ? null : containingClass.getQualifiedName();
    return HelidonConstants.SERVICE_REGISTRY_SERVICES.equals(containingClassName) ||
           HelidonConstants.SERVICE_REGISTRY.equals(containingClassName);
  }

  private static boolean isInjectedParameter(@NotNull PsiParameter parameter) {
    PsiElement parent = parameter.getDeclarationScope();
    return parent instanceof PsiMethod && hasAnnotation((PsiMethod)parent, HelidonConstants.SERVICE_INJECT);
  }

  private static boolean hasAnnotationOrMetaAnnotation(@NotNull PsiModifierListOwner owner,
                                                       @NotNull Set<String> annotations,
                                                       @NotNull Set<? super PsiModifierListOwner> visited) {
    if (!visited.add(owner)) return false;
    for (PsiAnnotation annotation : getAnnotations(owner)) {
      String qualifiedName = annotation.getQualifiedName();
      if (qualifiedName != null && annotations.contains(qualifiedName)) {
        return true;
      }
      PsiClass annotationClass = annotation.resolveAnnotationType();
      if (annotationClass != null && hasAnnotationOrMetaAnnotation(annotationClass, annotations, visited)) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String findAnnotationOrMetaAnnotation(@NotNull PsiModifierListOwner owner,
                                                                 @NotNull Set<String> annotations,
                                                                 @NotNull Set<? super PsiModifierListOwner> visited) {
    if (!visited.add(owner)) return null;
    for (PsiAnnotation annotation : getAnnotations(owner)) {
      String qualifiedName = annotation.getQualifiedName();
      if (qualifiedName != null && annotations.contains(qualifiedName)) {
        return qualifiedName;
      }
      PsiClass annotationClass = annotation.resolveAnnotationType();
      if (annotationClass != null) {
        String metaAnnotation = findAnnotationOrMetaAnnotation(annotationClass, annotations, visited);
        if (metaAnnotation != null) {
          return metaAnnotation;
        }
      }
    }
    return null;
  }

  private static boolean hasAnnotation(@NotNull PsiModifierListOwner owner, @NotNull String annotationName) {
    return findAnnotation(owner, annotationName) != null;
  }

  private static @Nullable PsiAnnotation findAnnotation(@NotNull PsiModifierListOwner owner, @NotNull String annotationName) {
    for (PsiAnnotation annotation : getAnnotations(owner)) {
      if (annotationName.equals(annotation.getQualifiedName())) {
        return annotation;
      }
    }
    return null;
  }

  private static @NotNull PsiAnnotation[] getAnnotations(@NotNull PsiModifierListOwner owner) {
    PsiModifierList modifierList = owner.getModifierList();
    return modifierList == null ? PsiAnnotation.EMPTY_ARRAY : modifierList.getAnnotations();
  }

  private static void collectAnnotationStringValues(@Nullable PsiAnnotationMemberValue value, @NotNull Set<? super String> result) {
    if (value == null) return;
    if (value instanceof PsiArrayInitializerMemberValue) {
      for (PsiAnnotationMemberValue initializer : ((PsiArrayInitializerMemberValue)value).getInitializers()) {
        collectAnnotationStringValues(initializer, result);
      }
      return;
    }
    Object constantValue = JavaPsiFacade.getInstance(value.getProject())
      .getConstantEvaluationHelper()
      .computeConstantExpression(value);
    if (constantValue instanceof String && !((String)constantValue).isBlank()) {
      result.add((String)constantValue);
    }
  }

  private record ServiceUsageScopeKey(@NotNull PsiClass contract, @NotNull SearchScope scope) {
  }
}
