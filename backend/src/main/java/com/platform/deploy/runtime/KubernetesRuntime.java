package com.platform.deploy.runtime;

import com.platform.common.exception.BusinessException;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s 运行时：后端不再执行 docker run，而是通过 K8s API 创建：
 * - 每 Agent 一个 Deployment（nginx 入口）
 * - 一个 ClusterIP Service
 * - 一个 nginx 模板 ConfigMap
 *
 * 只在集群内（存在 service account）自动启用；本机开发仍可用 DockerCliRuntime。
 */
@Slf4j
@Component
public class KubernetesRuntime implements ContainerRuntime {

    private static final String RESOURCE_LABEL = "aap.agent.resource";
    private static final String REPLICAS_ANNOTATION = "aap.agent.replicas";
    private static final String CONFIG_MAP_SUFFIX = "-nginx";

    @Value("${agent.deploy.k8s.namespace:}")
    private String configuredNamespace;

    @Value("${agent.deploy.runtime:auto}")
    private String runtimeMode;

    @Value("${agent.deploy.k8s.node-selector:}")
    private String nodeSelector;

    @Value("${agent.deploy.k8s.ingress.enabled:false}")
    private boolean ingressEnabled;

    @Value("${agent.deploy.k8s.ingress.class-name:nginx}")
    private String ingressClassName;

    @Value("${agent.deploy.k8s.ingress.host-template:}")
    private String ingressHostTemplate;

    @Value("${agent.deploy.k8s.ingress.tls-secret:}")
    private String ingressTlsSecret;

    private volatile KubernetesClient client;

    @Override
    public boolean available() {
        if (!"kubernetes".equalsIgnoreCase(runtimeMode)
                && System.getenv("KUBERNETES_SERVICE_HOST") == null) {
            return false;
        }
        try {
            client();
            return true;
        } catch (Exception e) {
            log.warn("K8s 客户端不可用：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean kubernetes() {
        return true;
    }

    @Override
    public String version() {
        try {
            return client().getKubernetesVersion().getGitVersion();
        } catch (Exception e) {
            log.warn("读取 K8s 版本失败：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public String create(CreateRequest request) {
        String namespace = namespace();
        try {
            String name = request.name();
            Map<String, String> labels = new LinkedHashMap<>(request.labels());
            labels.put(RESOURCE_LABEL, name);

            if (request.files() != null && !request.files().isEmpty()) {
                ConfigMap configMap = new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(name + CONFIG_MAP_SUFFIX)
                        .withNamespace(namespace)
                        .addToLabels(labels)
                        .endMetadata()
                        .withData(request.files())
                        .build();
                client().configMaps().inNamespace(namespace).resource(configMap).createOrReplace();
            }

            List<EnvVar> env = new ArrayList<>();
            for (Map.Entry<String, String> e : request.env().entrySet()) {
                env.add(new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build());
            }

            ContainerBuilder container = new ContainerBuilder()
                    .withName("nginx")
                    .withImage(request.image())
                    .withImagePullPolicy("IfNotPresent")
                    .withEnv(env)
                    .addNewPort().withName("http").withContainerPort(request.containerPort()).endPort()
                    .withResources(resources(request.extraArgs()));

            if (request.files() != null && !request.files().isEmpty()) {
                container.addNewVolumeMount()
                        .withName("nginx-template")
                        .withMountPath("/etc/nginx/templates")
                        .withReadOnly(true)
                        .endVolumeMount();
            }

            List<Volume> volumes = new ArrayList<>();
            if (request.files() != null && !request.files().isEmpty()) {
                volumes.add(new VolumeBuilder()
                        .withName("nginx-template")
                        .withNewConfigMap()
                        .withName(name + CONFIG_MAP_SUFFIX)
                        .endConfigMap()
                        .build());
            }

            Deployment deployment = new DeploymentBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .addToAnnotations(REPLICAS_ANNOTATION, String.valueOf(Math.max(1, request.replicas())))
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(Math.max(1, request.replicas()))
                    .withNewSelector()
                    .addToMatchLabels(RESOURCE_LABEL, name)
                    .endSelector()
                    .withNewTemplate()
                    .withNewMetadata()
                    .addToLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                    .withNodeSelector(nodeSelectorMap())
                    .withAutomountServiceAccountToken(false)
                    .addToContainers(container.build())
                    .withVolumes(volumes)
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build();
            client().apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();

            Service service = new ServiceBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                    .withType("ClusterIP")
                    .addToSelector(RESOURCE_LABEL, name)
                    .addNewPort()
                    .withName("http")
                    .withPort(80)
                    .withTargetPort(new IntOrString(request.containerPort()))
                    .endPort()
                    .endSpec()
                    .build();
            client().services().inNamespace(namespace).resource(service).createOrReplace();
            createIngress(name, namespace, labels);
            return name;
        } catch (Exception e) {
            throw new BusinessException(409, "创建 K8s Agent 运行时失败：" + message(e));
        }
    }

    @Override
    public void start(String resource) {
        scale(resource, desiredReplicas(resource));
    }

    @Override
    public void stop(String resource) {
        scale(resource, 0);
    }

    @Override
    public void remove(String resource) {
        String namespace = namespace();
        try {
            client().network().v1().ingresses().inNamespace(namespace).withName(resource).delete();
            client().services().inNamespace(namespace).withName(resource).delete();
            client().configMaps().inNamespace(namespace).withName(resource + CONFIG_MAP_SUFFIX).delete();
            client().apps().deployments().inNamespace(namespace).withName(resource).delete();
        } catch (Exception e) {
            throw new BusinessException(409, "删除 K8s Agent 运行时失败：" + message(e));
        }
    }

    @Override
    public Info inspect(String resource) {
        try {
            Deployment deployment = client().apps().deployments()
                    .inNamespace(namespace()).withName(resource).get();
            if (deployment == null) {
                return new Info(resource, false, "MISSING", "Deployment 不存在");
            }
            int desired = deployment.getSpec() == null || deployment.getSpec().getReplicas() == null
                    ? 0 : deployment.getSpec().getReplicas();
            int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null
                    ? 0 : deployment.getStatus().getReadyReplicas();
            String status = desired == 0 ? "STOPPED" : ready > 0 ? "RUNNING" : "PENDING";
            String error = "";
            if (deployment.getStatus() != null && deployment.getStatus().getConditions() != null) {
                for (var condition : deployment.getStatus().getConditions()) {
                    if ("False".equalsIgnoreCase(condition.getStatus())
                            && ("Available".equalsIgnoreCase(condition.getType())
                            || "Progressing".equalsIgnoreCase(condition.getType()))) {
                        error = condition.getMessage() == null ? condition.getReason() : condition.getMessage();
                        break;
                    }
                }
            }
            return new Info(resource, ready > 0, status, error == null ? "" : error);
        } catch (Exception e) {
            throw new BusinessException(502, "读取 K8s Agent 运行时状态失败：" + message(e));
        }
    }

    @Override
    public String logs(String resource, int tail) {
        try {
            var pods = client().pods().inNamespace(namespace())
                    .withLabel(RESOURCE_LABEL, resource).list().getItems();
            if (pods.isEmpty()) {
                throw new BusinessException(404, "Agent 运行时没有可用 Pod");
            }
            String pod = pods.stream()
                    .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
                    .map(p -> p.getMetadata().getName())
                    .findFirst()
                    .orElse(pods.get(0).getMetadata().getName());
            String log = client().pods().inNamespace(namespace()).withName(pod).getLog();
            if (log == null) return "";
            String[] lines = log.split("\\R", -1);
            int size = Math.min(Math.max(1, tail), lines.length);
            return String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - size), lines.length));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "读取 Agent 运行时日志失败：" + message(e));
        }
    }

