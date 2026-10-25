package com.swiftmq.mcp.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathAndGuardTest {
    final ContextGuard guard = new ContextGuard(Set.of("SYS$Streams"));

    @Test
    void canonicalization() {
        assertEquals("/sys$queuemanager/usage", MgmtPath.of("/sys$queuemanager/usage/").toString());
        assertEquals("/sys$queuemanager/usage", MgmtPath.of("//sys$queuemanager/./usage").toString());
        assertEquals("/sys$queuemanager", MgmtPath.of("/sys$queuemanager/usage/..").toString());
        MgmtPath base = MgmtPath.of("/sys$queuemanager");
        assertEquals("/sys$queuemanager/usage/testqueue", MgmtPath.resolve(base, "usage/testqueue").toString());
        assertEquals("/sys$topicmanager", MgmtPath.resolve(base, "../sys$topicmanager").toString());
        assertEquals("/sys$net", MgmtPath.resolve(base, "/sys$net").toString());
        assertEquals("/", MgmtPath.resolve(base, "..").toString());
    }

    @Test
    void namesWithSpacesAndSpecialCharactersAreKept() {
        MgmtPath p = MgmtPath.of("/sys$scheduler/usage/job-groups/Queue Manager");
        assertEquals(List.of("sys$scheduler", "usage", "job-groups", "Queue Manager"), p.segments());
        assertEquals("/sys$net/usage/127.0.0.1:60526", MgmtPath.of("/sys$net/usage/127.0.0.1:60526").toString());
        assertEquals("tmp$1-1792864767552@router", MgmtPath.of("/sys$queuemanager/usage/tmp$1-1792864767552@router").segments().get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sys$queuemanager/a\"b", "/sys$queuemanager/a;b", "/sys$queuemanager/a\nb", "/sys$queuemanager/ lead", "/..", "relative"})
    void rejectsInvalidPaths(String path) {
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> MgmtPath.of(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sys$authentication", "/sys$Authentication/users", "/SYS$MGMT/roles", "/sys$timer",
            "/sys$queuemanager/../sys$mgmt", "/sys$mgmt/", "/sys$streams/domains"})
    void deniedContexts(String path) {
        MgmtPath p = MgmtPath.of(path);
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> guard.check(p));
    }

    @Test
    void relativeEscapesIntoDeniedContextAreCaught() {
        MgmtPath p = MgmtPath.resolve(MgmtPath.of("/sys$queuemanager/usage"), "../../sys$authentication/users");
        assertEquals("/sys$authentication/users", p.toString());
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> guard.check(p));
    }

    @Test
    void rootIsNotReadable() {
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> guard.check(MgmtPath.ROOT));
    }

    @Test
    void allowedContexts() {
        for (String path : List.of("/sys$queuemanager/usage", "/.env/router-memory-list", "/sys$routing", "/sys$net/usage"))
            assertEquals(path, guard.check(MgmtPath.of(path)).toString());
        assertTrue(guard.denied().containsAll(Set.of("sys$authentication", "sys$mgmt", "sys$timer", "sys$streams")));
    }

    @Test
    void commandBuilderOnlyBuildsSrAndQuotedCc() {
        assertEquals("sr router1", CommandBuilder.selectRouter("router1"));
        assertEquals("cc \"/sys$scheduler/usage/job-groups/Queue Manager\"",
                CommandBuilder.changeContext(MgmtPath.of("/sys$scheduler/usage/job-groups/Queue Manager")));
        for (String bad : List.of("r1; halt", "r1 reboot", "r\"1", "", " r1"))
            assertThrows(SwiftMqAccessException.AccessDenied.class, () -> CommandBuilder.selectRouter(bad), bad);
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> CommandBuilder.changeContext(MgmtPath.ROOT));
    }
}
