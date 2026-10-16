package com.paiagent.service;

import com.paiagent.common.SafeHttpFetcher;
import com.paiagent.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Slf4j
@Service
public class MinioService {

    /** 连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读取超时（毫秒）：转存大文件时给足时间。 */
    private static final int READ_TIMEOUT_MS = 30000;

    /** 单次 URL 转存的大小上限（迭代9 P1-7）：修复前无上限，超大响应可打爆磁盘/内存。 */
    private static final int MAX_TRANSFER_BYTES = 32 * 1024 * 1024;

    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private MinioConfig minioConfig;
    
    /**
     * 确保 bucket 存在
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .build()
        );
        
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .build()
            );
            log.info("创建 MinIO bucket: {}", minioConfig.getBucketName());
        }
    }
    
    /**
     * 上传文件到 MinIO
     * @param objectName 对象名称 (如: audio/xxx.wav)
     * @param inputStream 文件流
     * @param contentType 文件类型
     * @return 文件的公共访问 URL
     */
    public String uploadFile(String objectName, InputStream inputStream, String contentType, long size) throws Exception {
        ensureBucketExists();
        
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
                        .stream(inputStream, size, -1)
                        .contentType(contentType)
                        .build()
        );
        
        log.info("文件上传成功到 MinIO: {}", objectName);
        
        // 返回公共访问 URL
        return minioConfig.getPublicUrl() + "/" + minioConfig.getBucketName() + "/" + objectName;
    }
    
    /**
     * 从 URL 下载文件并上传到 MinIO。
     *
     * <p>迭代7（评审报告 A4 · 同类修复）：转存来源同样是用户/模型可控的 URL，
     * 补齐与 {@code WebFetchNodeExecutor} 同级的防护——协议与内网地址校验、
     * 连接/读取超时、不自动跟随重定向；同时修掉原先「两次 {@code openConnection()}
     * 导致内容长度可能取自另一条连接」的问题。
     *
     * <p>迭代9 P1-7：抓取统一收敛到 {@link SafeHttpFetcher#fetchBytes}（逐跳安全校验 +
     * 超时 + 大小上限），修复前 {@code putObject(stream, contentLength, -1)} 对
     * 「未声明长度」的响应会无上限转存，磁盘/内存可被打爆。
     */
    public String uploadFromUrl(String fileUrl, String objectName, String contentType) throws Exception {
        log.info("从 URL 下载文件: {}", fileUrl);
        byte[] data = SafeHttpFetcher.fetchBytes(fileUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_TRANSFER_BYTES);
        return uploadFromBytes(data, objectName, contentType);
    }
    
    /**
     * 从字节数组上传到 MinIO
     * @param data 字节数组
     * @param objectName 对象名称
     * @param contentType 文件类型
     * @return MinIO 公共 URL
     */
    public String uploadFromBytes(byte[] data, String objectName, String contentType) throws Exception {
        log.info("从字节数组上传文件到 MinIO: {}, 大小: {} bytes", objectName, data.length);
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            return uploadFile(objectName, inputStream, contentType, data.length);
        }
    }
}