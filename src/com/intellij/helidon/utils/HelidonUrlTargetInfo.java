// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.utils;

import com.intellij.helidon.HelidonIcons;
import com.intellij.helidon.providers.HelidonRequestMethods;
import com.intellij.helidon.providers.HelidonUrlPathSpecification;
import com.intellij.microservices.jvm.url.UastUrlAttributeUtils;
import com.intellij.microservices.url.Authority;
import com.intellij.microservices.url.HttpUrlResolver;
import com.intellij.microservices.url.UrlPath;
import com.intellij.microservices.url.UrlTargetInfo;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PartiallyKnownString;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

import static com.intellij.microservices.url.UrlConstants.HTTP_SCHEMES;

public final class HelidonUrlTargetInfo implements UrlTargetInfo {
  public enum PathSemantics {
    PATTERN,
    LITERAL,
    PREFIX
  }

  private final String urlDefinition;
  private String myPathDefinition = null;

  private final SmartPsiElementPointer<PsiElement> myElementPointer;
  private HelidonRequestMethods myType = HelidonRequestMethods.UNKNOWN;
  private Set<String> myMethods = null;
  private String myParentUrl = null;
  private PathSemantics myPathSemantics = PathSemantics.PATTERN;
  private final NotNullLazyValue<UrlPath> myUrlPath = NotNullLazyValue.createValue(() -> {
    return computeUrlPath();
  });

  public static HelidonUrlTargetInfo create(@NotNull String url, @NotNull PsiElement resolveTo) {
    return new HelidonUrlTargetInfo(url, resolveTo);
  }

  public HelidonUrlTargetInfo ofType(HelidonRequestMethods type) {
    myType = type;
    return this;
  }

  public HelidonUrlTargetInfo withMethods(@NotNull Collection<String> methods) {
    myMethods = Collections.unmodifiableSet(new LinkedHashSet<>(methods));
    return this;
  }

  @Override
  public @NotNull Set<String> getMethods() {
    if (myMethods != null) return myMethods;
    if (myType == HelidonRequestMethods.UNKNOWN ||
        myType == HelidonRequestMethods.REGISTER ||
        myType == HelidonRequestMethods.ANY_OF) {
      return Collections.emptySet();
    }

    return Collections.singleton(myType.name().toUpperCase(Locale.ENGLISH));
  }

  @Override
  public @NotNull String getSource() {
    return UastUrlAttributeUtils.getUastDeclaringLocation(resolveToPsiElement());
  }

  @Override
  public @Nullable PsiElement getDocumentationPsiElement() {
    return UastUrlAttributeUtils.getUastDeclaringDocumentationElement(resolveToPsiElement());
  }

  public HelidonUrlTargetInfo withParentUrl(String parentUrl) {
    myParentUrl = parentUrl;
    return this;
  }

  public HelidonUrlTargetInfo withLiteralPath() {
    myPathSemantics = PathSemantics.LITERAL;
    return this;
  }

  public HelidonUrlTargetInfo withPrefixPath(@Nullable String pathDefinition) {
    myPathSemantics = PathSemantics.PREFIX;
    myPathDefinition = pathDefinition;
    return this;
  }

  private HelidonUrlTargetInfo(@NotNull String url, @NotNull PsiElement resolveTo) {
    urlDefinition = url;
    myElementPointer = SmartPointerManager.getInstance(resolveTo.getProject()).createSmartPsiElementPointer(resolveTo);
  }

  private @NotNull UrlPath computeUrlPath() {
    if (myPathSemantics == PathSemantics.LITERAL || myPathSemantics == PathSemantics.PREFIX) {
      return computeUrlPathWithLiteralChild(getPathDefinition());
    }
    return parseUrlPath(getFullUrlDefinition());
  }

  private @NotNull UrlPath computeUrlPathWithLiteralChild(@NotNull String childUrlDefinition) {
    UrlPath childPath = UrlPath.fromExactString(withoutLeadingSlash(childUrlDefinition));
    if (myParentUrl == null) {
      return childPath;
    }

    UrlPath parentPath = parseUrlPath(withoutTrailingSlash(withoutLeadingSlash(myParentUrl)));
    List<UrlPath.PathSegment> segments = new ArrayList<>(parentPath.getSegments());
    segments.addAll(childPath.getSegments());
    return new UrlPath(segments);
  }

  private @NotNull String getFullUrlDefinition() {
    StringBuilder sb = new StringBuilder();
    if (myParentUrl != null) {
      sb.append(myParentUrl);
    }
    if (sb.toString().endsWith("/")) {
      sb.append(StringsKt.removePrefix(urlDefinition, "/"));
    }
    else {
      if (!urlDefinition.startsWith("/")) {
        sb.append("/");
      }
      sb.append(urlDefinition);
    }
    return withoutLeadingSlash(sb.toString());
  }

