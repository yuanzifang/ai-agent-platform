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

    /** 是否 K8s 运行时；默认 Docker。 */
    default boolean kubernetes() {
        return false;
    }

    /** K8s Service 的内部访问地址；Docker 返回 null。 */
    default String endpoint(String resource) {
        return null;
    }

    /** 创建容器/Deployment 并返回资源名（不启动）。 */
    String create(CreateRequest request);

    void start(String container);

    void stop(String container);

    void remove(String container);

    Info inspect(String container);

    String logs(String container, int tail);

    record CreateRequest(String name, String image, String hostBind, int hostPort, int containerPort,
                         Map<String, String> env, Map<String, String> labels,
                         List<String> mounts, List<String> extraArgs,
                         int replicas, Map<String, String> files) {

        /** Docker 调用兼容构造器（单副本、无 ConfigMap 文件）。 */
        public CreateRequest(String name, String image, String hostBind, int hostPort, int containerPort,
                             Map<String, String> env, Map<String, String> labels,
                             List<String> mounts, List<String> extraArgs) {
            this(name, image, hostBind, hostPort, containerPort, env, labels, mounts, extraArgs, 1, Map.of());
        }
    }

    record Info(String id, boolean running, String status, String error) {
    }
}