    @Override
    public String endpoint(String resource) {
        String host = ingressHost(resource);
        if (host != null) {
            return (ingressTlsSecret != null && !ingressTlsSecret.isBlank() ? "https://" : "http://") + host + "/v1";
        }
        return "http://" + resource + "." + namespace() + ".svc.cluster.local/v1";
    }

    private void scale(String resource, int replicas) {
        try {
            Deployment deployment = client().apps().deployments()
                    .inNamespace(namespace()).withName(resource).get();
            if (deployment == null) throw new BusinessException(404, "Agent 运行时 Deployment 不存在");
            deployment.getSpec().setReplicas(Math.max(0, replicas));
            if (replicas > 0) {
                if (deployment.getMetadata().getAnnotations() == null) {
                    deployment.getMetadata().setAnnotations(new LinkedHashMap<>());
                }
                deployment.getMetadata().getAnnotations().put(REPLICAS_ANNOTATION, String.valueOf(replicas));
            }
            client().apps().deployments().inNamespace(namespace()).resource(deployment).update();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "调整 Agent 运行时副本失败：" + message(e));
        }
    }

    private int desiredReplicas(String resource) {
        try {
            Deployment deployment = client().apps().deployments()
                    .inNamespace(namespace()).withName(resource).get();
            if (deployment == null) return 1;
            String raw = deployment.getMetadata().getAnnotations() == null ? null
                    : deployment.getMetadata().getAnnotations().get(REPLICAS_ANNOTATION);
            return raw == null ? 1 : Math.max(1, Integer.parseInt(raw));
        } catch (Exception e) {
            return 1;
        }
    }

    private ResourceRequirements resources(List<String> extraArgs) {
        Map<String, Quantity> requests = new LinkedHashMap<>();
        Map<String, Quantity> limits = new LinkedHashMap<>();
        if (extraArgs != null) {
            for (int i = 0; i + 1 < extraArgs.size(); i++) {
                String arg = extraArgs.get(i);
                String value = extraArgs.get(i + 1);
                if ("--cpus".equals(arg)) {
                    Quantity cpu = new Quantity(value);
                    requests.put("cpu", new Quantity(cpuForRequest(value)));
                    limits.put("cpu", cpu);
                } else if ("--memory".equals(arg)) {
                    String memory = memoryQuantity(value);
                    requests.put("memory", new Quantity(memory));
                    limits.put("memory", new Quantity(memory));
                }
            }
        }
        return new ResourceRequirementsBuilder()
                .withRequests(requests)
                .withLimits(limits)
                .build();
    }

    private String cpuForRequest(String limit) {
        try {
            double v = Double.parseDouble(limit);
            return String.valueOf(Math.max(1, (int) Math.ceil(v / 2.0)));
        } catch (Exception e) {
            return "1";
        }
    }

    /** Docker 的 `512m/1g` 是 MiB/GiB；K8s 的 `m` 是毫字节，必须转成 Mi/Gi。 */
    static String memoryQuantity(String value) {
        if (value == null || value.isBlank()) return "512Mi";
        String raw = value.trim();
        String lower = raw.toLowerCase();
        if (lower.endsWith("mi") || lower.endsWith("gi") || lower.endsWith("ki")
                || lower.endsWith("ti") || lower.endsWith("mib") || lower.endsWith("gib")) {
            return raw;
        }
        if (lower.endsWith("g")) return raw.substring(0, raw.length() - 1) + "Gi";
        if (lower.endsWith("m")) return raw.substring(0, raw.length() - 1) + "Mi";
        if (lower.endsWith("k")) return raw.substring(0, raw.length() - 1) + "Ki";
        try {
            // 纯数字按 MiB 处理，避免 K8s 解释成字节数导致过小
            Long.parseLong(raw);
            return raw + "Mi";
        } catch (NumberFormatException e) {
            return "512Mi";
        }
    }

    private KubernetesClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        client = new KubernetesClientBuilder().build();
                    } catch (Exception e) {
                        throw new BusinessException(503, "初始化 K8s Client 失败：" + message(e));
                    }
                }
            }
        }
        return client;
    }

    private void createIngress(String resource, String namespace, Map<String, String> labels) {
        if (!ingressEnabled) return;
        String host = ingressHost(resource);
        if (host == null) return;
        try {
            var metadata = new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                    .withName(resource)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .addToAnnotations(Map.of(
                            "nginx.ingress.kubernetes.io/proxy-body-size", "2m",
                            "nginx.ingress.kubernetes.io/proxy-buffering", "off",
                            "nginx.ingress.kubernetes.io/proxy-read-timeout", "300",
                            "nginx.ingress.kubernetes.io/proxy-send-timeout", "300"))
                    .build();
            var specBuilder = new io.fabric8.kubernetes.api.model.networking.v1.IngressSpecBuilder()
                    .withIngressClassName(ingressClassName)
                    .addNewRule()
                    .withHost(host)
                    .withNewHttp()
                    .addNewPath()
                    .withPath("/")
                    .withPathType("Prefix")
                    .withNewBackend()
                    .withNewService()
                    .withName(resource)
                    .withNewPort().withNumber(80).endPort()
                    .endService()
                    .endBackend()
                    .endPath()
                    .endHttp()
                    .endRule();
            if (ingressTlsSecret != null && !ingressTlsSecret.isBlank()) {
                var tls = new io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder()
                        .withHosts(host)
                        .withSecretName(ingressTlsSecret)
                        .build();
                specBuilder.withTls(List.of(tls));
            }
            var ingress = new io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder()
                    .withMetadata(metadata)
                    .withSpec(specBuilder.build())
                    .build();
            client().network().v1().ingresses().inNamespace(namespace)
                    .resource(ingress).createOrReplace();
        } catch (Exception e) {
            log.warn("创建 Agent Ingress 失败（继续使用 ClusterIP）：{}", e.getMessage());
        }
    }

    private String ingressHost(String resource) {
        if (!ingressEnabled || ingressHostTemplate == null || ingressHostTemplate.isBlank()) return null;
        return renderIngressHost(ingressHostTemplate, resource);
    }

    static String renderIngressHost(String template, String resource) {
        String agentId = resource.startsWith("aap-agent-") ? resource.substring("aap-agent-".length()) : resource;
        return template.replace("{agentId}", agentId).replace("{name}", resource);
    }

    private Map<String, String> nodeSelectorMap() {
        Map<String, String> result = new LinkedHashMap<>();
        if (nodeSelector == null || nodeSelector.isBlank()) return result;
        for (String pair : nodeSelector.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    private String namespace() {
        if (configuredNamespace != null && !configuredNamespace.isBlank()) return configuredNamespace;
        String env = System.getenv("POD_NAMESPACE");
        if (env != null && !env.isBlank()) return env;
        try {
            Path file = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
            if (Files.isReadable(file)) return Files.readString(file).trim();
        } catch (Exception ignored) {
            // fall through
        }
        return "aap";
    }

    private String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
