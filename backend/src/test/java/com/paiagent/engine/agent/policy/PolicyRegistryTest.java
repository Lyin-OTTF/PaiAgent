package com.paiagent.engine.agent.policy;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PolicyRegistryTest {

    @ToolPolicy(level = AutonomyLevel.OBSERVE)
    static class ObserveTool implements AgentTool {
        @Override public String getName() { return "observeTool"; }
        @Override public String getDescription() { return "d"; }
        @Override public String getInputSchema() { return "{}"; }
        @Override public Map<String, Object> execute(Map<String, Object> a, AgentToolContext c) { return Map.of(); }
    }

    @ToolPolicy(level = AutonomyLevel.DRAFT, write = true, idempotent = true, scope = "tenantA")
    static class DraftTool implements AgentTool {
        @Override public String getName() { return "draftTool"; }
        @Override public String getDescription() { return "d"; }
        @Override public String getInputSchema() { return "{}"; }
        @Override public Map<String, Object> execute(Map<String, Object> a, AgentToolContext c) { return Map.of(); }
    }

    @Test
    void shouldResolveAnnotatedPolicies() {
        PolicyRegistry registry = new PolicyRegistry(List.of(new ObserveTool(), new DraftTool()));

        ResolvedPolicy observe = registry.resolve("observeTool");
        assertEquals(AutonomyLevel.OBSERVE, observe.level());
        assertFalse(observe.isWrite());

        ResolvedPolicy draft = registry.resolve("draftTool");
        assertEquals(AutonomyLevel.DRAFT, draft.level());
        assertTrue(draft.isWrite());
        assertTrue(draft.isIdempotent());
        assertEquals("tenantA", draft.scope());
    }

    @Test
    void shouldReturnDefaultPolicyForUnknownTool() {
        PolicyRegistry registry = new PolicyRegistry(List.of(new ObserveTool()));
        ResolvedPolicy def = registry.resolve("notRegistered");
        // 缺省策略：OBSERVE / 只读 / global，保证未知工具默认最保守
        assertEquals(AutonomyLevel.OBSERVE, def.level());
        assertFalse(def.isWrite());
        assertEquals("global", def.scope());
        assertFalse(def.isIdempotent());
    }

    @Test
    void shouldExposeAllPolicies() {
        PolicyRegistry registry = new PolicyRegistry(List.of(new ObserveTool(), new DraftTool()));
        assertEquals(2, registry.allPolicies().size());
        assertTrue(registry.isRegistered("draftTool"));
    }
}
