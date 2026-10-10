package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

/** Tracks the entire synchronous service invocation, including its transaction commit/rollback. */
@Configuration(proxyBeanMethods = false)
public class KnowledgeBaseOperationAdvisor {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor knowledgeBaseOperationGate(
            ObjectProvider<KnowledgeBaseOperations> operations,
            ObjectProvider<DatasetRepository> datasets) {
        var pointcut =
                new StaticMethodMatcherPointcut() {
                    @Override
                    public boolean matches(Method method, Class<?> target) {
                        String name = target.getName();
                        boolean service =
                                name.startsWith("io.agentscope.dataagent.dataset.")
                                        && (target.getSimpleName().endsWith("Service")
                                                || target.getSimpleName().endsWith("Store")
                                                || target.getSimpleName().equals("MdlSeeder")
                                                || target.getSimpleName()
                                                        .equals("MdlWorkspaceReader")
                                                || target.getSimpleName().equals("MdlCatalog")
                                                || target.getSimpleName()
                                                        .equals("AnswerQueryMemory"));
                        boolean wren =
                                name.equals(
                                                "io.agentscope.dataagent.runtime.wren.WrenInstanceRegistry")
                                        && (method.getName().equals("call")
                                                || method.getName().equals("callDraft"));
                        if ((!service && !wren)
                                || method.getName().equals("deleteGroup")
                                || method.getName().equals("countDatasets")
                                || method.getName().equals("deletionPending")
                                || target.getSimpleName().equals("KnowledgeBaseDeletionService"))
                            return false;
                        for (Parameter parameter : method.getParameters()) {
                            if (parameter.getName().equals("groupId")
                                    || parameter.getName().equals("group")
                                    || parameter.getName().equals("groupIds")
                                    || parameter.getType() == DatasetEntity.class
                                    || (target.getSimpleName().equals("DatasetService")
                                            && parameter.getName().equals("datasetId")))
                                return true;
                        }
                        return false;
                    }
                };
        MethodInterceptor interceptor =
                invocation -> {
                    var ids = new java.util.LinkedHashSet<String>();
                    Parameter[] params = invocation.getMethod().getParameters();
                    Object[] args = invocation.getArguments();
                    for (int i = 0; i < params.length; i++) {
                        if (args[i] instanceof DatasetEntity entity) ids.add(entity.getGroupId());
                        else if ((params[i].getName().equals("groupId")
                                        || params[i].getName().equals("group"))
                                && args[i] instanceof String id) ids.add(id);
                        else if (params[i].getName().equals("groupIds")
                                && args[i] instanceof List<?> list) {
                            for (Object id : list) if (id instanceof String value) ids.add(value);
                        } else if (params[i].getName().equals("datasetId")
                                && args[i] instanceof String id) {
                            datasets.getObject()
                                    .findById(id)
                                    .ifPresent(entity -> ids.add(entity.getGroupId()));
                        }
                    }
                    List<AutoCloseable> leases = new ArrayList<>();
                    try {
                        for (String id : ids) leases.add(operations.getObject().enter(id));
                        return invocation.proceed();
                    } finally {
                        for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
                    }
                };
        var advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return advisor;
    }
}
