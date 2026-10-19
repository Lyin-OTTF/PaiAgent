package com.paiagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 迭代10 P1（SSRF 收口）：四个 provider 客户端（视频 / 图片 ×2 / AgentPlan）的
 * 私网拒绝锁定测试。
 *
 * <p><b>为什么需要这个类</b>：这些客户端的既有单测只覆盖 URL 归一化等纯函数，
 * 生产建连方法（{@code openConnection} / {@code httpExchange}）不被任何用例触达。
 * 若重构时弄掉 {@code UrlSafetyValidator.assertSafeOutbound} 调用，没有测试会挂——
 * 安全属性必须有测试锁定，否则等于没有。
 *
 * <p>这里用反射直接调用私网建连方法，断言「恶意 URL 在建立连接之前即被拒绝」
 * （IllegalArgumentException 是 assertSafeOutbound 的拒错类型；若校验被移除，
 * 代码会走到 {@code URI.create(...).toURL().openConnection()}，对
 * {@code http://169.254.169.254/...} 这类地址抛出的将是连接异常而非参数异常，
 * 或直接静默发起对内网地址的请求——两种走向都与本测试的期望不符，测试即失败）。
 *
 * <p>合法公网 URL「不被误伤」由 {@code ChatClientFactoryTest} 的本地 HTTP 端到端
 * 用例侧面覆盖（同一校验器、同一策略）；客户端层若再连真实公网验证会把单测
 * 变成网络依赖，故不在此重复。
 */
class ProviderClientSsrfGuardTest {

    /** 云元数据端点：SSRF 最典型的窃取目标（临时凭证）。 */
    private static final String CLOUD_METADATA = "http://169.254.169.254/latest/meta-data/";

    /** 回环地址。 */
    private static final String LOOPBACK = "http://127.0.0.1:8080/admin";

    /** 私网段。 */
    private static final String PRIVATE_NET = "http://192.168.1.1/";

    /** 非 http(s) 协议。 */
    private static final String FILE_SCHEME = "file:///etc/passwd";

    private static ResolvedAgentPlanConfig configWithApiUrl(String apiUrl) {
        return new ResolvedAgentPlanConfig(
                1L, "agnes", apiUrl, "secret-key", "model", null, null, null, false);
    }

    private static Method privateMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    /**
     * 反射调用并返回被检出的参数异常。
     *
     * @throws AssertionError 实际抛出的不是 {@link IllegalArgumentException}
     *                        （校验缺失、走到建连阶段时会落到这里）
     */
    private static IllegalArgumentException expectUnsafeUrlRejected(Method method, Object target,
                                                                    Object... args) throws Throwable {
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> method.invoke(target, args),
                "恶意 URL 必须在建连前被拒绝");
        Throwable cause = ex.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause,
                "拒绝原因应为 URL 安全校验（IllegalArgumentException），实际: " + cause);
        return (IllegalArgumentException) cause;
    }

    @Test
    @DisplayName("AgnesVideoClient：openConnection 拒绝云元数据/回环/私网/非 http 协议")
    void agnesVideoClientRejectsUnsafeUrls() throws Throwable {
        Method openConnection = privateMethod(AgnesVideoClient.class,
                "openConnection", String.class, String.class, String.class);
        AgnesVideoClient client = new AgnesVideoClient();

        for (String unsafe : new String[]{CLOUD_METADATA, LOOPBACK, PRIVATE_NET, FILE_SCHEME}) {
            expectUnsafeUrlRejected(openConnection, client, unsafe, "POST", "secret-key");
        }
    }

    @Test
    @DisplayName("OpenAiCompatibleImageClient：openConnection 拒绝云元数据/回环/私网/非 http 协议")
    void openAiCompatibleImageClientRejectsUnsafeUrls() throws Throwable {
        Method openConnection = privateMethod(OpenAiCompatibleImageClient.class,
                "openConnection", ResolvedAgentPlanConfig.class);
        OpenAiCompatibleImageClient client = new OpenAiCompatibleImageClient();

        for (String unsafe : new String[]{CLOUD_METADATA, LOOPBACK, PRIVATE_NET, FILE_SCHEME}) {
            expectUnsafeUrlRejected(openConnection, client, configWithApiUrl(unsafe));
        }
    }

    @Test
    @DisplayName("StepFunImageClient：openConnection 拒绝云元数据/回环/私网/非 http 协议")
    void stepFunImageClientRejectsUnsafeUrls() throws Throwable {
        Method openConnection = privateMethod(StepFunImageClient.class,
                "openConnection", ResolvedAgentPlanConfig.class);
        StepFunImageClient client = new StepFunImageClient();

        for (String unsafe : new String[]{CLOUD_METADATA, LOOPBACK, PRIVATE_NET, FILE_SCHEME}) {
            expectUnsafeUrlRejected(openConnection, client, configWithApiUrl(unsafe));
        }
    }

    @Test
    @DisplayName("VolcengineAgentPlanClient：httpExchange 拒绝云元数据/回环/私网/非 http 协议")
    void volcengineAgentPlanClientRejectsUnsafeUrls() throws Throwable {
        Method httpExchange = privateMethod(VolcengineAgentPlanClient.class,
                "httpExchange", String.class, String.class, Map.class, String.class);

        for (String unsafe : new String[]{CLOUD_METADATA, LOOPBACK, PRIVATE_NET, FILE_SCHEME}) {
            expectUnsafeUrlRejected(httpExchange, null, unsafe, "POST", Map.of(), "{}");
        }
    }
}
