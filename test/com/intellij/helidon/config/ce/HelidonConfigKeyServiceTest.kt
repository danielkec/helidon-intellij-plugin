// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase

class HelidonConfigKeyServiceTest : HelidonHighlightingTestCase() {

  fun testLoadsConfigMetadataWithoutMicroservicesModel() {
    val keys = HelidonConfigKeyService.getInstance().getAllKeys(module).associateBy { it.name }

    assertTrue(keys.containsKey("server.host"))
    assertEquals("java.lang.String", keys["server.host"]?.type)
    assertTrue(keys.containsKey("server.sockets"))
    assertEquals("java.util.Map<java.lang.String, io.helidon.webserver.ListenerConfig>", keys["server.sockets"]?.type)
    assertTrue(keys.containsKey("server.sockets.*.port"))
    assertFalse(keys.containsKey("server.connection-config"))
    assertTrue(keys.containsKey("server.connection-config.keep-alive"))
    assertFalse(keys.containsKey("server.sockets.*.connection-config"))
    assertTrue(keys.containsKey("server.sockets.*.connection-config.keep-alive"))
    assertTrue(keys.containsKey("security.secrets"))
    assertEquals("java.util.List<io.helidon.common.config.Config>", keys["security.secrets"]?.type)
    assertTrue(keys.containsKey("security.secrets.*.name"))
    assertTrue(keys.containsKey("security.secrets.*.provider"))
  }
}
