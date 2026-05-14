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
  @NonNls String HTTP_ROUTE = "io.helidon.webserver.http.HttpRoute";
  @NonNls String HTTP_ROUTE_BUILDER = "io.helidon.webserver.http.HttpRoute.Builder";
  @NonNls String HTTP_HANDLER = "io.helidon.webserver.http.Handler";
  @NonNls String HTTP_SERVER_REQUEST = "io.helidon.webserver.http.ServerRequest";
  @NonNls String HTTP_SERVER_RESPONSE = "io.helidon.webserver.http.ServerResponse";
  @NonNls String HTTP_PARAMETERS = "io.helidon.common.parameters.Parameters";
  @NonNls String REST_SERVER_ENDPOINT = "io.helidon.webserver.http.RestServer.Endpoint";

  @NonNls String HTTP_METHOD = "io.helidon.http.Method";
  @NonNls String HTTP_METHOD_PREDICATE = "io.helidon.http.MethodPredicate";
  @NonNls String HTTP_PATH_MATCHER = "io.helidon.http.PathMatcher";
  @NonNls String HTTP_PATH_MATCHERS = "io.helidon.http.PathMatchers";
  @NonNls String HTTP_PATH = "io.helidon.http.Http.Path";
  @NonNls String HTTP_HTTP_METHOD = "io.helidon.http.Http.HttpMethod";
  @NonNls String HTTP_PATH_PARAM = "io.helidon.http.Http.PathParam";
  @NonNls String HTTP_HEADER_PARAM = "io.helidon.http.Http.HeaderParam";
  @NonNls String HTTP_QUERY_PARAM = "io.helidon.http.Http.QueryParam";
  @NonNls String HTTP_ENTITY = "io.helidon.http.Http.Entity";
  @NonNls String HTTP_CONSUMES = "io.helidon.http.Http.Consumes";
  @NonNls String HTTP_PRODUCES = "io.helidon.http.Http.Produces";
  @NonNls String HTTP_GET = "io.helidon.http.Http.GET";
  @NonNls String HTTP_HEAD = "io.helidon.http.Http.HEAD";
  @NonNls String HTTP_POST = "io.helidon.http.Http.POST";
  @NonNls String HTTP_PUT = "io.helidon.http.Http.PUT";
  @NonNls String HTTP_PATCH = "io.helidon.http.Http.PATCH";
  @NonNls String HTTP_DELETE = "io.helidon.http.Http.DELETE";
  @NonNls String HTTP_OPTIONS = "io.helidon.http.Http.OPTIONS";

  @NonNls String ROUTING = "io.helidon.webserver.Routing";
  @NonNls String ROUTING_BUILDER = "io.helidon.webserver.Routing.Builder";
  @NonNls String ROUTING_RULES = "io.helidon.webserver.Routing.Rules";
  @NonNls String SERVICE = "io.helidon.webserver.Service";
  @NonNls String LEGACY_HTTP_ROUTE = "io.helidon.webserver.HttpRoute";
  @NonNls String HANDLER = "io.helidon.webserver.Handler";
  @NonNls String LEGACY_PATH_MATCHER = "io.helidon.webserver.PathMatcher";
  @NonNls String HTTP_REQUEST_PATH = "io.helidon.common.http.HttpRequest.Path";
  @NonNls String LEGACY_HTTP_SERVER_REQUEST = "io.helidon.webserver.ServerRequest";
  @NonNls String LEGACY_HTTP_SERVER_RESPONSE = "io.helidon.webserver.ServerResponse";

  @NonNls String MP_MAIN = "io.helidon.microprofile.cdi.Main";
  @NonNls String HELIDON_MAIN = "io.helidon.Main";

  @NonNls String SERVICE_REGISTRY_SERVICE = "io.helidon.service.registry.Service";
  @NonNls String SERVICE_SINGLETON = "io.helidon.service.registry.Service.Singleton";
  @NonNls String SERVICE_PROVIDER = "io.helidon.service.registry.Service.Provider";
  @NonNls String SERVICE_PER_LOOKUP = "io.helidon.service.registry.Service.PerLookup";
  @NonNls String SERVICE_PER_REQUEST = "io.helidon.service.registry.Service.PerRequest";
  @NonNls String SERVICE_INJECT = "io.helidon.service.registry.Service.Inject";
  @NonNls String SERVICE_CONTRACT = "io.helidon.service.registry.Service.Contract";
  @NonNls String SERVICE_NAMED = "io.helidon.service.registry.Service.Named";
  @NonNls String SERVICE_EXTERNAL_CONTRACTS = "io.helidon.service.registry.Service.ExternalContracts";
  @NonNls String SERVICE_REGISTRY_SERVICES = "io.helidon.service.registry.Services";
  @NonNls String SERVICE_REGISTRY = "io.helidon.service.registry.ServiceRegistry";

  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI = "io.helidon.integrations.langchain4j.Ai";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_SERVICE = LANGCHAIN4J_INTEGRATIONS_AI + ".Service";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_AGENT = LANGCHAIN4J_INTEGRATIONS_AI + ".Agent";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL = LANGCHAIN4J_INTEGRATIONS_AI + ".ChatModel";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL = LANGCHAIN4J_INTEGRATIONS_AI + ".StreamingChatModel";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MEMORY_PROVIDER = LANGCHAIN4J_INTEGRATIONS_AI + ".ChatMemoryProvider";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_MODERATION_MODEL = LANGCHAIN4J_INTEGRATIONS_AI + ".ModerationModel";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_CONTENT_RETRIEVER = LANGCHAIN4J_INTEGRATIONS_AI + ".ContentRetriever";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_RETRIEVAL_AUGMENTOR = LANGCHAIN4J_INTEGRATIONS_AI + ".RetrievalAugmentor";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_TOOL_PROVIDER = LANGCHAIN4J_INTEGRATIONS_AI + ".ToolProvider";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS = LANGCHAIN4J_INTEGRATIONS_AI + ".McpClients";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_TOOLS = LANGCHAIN4J_INTEGRATIONS_AI + ".Tools";
  @NonNls String LANGCHAIN4J_INTEGRATIONS_AI_TOOL = LANGCHAIN4J_INTEGRATIONS_AI + ".Tool";

  @NonNls String LANGCHAIN4J_EXTENSIONS_AI = "io.helidon.extensions.langchain4j.Ai";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_SERVICE = LANGCHAIN4J_EXTENSIONS_AI + ".Service";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_AGENT = LANGCHAIN4J_EXTENSIONS_AI + ".Agent";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL = LANGCHAIN4J_EXTENSIONS_AI + ".ChatModel";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL = LANGCHAIN4J_EXTENSIONS_AI + ".StreamingChatModel";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_CHAT_MEMORY_PROVIDER = LANGCHAIN4J_EXTENSIONS_AI + ".ChatMemoryProvider";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_MODERATION_MODEL = LANGCHAIN4J_EXTENSIONS_AI + ".ModerationModel";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_CONTENT_RETRIEVER = LANGCHAIN4J_EXTENSIONS_AI + ".ContentRetriever";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_RETRIEVAL_AUGMENTOR = LANGCHAIN4J_EXTENSIONS_AI + ".RetrievalAugmentor";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_TOOL_PROVIDER = LANGCHAIN4J_EXTENSIONS_AI + ".ToolProvider";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS = LANGCHAIN4J_EXTENSIONS_AI + ".McpClients";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_TOOLS = LANGCHAIN4J_EXTENSIONS_AI + ".Tools";
  @NonNls String LANGCHAIN4J_EXTENSIONS_AI_TOOL = LANGCHAIN4J_EXTENSIONS_AI + ".Tool";
}
