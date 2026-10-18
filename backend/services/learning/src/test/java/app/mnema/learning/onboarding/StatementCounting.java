package app.mnema.learning.onboarding;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** Counts the statements prepared on the connections of a thread, between {@link #start} and {@link #stop}. */
@TestConfiguration(proxyBeanMethods = false)
class StatementCounting {
    private static final ThreadLocal<List<String>> LOG = new ThreadLocal<>();

    static void start() { LOG.set(new ArrayList<>()); }

    static List<String> stop() {
        List<String> log = LOG.get();
        LOG.remove();
        return log;
    }

    @Bean
    static BeanPostProcessor countingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source) || Proxy.isProxyClass(bean.getClass())) return bean;
                return Proxy.newProxyInstance(source.getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = call(source, method, args);
                    return method.getName().equals("getConnection") ? counted((Connection) result) : result;
                });
            }
        };
    }

    private static Connection counted(Connection connection) {
        return (Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            List<String> log = LOG.get();
            if (log != null && method.getName().startsWith("prepare") && args != null && args.length > 0 && args[0] instanceof String sql) log.add(sql);
            return call(connection, method, args);
        });
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
