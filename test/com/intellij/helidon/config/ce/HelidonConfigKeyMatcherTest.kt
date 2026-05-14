// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelidonConfigKeyMatcherTest {

  @Test
  fun testRelaxedPrefixMatching() {
    assertTrue(HelidonConfigKeyMatcher.matchesPrefix("server.max-payload-size", "server.maxPayload"))
    assertTrue(HelidonConfigKeyMatcher.matchesPrefix("server.max-payload-size", "server.max-payload"))
    assertTrue(HelidonConfigKeyMatcher.matchesPrefix("server.connection-config.keep-alive", "server.connection-config.keepAlive"))
    assertTrue(HelidonConfigKeyMatcher.matchesPart("keep-alive", "keepA"))
    assertFalse(HelidonConfigKeyMatcher.matchesPrefix("server.max-payload-size", "security.maxPayload"))
  }

  @Test
  fun testParameterizedPropertiesKeyBinding() {
    assertEquals("server.sockets.admin.port",
                 HelidonConfigKeyMatcher.bindParameterizedKey("server.sockets.*.port", "server.sockets.admin.p"))
    assertNull(HelidonConfigKeyMatcher.bindParameterizedKey("server.sockets.*.port", "server.sockets."))
  }

  @Test
  fun testParameterizedYamlChildLookup() {
    assertEquals("port", HelidonConfigKeyMatcher.childLookupName("server.sockets.*.port", "server.sockets.admin"))
    assertEquals("name", HelidonConfigKeyMatcher.childLookupName("security.secrets.*.name", "security.secrets.dev"))
    assertEquals("*", HelidonConfigKeyMatcher.childLookupName("security.secrets.*.name", "security.secrets"))
    assertEquals("name", HelidonConfigKeyMatcher.childLookupName("security.secrets.*.name", "security.secrets.*"))
  }

  @Test
  fun testPropertyDelimiterDetection() {
    assertEquals(25, getPropertyDelimiterOffset("server.sockets.admin.port=8080", 25))
    assertEquals(25, getPropertyDelimiterOffset("server.sockets.admin.port:8080", 25))
    assertEquals(25, getPropertyDelimiterOffset("server.sockets.admin.port 8080", 25))
    assertEquals(25, getPropertyDelimiterOffset("server.sockets.admin.port\t8080", 25))
    assertEquals(25, getPropertyDelimiterOffset("server.sockets.admin.port\u000C8080", 25))
    assertEquals(27, getPropertyDelimiterOffset("server.sockets.admin.port   8080", 25))
    assertEquals(28, getPropertyDelimiterOffset("server.sockets.admin.port   :8080", 25))
    assertNull(getPropertyDelimiterOffset("server.sockets.admin.port", 25))
    assertNull(getPropertyDelimiterOffset("server.sockets.admin.port\nnext=value", 25))
  }
}
