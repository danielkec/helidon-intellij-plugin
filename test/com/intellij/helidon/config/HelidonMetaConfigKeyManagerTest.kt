// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.microservices.jvm.config.MetaConfigKey

class HelidonMetaConfigKeyManagerTest : HelidonHighlightingTestCase() {

  fun testFindCanonicalApplicationMetaConfigKey() {
    val key = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "server.host")
    assertNotNull(key)

    val keyWithNonCanonicalName = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "Server.host")
    assertNull(keyWithNonCanonicalName)
  }

  fun testMapConfigKeyHasNestedValueTypeMetadata() {
    val key = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "server.sockets")
    assertNotNull(key)
    assertTrue(key!!.isAccessType(MetaConfigKey.AccessType.MAP))
    assertEquals("Map<String, ListenerConfig>", key.type!!.presentableText)
    assertEquals("ListenerConfig", key.effectiveValueType!!.presentableText)

    val helidonKey = assertInstanceOf(key, HelidonMetaConfigKey::class.java)
    assertContainsElements(helidonKey.subKeys.map { it.name }, "port", "host", "tls.enabled")
  }
}
