package com.platform.deploy.runtime;

import java.util.List;
import java.util.Map;

/**
 * 容器运行时抽象：当前只有 Docker CLI 实现，后续可加 K8s 实现。
 * 所有方法失败时抛 BusinessException（对上层给出可读错误）。
 */
public interface ContainerRuntime {

    /** Docker 守护进程是否可用（CLI 存在且能连上 daemon）。 */
    boolean available();

    /** 服务端版本号，不可用时返回 null。 */
    String version();

    /** 创建容器并返回容器 ID（不启动）。 */
    String create(CreateRequest request);

    void start(String container);

    void stop(String container);

    void remove(String container);

    Info inspect(String container);

    String logs(String container, int tail);

    record CreateRequest(String name, String image, String hostBind, int hostPort, int containerPort,
                         Map<String, String> env, Map<String, String> labels,
                         List<String> mounts, List<String> extraArgs) {
    }

    record Info(String id, boolean running, String status, String error) {
    }
}
