package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 对象存储（架构 3.8）：MinIO 预签名直传 + 回调存在性校验；Bucket 启动时确保存在。
 */
@Slf4j
@Service
public class StorageService {

    private final MinioClient client;
    private final String bucket;

    public StorageService(@Value("${storage.minio.endpoint}") String endpoint,
                          @Value("${storage.minio.access-key}") String accessKey,
                          @Value("${storage.minio.secret-key}") String secretKey,
                          @Value("${storage.minio.bucket}") String bucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(io.minio.MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建：{}", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 检查失败（服务不可用？）：{}", e.getMessage());
        }
    }

    /** 预签名 PUT（直传），默认 10 分钟有效。 */
    public String presignPut(String objectName) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.PUT)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(10, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(502, "生成上传地址失败：" + e.getMessage());
        }
    }

    /** 回调校验：对象必须真实存在（防伪造元数据，需求文档 7.2）。 */
    public boolean exists(String objectName) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new BusinessException(502, "对象存储不可用：" + e.getMessage());
        }
    }

    /** 读取对象字节（解析用）。 */
    public byte[] get(String objectName) {
        try (var in = client.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(502, "读取文件失败：" + e.getMessage());
        }
    }
}
