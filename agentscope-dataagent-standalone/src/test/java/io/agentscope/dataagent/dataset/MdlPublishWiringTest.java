package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class MdlPublishWiringTest {
    @Test
    void springCreatesPublisherWithPermissionDeletionAndEventDependenciesTogether() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatasetRepository.class, () -> mock(DatasetRepository.class));
            context.registerBean(
                    DatasetGroupRepository.class, () -> mock(DatasetGroupRepository.class));
            context.registerBean(
                    ExternalDataSourceRepository.class,
                    () -> mock(ExternalDataSourceRepository.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(WrenProperties.class, () -> new WrenProperties());
            context.registerBean(WrenCli.class, () -> mock(WrenCli.class));
            context.registerBean(WrenProfileHome.class, () -> mock(WrenProfileHome.class));
            context.registerBean(WrenSourceProber.class, () -> mock(WrenSourceProber.class));
            context.registerBean(MdlSeeder.class, () -> mock(MdlSeeder.class));
            context.registerBean(MdlWorkspaceService.class, () -> mock(MdlWorkspaceService.class));
            context.registerBean(MdlWorkspaceReader.class, () -> mock(MdlWorkspaceReader.class));
            context.registerBean(
                    KnowledgeBaseOperations.class, () -> mock(KnowledgeBaseOperations.class));
            context.registerBean(
                    KnowledgeBaseAccessService.class, () -> mock(KnowledgeBaseAccessService.class));
            context.register(MdlPublishService.class);
            context.refresh();
            var service = context.getBean(MdlPublishService.class);
            assertThat(ReflectionTestUtils.getField(service, "operations"))
                    .isSameAs(context.getBean(KnowledgeBaseOperations.class));
            assertThat(ReflectionTestUtils.getField(service, "access"))
                    .isSameAs(context.getBean(KnowledgeBaseAccessService.class));
            assertThat(ReflectionTestUtils.getField(service, "events")).isSameAs(context);
        }
    }
}
