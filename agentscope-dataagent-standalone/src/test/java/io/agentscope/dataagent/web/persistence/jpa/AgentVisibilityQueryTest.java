package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {"spring.sql.init.mode=never"})
class AgentVisibilityQueryTest {
    @Autowired AgentEntityRepository agents;
    @Autowired UserEntityRepository users;

    @Test
    void filtersOwnDirectWorkspaceAndRevokedGrantsInTheDatabase() {
        users.save(new UserEntity("alice", "alice", "hash", "user"));
        users.save(new UserEntity("bob", "bob", "hash", "user"));
        save("alice", "own", null, null);
        save("bob", "private", null, null);
        AgentEntity direct = save("bob", "shared", "USER", "alice");
        save("bob", "workspace", "WORKSPACE", "*");
        save("bob", "other", "USER", "charlie");
        save("deleted-owner", "orphan", "WORKSPACE", "*");
        agents.flush();
        assertThat(agents.findVisible("alice", null))
                .extracting(AgentEntity::getAgentId)
                .containsExactlyInAnyOrder("own", "shared", "workspace");
        assertThat(agents.findVisible("alice", "shared")).hasSize(1);
        assertThat(agents.findVisible("alice", "private")).isEmpty();
        direct.getShares().clear();
        agents.flush();
        assertThat(agents.findVisible("alice", "shared")).isEmpty();
        assertThat(agents.findByAgentId("shared")).hasSize(1);
        assertThat(agents.findByAgentId("orphan")).isEmpty();
    }

    private AgentEntity save(String owner, String id, String type, String grantee) {
        AgentEntity e = new AgentEntity();
        e.setOwnerId(owner);
        e.setAgentId(id);
        e.setName(id);
        if (type != null)
            e.getShares().add(new AgentShareEntity(e, type, grantee, "RUN", 1L, owner));
        return agents.save(e);
    }
}
