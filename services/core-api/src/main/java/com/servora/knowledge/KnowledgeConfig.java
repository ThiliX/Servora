package com.servora.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class KnowledgeConfig {

    @ConfigurationProperties("servora.knowledge")
    @Validated
    public record KnowledgeProperties(
            @Min(1) long maxUploadBytes,
            @Min(1) int maxDocumentsPerWorkspace,
            @Min(1) int uploadsPerHour) {}

    @ConfigurationProperties("servora.storage")
    @Validated
    public record StorageProperties(
            @NotBlank String endpoint,
            @NotBlank String region,
            @NotBlank String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey) {}

    @ConfigurationProperties("servora.ingestion")
    @Validated
    public record IngestionProperties(
            @NotBlank String enqueueUrl,
            /** Base64 HMAC key shared with the document worker, at least 32 bytes. */
            @NotBlank String tokenSecret,
            @NotNull Duration tokenTtl,
            @NotNull Duration requestTimeout) {}

    @ConfigurationProperties("servora.outbox")
    @Validated
    public record OutboxProperties(boolean enabled, @Min(1) int maxAttempts, @NotNull Duration retryBaseDelay) {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DocumentStorage.class)
    S3Client documentS3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // Path-style addressing works with every S3-compatible store, including local emulators.
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DocumentStorage.class)
    DocumentStorage documentStorage(S3Client documentS3Client, StorageProperties properties) {
        return new S3DocumentStorage(documentS3Client, properties.bucket());
    }

    /** S3-compatible implementation. The bucket is private; nothing here creates a public URL. */
    public static final class S3DocumentStorage implements DocumentStorage {

        private final S3Client s3;
        private final String bucket;
        private final AtomicBoolean bucketChecked = new AtomicBoolean();

        public S3DocumentStorage(S3Client s3, String bucket) {
            this.s3 = s3;
            this.bucket = bucket;
        }

        @Override
        public void put(String key, byte[] content, String contentType) {
            ensureBucket();
            s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
        }

        @Override
        public void delete(String key) {
            // S3 DeleteObject succeeds whether or not the key exists.
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        }

        /** Convenience for local development, where the bucket may not exist yet. */
        private void ensureBucket() {
            if (bucketChecked.get()) {
                return;
            }
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
            }
            bucketChecked.set(true);
        }
    }
}
