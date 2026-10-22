package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;

class WrenQueryPreparationTest {
    @TempDir Path home;

    @Test
    void afterCommitPreparationAdmissionFailureDoesNotFailPublication() {
        var projects = mock(WrenPublishedProject.class);
        var operations = mock(KnowledgeBaseOperations.class);
        var scheduler = mock(Scheduler.class);
        var groups = mock(DatasetGroupService.class);
        when(groups.queryGroup("alice", "a")).thenThrow(new DatasetException("资源已不可用", 404));
        var service = new WrenQueryPreparation(projects, operations, scheduler, null, groups);
        service.changed(new PublishedQueryProjectChanged("alice", "a"));
        verifyNoInteractions(projects, scheduler);
        service.close();
    }

    @Test
    void warmsActualGatewayAfterInvalidationAndHidesStateAfterRevocationOrEviction() {
        var projects = mock(WrenPublishedProject.class);
        var scheduler = mock(Scheduler.class);
        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class)))
                .thenAnswer(
                        c -> {
                            tasks.add(c.getArgument(0));
                            return Disposables.disposed();
                        });
        var gateway =
                mock(
                        io.agentscope.dataagent.runtime.wren.WrenQueryGateway.class,
                        org.mockito.Mockito.CALLS_REAL_METHODS);
        when(gateway.call(eq("a"), eq("recall_queries"), any()))
                .thenReturn(
                        new io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult(
                                true, "{}"));
        when(gateway.recallWarm("a")).thenReturn(true);
        org.springframework.beans.factory.ObjectProvider<
                        io.agentscope.dataagent.runtime.wren.WrenQueryGateway>
                provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        var groups = mock(DatasetGroupService.class);
        var group =
                new io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity(
                        "a", "alice", "测试", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("alice", "a")).thenReturn(group);
        when(groups.queryGroup("bob", "a")).thenThrow(new DatasetException("无权访问", 403));
        var operations =
                new KnowledgeBaseOperations(
                        new WrenProperties("wren", home.toString(), "test", "mysql", 30));
        var service = new WrenQueryPreparation(projects, operations, scheduler, provider, groups);
        var event = new PublishedQueryProjectChanged("alice", "a");
        service.invalidatePublished(event);
        service.changed(event);
        assertThat(service.status("alice", "a").state()).isEqualTo("QUEUED");
        tasks.get(0).run();
        assertThat(service.status("alice", "a").state()).isEqualTo("READY");
        var order = org.mockito.Mockito.inOrder(gateway, projects);
        order.verify(gateway).invalidate("a");
        order.verify(projects).indexed(eq("alice"), eq("a"), any(RuntimeContext.class));
        order.verify(gateway).warmRecall(eq("a"), any(Runnable.class));
        verify(gateway)
                .call("a", "recall_queries", java.util.Map.of("question", "检索准备检查", "limit", 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.status("bob", "a"))
                .isInstanceOf(DatasetException.class);
        when(gateway.recallWarm("a")).thenReturn(false);
        assertThat(service.status("alice", "a").state()).isEqualTo("NOT_READY");
        when(gateway.call(eq("a"), eq("recall_queries"), any()))
                .thenReturn(
                        new io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult(
                                false, "failed"));
        service.changed(event);
        tasks.get(1).run();
        assertThat(service.status("alice", "a").state()).isEqualTo("FAILED");
        service.close();
    }

    @Test
    void coalescesQueuedEventsKeepsGroupsSeparateAndRetriesAfterFailure() {
        var projects = mock(WrenPublishedProject.class);
        var scheduler = mock(Scheduler.class);
        List<Runnable> work = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class)))
                .thenAnswer(
                        c -> {
                            work.add(c.getArgument(0));
                            return Disposables.disposed();
                        });
        var operations =
                new KnowledgeBaseOperations(
                        new WrenProperties("wren", home.toString(), "test", "mysql", 30));
        try (var service = new Fixture(projects, operations, scheduler)) {
            when(projects.indexed(eq("alice"), eq("a"), any(RuntimeContext.class)))
                    .thenThrow(new DatasetException("索引失败", 502))
                    .thenReturn(home);
            service.value.changed(new PublishedQueryProjectChanged("alice", "a"));
            service.value.changed(new PublishedQueryProjectChanged("alice", "a"));
            service.value.changed(new PublishedQueryProjectChanged("bob", "b"));
            assertThat(work).hasSize(2);
            work.get(0).run();
            work.get(1).run();
            service.value.changed(new PublishedQueryProjectChanged("alice", "a"));
            assertThat(work).hasSize(3);
            work.get(2).run();
            verify(projects, times(2)).indexed(eq("alice"), eq("a"), any(RuntimeContext.class));
            verify(projects).indexed(eq("bob"), eq("b"), any(RuntimeContext.class));
            verify(projects, never()).indexed(eq("bob"), eq("a"), any(RuntimeContext.class));
        }
    }

    @Test
    void transactionCommitSchedulesAndRollbackDoesNot() {
        var scheduler = mock(Scheduler.class);
        var projects = mock(WrenPublishedProject.class);
        var operations = mock(KnowledgeBaseOperations.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "transactionalEventListenerFactory", TransactionalEventListenerFactory.class);
            context.registerBean(
                    WrenQueryPreparation.class,
                    () -> new WrenQueryPreparation(projects, operations, scheduler));
            context.refresh();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                context.publishEvent(new PublishedQueryProjectChanged("alice", "a"));
                verifyNoInteractions(scheduler);
                for (var sync : TransactionSynchronizationManager.getSynchronizations())
                    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                verifyNoInteractions(scheduler);
            } finally {
                TransactionSynchronizationManager.clear();
            }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                context.publishEvent(new PublishedQueryProjectChanged("alice", "a"));
                verifyNoInteractions(scheduler);
                for (var sync : TransactionSynchronizationManager.getSynchronizations())
                    sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
                verify(scheduler).schedule(any(Runnable.class));
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    private record Fixture(WrenQueryPreparation value) implements AutoCloseable {
        Fixture(
                WrenPublishedProject projects,
                KnowledgeBaseOperations operations,
                Scheduler scheduler) {
            this(new WrenQueryPreparation(projects, operations, scheduler));
        }

        public void close() {
            value.close();
        }
    }
}