  private @NotNull String getPathDefinition() {
    return myPathDefinition == null ? urlDefinition : myPathDefinition;
  }

  public boolean matchesPath(@NotNull UrlPath requestPath) {
    UrlPath path = getPath();
    if (path.isCompatibleWith(requestPath)) {
      return true;
    }
    return myPathSemantics == PathSemantics.PREFIX && isPrefixCompatibleWith(path, requestPath);
  }

  private static boolean isPrefixCompatibleWith(@NotNull UrlPath prefixPath, @NotNull UrlPath requestPath) {
    List<UrlPath.PathSegment> prefixSegments = meaningfulSegments(prefixPath);
    List<UrlPath.PathSegment> requestSegments = meaningfulSegments(requestPath);
    if (prefixSegments.size() > requestSegments.size()) return false;

    for (int i = 0; i < prefixSegments.size(); i++) {
      if (!segmentsAreCompatible(prefixSegments.get(i), requestSegments.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static @NotNull List<UrlPath.PathSegment> meaningfulSegments(@NotNull UrlPath path) {
    List<UrlPath.PathSegment> result = new ArrayList<>();
    for (UrlPath.PathSegment segment : path.getSegments()) {
      if (!segment.isEmpty()) {
        result.add(segment);
      }
    }
    return result;
  }

  private static boolean segmentsAreCompatible(@NotNull UrlPath.PathSegment targetSegment,
                                               @NotNull UrlPath.PathSegment requestSegment) {
    if (targetSegment instanceof UrlPath.PathSegment.Exact && requestSegment instanceof UrlPath.PathSegment.Exact) {
      return Objects.equals(targetSegment.getValueIfExact(), requestSegment.getValueIfExact());
    }
    if (targetSegment instanceof UrlPath.PathSegment.Variable && requestSegment instanceof UrlPath.PathSegment.Exact) {
      String value = requestSegment.getValueIfExact();
      return value != null && ((UrlPath.PathSegment.Variable)targetSegment).accepts(value);
    }
    if (targetSegment instanceof UrlPath.PathSegment.Exact && requestSegment instanceof UrlPath.PathSegment.Variable) {
      String value = targetSegment.getValueIfExact();
      return value != null && ((UrlPath.PathSegment.Variable)requestSegment).accepts(value);
    }
    if (targetSegment instanceof UrlPath.PathSegment.Variable && requestSegment instanceof UrlPath.PathSegment.Variable) {
      return true;
    }
    if (targetSegment instanceof UrlPath.PathSegment.Undefined || requestSegment instanceof UrlPath.PathSegment.Undefined) {
      return true;
    }
    return new UrlPath(Collections.singletonList(targetSegment)).isCompatibleWith(new UrlPath(Collections.singletonList(requestSegment)));
  }

  private static @NotNull UrlPath parseUrlPath(@NotNull String url) {
    var urlPath = HelidonUrlPathSpecification.INSTANCE.getParser().parseUrlPath(new PartiallyKnownString(url));
    return urlPath.getUrlPath();
  }

  private static @NotNull String withoutLeadingSlash(@NotNull String url) {
    return StringsKt.removePrefix(url, "/");
  }

  private static @NotNull String withoutTrailingSlash(@NotNull String url) {
    while (url.endsWith("/") && !url.isEmpty()) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  @Override
  public @NotNull List<String> getSchemes() { return HTTP_SCHEMES; }

  @Override
  public @NotNull List<Authority> getAuthorities() {
    return new ArrayList<>(HttpUrlResolver.Companion.getHTTP_AUTHORITY());
  }

  @Override
  public @NotNull UrlPath getPath() {
    return myUrlPath.getValue();
  }

  @Override
  public @NotNull Icon getIcon() {
    return HelidonIcons.Helidon;
  }

  @Override
  public boolean isDeprecated() {
    return UastUrlAttributeUtils.isUastDeclarationDeprecated(resolveToPsiElement());
  }

  public @Nullable String getParentUrl() {
    return myParentUrl;
  }

  public @NotNull String getUrlDefinition() {
    return urlDefinition;
  }

  public @NotNull PathSemantics getPathSemantics() {
    return myPathSemantics;
  }

  @Override
  public @Nullable PsiElement resolveToPsiElement() {
    return myElementPointer.getElement();
  }

  public HelidonRequestMethods getType() {
    return myType;
  }
}
