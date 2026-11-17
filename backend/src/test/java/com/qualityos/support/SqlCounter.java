package com.qualityos.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Counts the SQL statements one thread prepares, through any connection of the application's data source: Hibernate's,
 * JdbcTemplate's and the tenant settings alike. For finding a query per row (N+1).
 */
public final class SqlCounter {

    private static final ThreadLocal<int[]> COUNT = new ThreadLocal<>();
    private static final Set<String> STATEMENTS = Set.of("prepareStatement", "prepareCall", "createStatement");

    private SqlCounter() {
    }

    public static void start() {
        COUNT.set(new int[1]);
    }

    public static int stop() {
        int[] count = COUNT.get();
        COUNT.remove();
        return count == null ? 0 : count[0];
    }

    /** Wraps the application's data source when registered as a bean in a test configuration. */
    public static BeanPostProcessor wrapDataSources() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource source ? wrap(source) : bean;
            }
        };
    }

    private static DataSource wrap(DataSource target) {
        return proxy(DataSource.class, target, (method, result) ->
                "getConnection".equals(method) ? proxy(Connection.class, (Connection) result, (inner, value) -> {
                    int[] count = COUNT.get();
                    if (count != null && STATEMENTS.contains(inner)) {
                        count[0]++;
                    }
                    return value;
                }) : result);
    }

    private interface After {
        Object apply(String method, Object result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, After after) {
        InvocationHandler handler = (self, method, args) -> {
            if ("unwrap".equals(method.getName()) && args[0] instanceof Class<?> wanted && wanted.isInstance(target)) {
                return target;
            }
            if ("isWrapperFor".equals(method.getName()) && args[0] instanceof Class<?> wanted && wanted.isInstance(target)) {
                return true;
            }
            try {
                return after.apply(method.getName(), method.invoke(target, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
