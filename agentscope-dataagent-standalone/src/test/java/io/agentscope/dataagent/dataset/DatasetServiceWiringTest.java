package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Both independently added policies must survive Spring constructor selection after integration. */
class DatasetServiceWiringTest {
    @Test
    void springWiringEnforcesSharedSourceAuthorizationAndKeepsTransactionalDeletionCleanup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var constructor =
                    Arrays.stream(DatasetService.class.getConstructors())
                            .filter(c -> c.isAnnotationPresent(Autowired.class))
                            .findFirst()
                            .orElseThrow();
            for (Class<?> type : constructor.getParameterTypes()) {
                if (type != List.class)
                    context.getBeanFactory().registerSingleton(type.getName(), mock(type));
            }
            context.register(DatasetService.class);
            context.refresh();
            var service = context.getBean(DatasetService.class);
            var source = context.getBean(ExternalDataSourceAccess.class);
            var denied = new DatasetException("未授权", 403);
            when(source.lock("source")).thenThrow(denied);
            assertThatThrownBy(
                            () ->
                                    service.associateTables(
                                            "outsider",
                                            "group",
                                            "source",
                                            "sales",
                                            List.of("orders"),
                                            false))
                    .isSameAs(denied);
            verify(source).lock("source");

            var cleanup = context.getBean(DatasetDeletionCleanup.class);
            var entity = new DatasetEntity();
            entity.setId("dataset");
            TransactionSynchronizationManager.initSynchronization();
            try {
                service.deleteEntity(entity);
                verify(cleanup).prepare(entity);
                var synchronization =
                        TransactionSynchronizationManager.getSynchronizations().get(0);
                synchronization.afterCommit();
                verify(cleanup).complete(null);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}
