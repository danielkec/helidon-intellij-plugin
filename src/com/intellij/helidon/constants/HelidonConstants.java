// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.constants;

import org.jetbrains.annotations.NonNls;

public interface HelidonConstants {
  @NonNls String WEB_SERVER = "io.helidon.webserver.WebServer";
  @NonNls String WEB_SERVER_CONFIG = "io.helidon.webserver.WebServerConfig";
  @NonNls String WEB_SERVER_CONFIG_BUILDER = "io.helidon.webserver.WebServerConfig.Builder";
  @NonNls String LISTENER_CONFIG = "io.helidon.webserver.ListenerConfig";
  @NonNls String LISTENER_CONFIG_BUILDER = "io.helidon.webserver.ListenerConfig.Builder";
  @NonNls String HTTP_ROUTING = "io.helidon.webserver.http.HttpRouting";
  @NonNls String HTTP_ROUTING_BUILDER = "io.helidon.webserver.http.HttpRouting.Builder";
  @NonNls String HTTP_RULES = "io.helidon.webserver.http.HttpRules";
  @NonNls String HTTP_SERVICE = "io.helidon.webserver.http.HttpService";
  @NonNls String HTTP_HANDLER = "io.helidon.webserver.http.Handler";
  @NonNls String HTTP_SERVER_REQUEST = "io.helidon.webserver.http.ServerRequest";
  @NonNls String HTTP_SERVER_RESPONSE = "io.helidon.webserver.http.ServerResponse";
  @NonNls String HTTP_PARAMETERS = "io.helidon.common.parameters.Parameters";

  @NonNls String ROUTING = "io.helidon.webserver.Routing";
  @NonNls String ROUTING_BUILDER = "io.helidon.webserver.Routing.Builder";
  @NonNls String ROUTING_RULES = "io.helidon.webserver.Routing.Rules";
  @NonNls String SERVICE = "io.helidon.webserver.Service";
  @NonNls String HANDLER = "io.helidon.webserver.Handler";
  @NonNls String HTTP_REQUEST_PATH = "io.helidon.common.http.HttpRequest.Path";
  @NonNls String LEGACY_HTTP_SERVER_REQUEST = "io.helidon.webserver.ServerRequest";
  @NonNls String LEGACY_HTTP_SERVER_RESPONSE = "io.helidon.webserver.ServerResponse";

  @NonNls String MP_MAIN = "io.helidon.microprofile.cdi.Main";
  @NonNls String HELIDON_MAIN = "io.helidon.Main";
}
