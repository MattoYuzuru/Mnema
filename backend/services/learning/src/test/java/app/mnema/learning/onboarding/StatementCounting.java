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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the statements prepared on the connections of a thread, between {@link #start} and {@link #stop}, and the connections each thread holds (borrowed from the pool and not
 * yet closed) at any time ({@link #held}).
 */
@TestConfiguration(proxyBeanMethods = false)
class StatementCounting {
    private static final ThreadLocal<List<String>> LOG = new ThreadLocal<>();

    private static final ThreadLocal<AtomicInteger> HELD = ThreadLocal.withInitial(AtomicInteger::new);

    static void start() { LOG.set(new ArrayList<>()); }

    /** The connections the calling thread has borrowed from the pool and not closed: 0 for a thread that is between its statements, with no transaction open. */
    static int held() { return HELD.get().get(); }

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
        AtomicInteger held = HELD.get();
        held.incrementAndGet();
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close") && !closed[0]) {
                closed[0] = true;
                held.decrementAndGet();
            }
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
